package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.calculator.CosmicCalculator;
import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.generator.UseCaseDiagramGenerator;
import com.cosmic.vpplugin.json.MiniJsonParser;
import com.cosmic.vpplugin.listener.CosmicModelChangeListener;
import com.cosmic.vpplugin.model.CosmicJsonMapper;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.model.IActor;
import com.vp.plugin.model.IAssociation;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IUseCase;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Motore di COSMIC AI: e' l'UNICA classe che parla con la rete (LLM) e con
 * le classi di business (mapper JSON, calcolatore COSMIC, generatore UML).
 * Non importa nulla di Swing "di presentazione" (nessun JPanel/JDialog):
 * riceve testo, produce risultati, notifica un {@link CosmicAnalysisListener}.
 *
 * Ciclo di vita: un'unica istanza per l'intera durata del plugin, creata in
 * {@link com.cosmic.vpplugin.CosmicPlugin#loaded} e fermata in
 * {@link com.cosmic.vpplugin.CosmicPlugin#unloaded}.
 *
 * NOVITA' di questa revisione:
 *  - Endpoint LLM aggiornato al tunnel LM Link locale.
 *  - Memoria dell'ultima analisi ({@link #currentModel}, {@link #lastReport},
 *    {@link #lastRequirementsText}) condivisa da chat, validazione e
 *    ricalcolo in tempo reale (si vedano rispettivamente
 *    {@link #sendChatMessage}, {@link #validateModelAgainstRequirements},
 *    {@link #handleModelElementAdded}/{@link #handleModelElementRemoved}).
 *  - Un "UI listener attivo" ({@link #setActiveUiListener}/
 *    {@link #clearActiveUiListener}), agganciato dal dialog mentre e'
 *    visibile: e' il canale con cui il ricalcolo scattato da una modifica
 *    manuale del diagramma torna a farsi vedere nella UI (cfpLabel/log),
 *    riusando l'esistente {@code onAnalysisCompleted(model, report)} senza
 *    dover toccare l'interfaccia {@link CosmicAnalysisListener}.
 */
public final class CosmicAiService {

    private static final CosmicAiService INSTANCE = new CosmicAiService();

    public static CosmicAiService getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Configurazione LLM
    // ------------------------------------------------------------------

    private static final String LLM_ENDPOINT_URL = "http://localhost:1234/v1/chat/completions";
    private static final String LLM_MODEL = "openai/gpt-oss-20b";
    private static final Duration LLM_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final int PROJECT_WATCHER_INTERVAL_MS = 1500;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private ExecutorService executor;

    private IProject watchedProject;

    private final CosmicModelChangeListener modelChangeListener = new CosmicModelChangeListener();

    private Timer projectWatcherTimer;

    // ------------------------------------------------------------------
    // Memoria dell'ultima analisi (Task 2/3/4)
    // ------------------------------------------------------------------

    /**
     * Modello "vivo": e' sia il risultato dell'ultima analisi LLM sia,
     * dopo, l'immagine mantenuta sincronizzata con le modifiche manuali
     * dell'utente sul diagramma (si veda {@link #handleModelElementAdded}).
     * Null finche' nessuna analisi e' mai andata a buon fine.
     */
    private CosmicJsonModel currentModel;

    /** Ultimo report COSMIC calcolato su {@link #currentModel} (LLM o ricalcolo manuale). */
    private CosmicReport lastReport;

    /** Testo dei requisiti originali usato per generare {@link #currentModel} (serve alla validazione, Task 3). */
    private String lastRequirementsText;

    /** Listener della UI attualmente visibile, se presente: riceve gli aggiornamenti del ricalcolo in tempo reale. */
    private volatile CosmicAnalysisListener activeUiListener;

    private CosmicAiService() {
        // Collega il listener di modello al Service: da qui in avanti ogni
        // IUseCase/IAssociation aggiunto o rimosso a mano dall'utente passa
        // da qui (Task 4).
        modelChangeListener.setOnModelAdded(this::handleModelElementAdded);
        modelChangeListener.setOnModelRemoved(this::handleModelElementRemoved);
    }

    // ------------------------------------------------------------------
    // Ciclo di vita
    // ------------------------------------------------------------------

    public void start() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cosmic-ai-llm-call");
            t.setDaemon(true);
            return t;
        });
        startProjectWatcher();
    }

    public void stop() {
        if (projectWatcherTimer != null) {
            projectWatcherTimer.stop();
            projectWatcherTimer = null;
        }
        detachModelChangeListener();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Registra il dialog corrente come destinatario degli aggiornamenti "live" (chiamato da shown()). */
    public void setActiveUiListener(CosmicAnalysisListener listener) {
        this.activeUiListener = listener;
    }

    /** Deregistra il dialog (chiamato da canClosed()): evita di notificare una UI ormai chiusa. */
    public void clearActiveUiListener(CosmicAnalysisListener listener) {
        if (this.activeUiListener == listener) {
            this.activeUiListener = null;
        }
    }

    private void startProjectWatcher() {
        projectWatcherTimer = new Timer(PROJECT_WATCHER_INTERVAL_MS, e -> {
            IProject currentProject = ApplicationManager.instance().getProjectManager().getProject();
            if (currentProject != watchedProject) {
                detachModelChangeListener();
                if (currentProject != null) {
                    attachModelChangeListener(currentProject);
                }
            }
        });
        projectWatcherTimer.setRepeats(true);
        projectWatcherTimer.start();
    }

    private void attachModelChangeListener(IProject project) {
        try {
            project.addProjectListener(modelChangeListener);
            project.addProjectDiagramListener(modelChangeListener);
            project.addProjectModelListener(modelChangeListener);
            watchedProject = project;
        } catch (Exception ex) {
            System.out.println("[COSMIC AI] Aggancio dei listener di modello non riuscito: " + ex);
        }
    }

    private void detachModelChangeListener() {
        if (watchedProject == null) {
            return;
        }
        try {
            watchedProject.removeProjectListener(modelChangeListener);
            watchedProject.removeProjectDiagramListener(modelChangeListener);
            watchedProject.removeProjectModelListener(modelChangeListener);
        } catch (Exception ignored) {
            // progetto probabilmente gia' chiuso: nulla da fare
        }
        watchedProject = null;
    }

    // ------------------------------------------------------------------
    // Pipeline principale: testo requisiti -> LLM -> diagramma + CFP
    // ------------------------------------------------------------------

    public void analyzeRequirements(String rawRequirementsText, CosmicAnalysisListener listener) {
        if (rawRequirementsText == null || rawRequirementsText.isBlank()) {
            listener.onAnalysisFailed(new IllegalArgumentException("Nessun testo di requisiti fornito."));
            return;
        }
        listener.onBusyStateChanged(true);
        listener.onLog("Invio richiesta al server LLM (" + LLM_ENDPOINT_URL + ", modello " + LLM_MODEL + ")...");

        executor.submit(() -> {
            try {
                String responseBody = callLlm(buildCosmicSystemPrompt(), rawRequirementsText);
                String assistantContent = extractAssistantContent(responseBody);
                String cleanedJson = stripMarkdownFences(assistantContent);
                SwingUtilities.invokeLater(() -> {
                    listener.onLog("Risposta LLM ricevuta (" + cleanedJson.length() + " caratteri).");
                    runPipelineOnJson(cleanedJson, rawRequirementsText, listener);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    listener.onBusyStateChanged(false);
                    listener.onAnalysisFailed(ex);
                });
            }
        });
    }

    /** Deve essere invocato SEMPRE sull'EDT: tocca l'Open API di Visual Paradigm. */
    private void runPipelineOnJson(String jsonText, String originalRequirementsText, CosmicAnalysisListener listener) {
        try {
            listener.onLog("Parsing JSON e generazione diagramma in corso...");
            CosmicJsonModel model = CosmicJsonMapper.map(jsonText);

            UseCaseDiagramGenerator generator = new UseCaseDiagramGenerator();
            generator.setLogSink(listener::onLog);
            generator.generate(model);

            listener.onLog("Diagramma generato: " + model.useCases.size() + " Use Case, "
                    + model.actors.size() + " Attori.");

            listener.onLog("Calcolo COSMIC Function Points (CFP) in corso...");
            CosmicReport report = new CosmicCalculator().compute(model);
            listener.onLog(report.toText());
            listener.onLog("Misurazione completata: " + report.totalCfp + " CFP totali.");

            // Aggiorna la memoria condivisa (Task 2/3/4): da qui in avanti
            // chat, validazione e ricalcolo manuale lavorano su QUESTO modello.
            this.currentModel = model;
            this.lastReport = report;
            this.lastRequirementsText = originalRequirementsText;

            listener.onBusyStateChanged(false);
            listener.onAnalysisCompleted(model, report);
        } catch (Exception e) {
            listener.onBusyStateChanged(false);
            listener.onAnalysisFailed(e);
        }
    }

    // ------------------------------------------------------------------
    // Task 2: Assistente di chat con memoria ("Suggeritore COSMIC")
    // ------------------------------------------------------------------

    public void sendChatMessage(String userMessage, Consumer<String> onReply, Consumer<Throwable> onError) {
        executor.submit(() -> {
            try {
                String systemPrompt = buildChatSuggesterSystemPrompt();
                String requestBody = buildChatCompletionRequestJson(systemPrompt, userMessage);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(LLM_ENDPOINT_URL))
                        .timeout(LLM_REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("Il server LLM ha risposto con codice HTTP "
                            + response.statusCode() + ": " + truncate(response.body(), 500));
                }
                String reply = extractAssistantContent(response.body());
                SwingUtilities.invokeLater(() -> onReply.accept(reply));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> onError.accept(ex));
            }
        });
    }

    /**
     * System prompt dell'assistente di chat. Rispetto alla versione
     * precedente (stateless), qui:
     *  1) l'LLM viene istruito a comportarsi da "Suggeritore COSMIC" secondo
     *     il paper CosMet, motivando le classificazioni E/X/R/W quando
     *     richiesto;
     *  2) se {@link #currentModel} non e' null, viene accodato un riassunto
     *     testuale dell'ultima analisi (Use Case, Processi Funzionali, CFP),
     *     cosi' l'assistente puo' rispondere a domande su "quello che ha
     *     appena generato" senza che l'utente debba ripetere il contesto.
     */
    private String buildChatSuggesterSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("Sei il 'Suggeritore COSMIC' dell'assistente COSMIC AI integrato in Visual Paradigm, ")
          .append("basato sul metodo descritto nel paper CosMet (De Vito et al.). Il tuo compito e' ")
          .append("aiutare l'utente a capire e verificare la misurazione COSMIC del progetto corrente.\n\n")
          .append("Regole di classificazione dei Data Movement che devi saper spiegare quando richiesto:\n")
          .append("- Entry (E): i dati entrano nel processo funzionale provenienti da un Functional User ")
          .append("(es. l'utente compila e invia un form).\n")
          .append("- Exit (X): i dati escono dal processo verso un Functional User (es. un messaggio di ")
          .append("conferma o di errore mostrato a schermo; un Exit va contato UNA sola volta per processo ")
          .append("anche se il messaggio puo' avere piu' varianti, es. conferma/errore).\n")
          .append("- Read (R): i dati vengono letti dalla memoria persistente (storage) verso il processo.\n")
          .append("- Write (W): i dati vengono scritti dal processo verso la memoria persistente.\n")
          .append("- Un processo funzionale valido deve contenere almeno 2 CFP, tipicamente una Entry piu' ")
          .append("una Write o una Exit.\n")
          .append("Quando l'utente chiede 'perche' questo e' E/X/R/W', spiega la direzione del movimento dati ")
          .append("(chi manda cosa a chi) facendo riferimento, se disponibile, al contesto del progetto sotto.\n")
          .append("Rispondi sempre in italiano, in modo sintetico e concreto.");

        String modelSummary = buildModelSummaryForPrompt();
        if (!modelSummary.isEmpty()) {
            sb.append("\n\n").append(modelSummary);
        } else {
            sb.append("\n\nNota: nessuna analisi e' ancora stata eseguita in questa sessione: se l'utente fa ")
              .append("domande sul progetto, invitalo a eseguire prima un'analisi dal pannello.");
        }
        return sb.toString();
    }

    /** Riassunto testuale di {@link #currentModel}/{@link #lastReport}, riusato da chat e log. */
    private String buildModelSummaryForPrompt() {
        if (currentModel == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CONTESTO ATTUALE - progetto '").append(currentModel.projectName).append("':\n");
        for (UseCase uc : currentModel.useCases) {
            UseCaseReport ucReport = findUseCaseReport(uc.id);
            sb.append("- Use Case [").append(uc.id).append("] ").append(uc.name);
            if (ucReport != null) {
                sb.append(" -> ").append(ucReport.totalCfp).append(" CFP totali");
            }
            sb.append("\n");
            for (FunctionalProcess fp : uc.functionalProcesses) {
                sb.append("    * FP [").append(fp.fpId).append("] ").append(fp.fpName).append("\n");
            }
        }
        if (lastReport != null) {
            sb.append("TOTALE PROGETTO: ").append(lastReport.totalCfp).append(" CFP.\n");
        }
        return sb.toString();
    }

    private UseCaseReport findUseCaseReport(String useCaseId) {
        if (lastReport == null || useCaseId == null) {
            return null;
        }
        for (UseCaseReport r : lastReport.useCases) {
            if (useCaseId.equals(r.id)) {
                return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Task 3: Validazione UML generato vs requisiti originali
    // ------------------------------------------------------------------

    public void validateModelAgainstRequirements(Consumer<String> onResult, Consumer<Throwable> onError) {
        if (currentModel == null || lastRequirementsText == null) {
            onError.accept(new IllegalStateException(
                    "Nessuna analisi disponibile: esegui prima \"Analizza\" su un documento requisiti."));
            return;
        }
        // Copie locali: l'utente potrebbe lanciare una nuova analisi mentre
        // questa richiesta e' ancora in volo sul thread di background.
        final String requirementsSnapshot = lastRequirementsText;
        final String modelSerializationSnapshot = serializeModelForValidation(currentModel);

        executor.submit(() -> {
            try {
                String systemPrompt = "Sei un revisore di qualita' esperto in UML Use Case e nel metodo "
                        + "COSMIC (paper CosMet). Riceverai (1) il testo originale dei requisiti e (2) il "
                        + "modello UML/COSMIC generato automaticamente a partire da esso. Il tuo compito e' "
                        + "controllare se il modello generato copre TUTTI i requisiti originali, segnalando "
                        + "in particolare: attori menzionati nel testo ma assenti nel modello, eccezioni o "
                        + "scenari alternativi presenti nel testo ma non rappresentati, e Use Case impliciti "
                        + "nel testo ma non generati. Rispondi in italiano con un elenco puntato conciso; se "
                        + "non trovi discrepanze, dichiaralo esplicitamente.";
                String userPrompt = "REQUISITI ORIGINALI:\n" + requirementsSnapshot
                        + "\n\nMODELLO GENERATO:\n" + modelSerializationSnapshot
                        + "\n\nEsegui il controllo di copertura richiesto.";

                String requestBody = buildChatCompletionRequestJson(systemPrompt, userPrompt);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(LLM_ENDPOINT_URL))
                        .timeout(LLM_REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("Il server LLM ha risposto con codice HTTP "
                            + response.statusCode() + ": " + truncate(response.body(), 500));
                }
                String reply = extractAssistantContent(response.body());
                SwingUtilities.invokeLater(() -> onResult.accept(reply));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> onError.accept(ex));
            }
        });
    }

    /** Serializzazione testuale completa del modello, per il prompt di validazione. */
    private String serializeModelForValidation(CosmicJsonModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("Progetto: ").append(model.projectName).append("\n\n");

        sb.append("Attori:\n");
        for (Actor a : model.actors) {
            sb.append("- ").append(a.name).append(": ").append(a.description).append("\n");
        }

        sb.append("\nUse Case:\n");
        for (UseCase uc : model.useCases) {
            sb.append("- [").append(uc.id).append("] ").append(uc.name)
              .append(" (attore primario: ").append(uc.primaryActorId).append(")\n");
            sb.append("  Specifica: ").append(uc.specification).append("\n");
            sb.append("  Scenario principale:\n");
            for (String step : uc.mainScenario) {
                sb.append("    - ").append(step).append("\n");
            }
            if (!uc.exceptions.isEmpty()) {
                sb.append("  Eccezioni:\n");
                for (String ex : uc.exceptions) {
                    sb.append("    - ").append(ex).append("\n");
                }
            }
            if (!uc.includesIds.isEmpty()) {
                sb.append("  <<include>>: ").append(uc.includesIds).append("\n");
            }
            if (!uc.extendsList.isEmpty()) {
                sb.append("  <<extend>> verso: ");
                for (var ext : uc.extendsList) {
                    sb.append(ext.targetId).append(" ");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Task 4: Ricalcolo in tempo reale (sync bidirezionale UML -> modello)
    // ------------------------------------------------------------------

    /**
     * Chiamato da {@link CosmicModelChangeListener} quando l'utente
     * disegna/crea a mano un elemento sul diagramma.
     *
     * LIMITE NOTO (dichiarato esplicitamente, non un bug nascosto): il
     * generatore ({@code UseCaseDiagramGenerator}) non salva una mappatura
     * tra l'ID interno di Visual Paradigm e l'id usato nel
     * {@link CosmicJsonModel} (quello prodotto dall'LLM). Senza poter
     * modificare quel file, la correlazione fra un {@code IUseCase}/
     * {@code IAssociation} di VP e la entry corrispondente nel modello e'
     * fatta per NOME (case-insensitive, trim): solida per gli scenari
     * richiesti (aggiunta/rimozione di base), ma un rename manuale di uno
     * Use Case verra' trattato come "elemento nuovo" finche' questa
     * mappatura non verra' irrobustita con ID stabili lato generatore.
     */
    private void handleModelElementAdded(IModelElement element) {
        SwingUtilities.invokeLater(() -> {
            if (currentModel == null) {
                return; // nessuna analisi ancora in memoria: niente con cui sincronizzare
            }
            boolean changed = false;
            if (element instanceof IUseCase) {
                changed = addUseCaseIfMissing((IUseCase) element);
            } else if (element instanceof IAssociation) {
                changed = applyAssociation((IAssociation) element, true);
            }
            if (changed) {
                recomputeAndPublish("Rilevata aggiunta manuale sul diagramma.");
            }
        });
    }

    private void handleModelElementRemoved(IModelElement element) {
        SwingUtilities.invokeLater(() -> {
            if (currentModel == null) {
                return;
            }
            boolean changed = false;
            if (element instanceof IUseCase) {
                changed = removeUseCaseIfPresent((IUseCase) element);
            } else if (element instanceof IAssociation) {
                changed = applyAssociation((IAssociation) element, false);
            }
            if (changed) {
                recomputeAndPublish("Rilevata rimozione manuale sul diagramma.");
            }
        });
    }

    private boolean addUseCaseIfMissing(IUseCase vpUseCase) {
        String vpId = vpUseCase.getId();
        String vpName = safeTrim(vpUseCase.getName());
        if (vpName.isEmpty()) {
            return false;
        }
        for (UseCase existing : currentModel.useCases) {
            if (vpId.equals(existing.id) || vpName.equalsIgnoreCase(safeTrim(existing.name))) {
                return false; // gia' presente (o e' l'eco della nostra stessa generazione)
            }
        }
        UseCase newUseCase = new UseCase();
        newUseCase.id = vpId;
        newUseCase.name = vpUseCase.getName();
        newUseCase.specification = "(Use Case aggiunto manualmente sul diagramma; nessun processo "
                + "funzionale ancora definito)";
        currentModel.useCases.add(newUseCase);
        return true;
    }

    private boolean removeUseCaseIfPresent(IUseCase vpUseCase) {
        String vpId = vpUseCase.getId();
        String vpName = safeTrim(vpUseCase.getName());
        return currentModel.useCases.removeIf(
                uc -> vpId.equals(uc.id) || vpName.equalsIgnoreCase(safeTrim(uc.name)));
    }

    /**
     * Applica (added=true) o rimuove (added=false) il legame Attore-Use Case
     * rappresentato da un'associazione appena disegnata/rimossa. Copre solo
     * il caso "un lato e' un IActor, l'altro e' un IUseCase" (associazione
     * Attore -> Use Case primario), come richiesto dal Task 4.
     */
    private boolean applyAssociation(IAssociation association, boolean added) {
        IActor actorEnd = null;
        IUseCase useCaseEnd = null;
        try {
            if (association.getFrom() instanceof IActor && association.getTo() instanceof IUseCase) {
                actorEnd = (IActor) association.getFrom();
                useCaseEnd = (IUseCase) association.getTo();
            } else if (association.getTo() instanceof IActor && association.getFrom() instanceof IUseCase) {
                actorEnd = (IActor) association.getTo();
                useCaseEnd = (IUseCase) association.getFrom();
            }
        } catch (Exception ex) {
            return false; // elemento gia' scollegato dal modello (rimozione avanzata): nulla da fare
        }
        if (actorEnd == null || useCaseEnd == null) {
            return false; // associazione tra altri tipi di elementi: fuori scope per il Task 4
        }

        UseCase targetUseCase = findUseCaseByNameOrId(useCaseEnd);
        if (targetUseCase == null) {
            return false;
        }

        if (added) {
            Actor actorDto = findOrCreateActor(actorEnd);
            if (targetUseCase.primaryActorId == null || targetUseCase.primaryActorId.isEmpty()) {
                targetUseCase.primaryActorId = actorDto.id;
                return true;
            }
            return false;
        } else {
            String actorName = safeTrim(actorEnd.getName());
            Actor linkedActor = findActorById(targetUseCase.primaryActorId);
            if (linkedActor != null && actorName.equalsIgnoreCase(safeTrim(linkedActor.name))) {
                targetUseCase.primaryActorId = null;
                return true;
            }
            return false;
        }
    }

    private UseCase findUseCaseByNameOrId(IUseCase vpUseCase) {
        String vpId = vpUseCase.getId();
        String vpName = safeTrim(vpUseCase.getName());
        for (UseCase uc : currentModel.useCases) {
            if (vpId.equals(uc.id) || vpName.equalsIgnoreCase(safeTrim(uc.name))) {
                return uc;
            }
        }
        return null;
    }

    private Actor findOrCreateActor(IActor vpActor) {
        String vpName = safeTrim(vpActor.getName());
        for (Actor a : currentModel.actors) {
            if (vpName.equalsIgnoreCase(safeTrim(a.name))) {
                return a;
            }
        }
        Actor newActor = new Actor();
        newActor.id = "actor_" + UUID.randomUUID();
        newActor.name = vpActor.getName();
        newActor.description = "(Attore aggiunto manualmente sul diagramma)";
        currentModel.actors.add(newActor);
        return newActor;
    }

    private Actor findActorById(String actorId) {
        if (actorId == null) {
            return null;
        }
        for (Actor a : currentModel.actors) {
            if (actorId.equals(a.id)) {
                return a;
            }
        }
        return null;
    }

    private void recomputeAndPublish(String reason) {
        CosmicReport report = new CosmicCalculator().compute(currentModel);
        this.lastReport = report;
        CosmicAnalysisListener listener = this.activeUiListener;
        if (listener != null) {
            listener.onLog(reason + " Ricalcolo COSMIC eseguito: " + report.totalCfp + " CFP totali.");
            listener.onAnalysisCompleted(currentModel, report);
        }
    }

    private String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    // ------------------------------------------------------------------
    // Plumbing HTTP/JSON per l'endpoint "chat completions"
    // ------------------------------------------------------------------

    private String callLlm(String systemPrompt, String userText) throws IOException, InterruptedException {
        String requestBody = buildChatCompletionRequestJson(systemPrompt, userText);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LLM_ENDPOINT_URL))
                .timeout(LLM_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() / 100 != 2) {
            throw new IOException("Il server LLM ha risposto con codice HTTP "
                    + response.statusCode() + ": " + truncate(response.body(), 500));
        }
        return response.body();
    }

    private String buildCosmicSystemPrompt() {
        return "Sei un assistente esperto di UML Use Case e del metodo COSMIC (Functional Size "
                + "Measurement). Riceverai una descrizione testuale in linguaggio naturale di uno o "
                + "piu' Use Case (attori, scenario principale, eccezioni). Il tuo compito e' restituire "
                + "ESCLUSIVAMENTE un oggetto JSON valido, senza alcun testo aggiuntivo, senza spiegazioni "
                + "e senza delimitatori Markdown (niente ```), con questa struttura esatta:\n"
                + "{\n"
                + "  \"projectName\": string,\n"
                + "  \"actors\": [ { \"id\": string, \"name\": string, \"description\": string } ],\n"
                + "  \"useCases\": [\n"
                + "    {\n"
                + "      \"id\": string,\n"
                + "      \"name\": string,\n"
                + "      \"primaryActorId\": string,\n"
                + "      \"specification\": string,\n"
                + "      \"mainScenario\": [ string, ... ],\n"
                + "      \"exceptions\": [ string, ... ],\n"
                + "      \"includesIds\": [ string, ... ],\n"
                + "      \"extendsList\": [ { \"targetId\": string, \"extensionPoint\": string } ],\n"
                + "      \"functionalProcesses\": [\n"
                + "        {\n"
                + "          \"fpId\": string,\n"
                + "          \"fpName\": string,\n"
                + "          \"triggeringEvent\": string,\n"
                + "          \"subProcesses\": [\n"
                + "            {\n"
                + "              \"step\": number,\n"
                + "              \"description\": string,\n"
                + "              \"functionalUser\": string,\n"
                + "              \"dataMovementType\": \"E\"|\"X\"|\"R\"|\"W\"|null,\n"
                + "              \"dataGroup\": string|null,\n"
                + "              \"objectOfInterest\": string|null\n"
                + "            }\n"
                + "          ]\n"
                + "        }\n"
                + "      ]\n"
                + "    }\n"
                + "  ]\n"
                + "}\n"
                + "Applica le regole COSMIC standard per identificare i Data Movement (Entry, Exit, "
                + "Read, Write) di ogni sotto-processo. Se un sotto-processo non comporta alcun "
                + "movimento dati, usa null per dataMovementType. Non aggiungere campi diversi da "
                + "quelli elencati e non omettere campi richiesti.";
    }

    private String buildChatCompletionRequestJson(String systemPrompt, String userText) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"model\":\"").append(escapeJson(LLM_MODEL)).append("\",");
        sb.append("\"temperature\":0,");
        sb.append("\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(systemPrompt)).append("\"},");
        sb.append("{\"role\":\"user\",\"content\":\"").append(escapeJson(userText)).append("\"}");
        sb.append("]");
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantContent(String responseBody) {
        Object root = MiniJsonParser.parse(responseBody);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Risposta LLM non e' un oggetto JSON valido.");
        }
        Object choicesObj = ((Map<String, Object>) root).get("choices");
        if (!(choicesObj instanceof List) || ((List<Object>) choicesObj).isEmpty()) {
            throw new IllegalArgumentException("Risposta LLM priva del campo 'choices' atteso: "
                    + truncate(responseBody, 300));
        }
        Object firstChoice = ((List<Object>) choicesObj).get(0);
        if (!(firstChoice instanceof Map)) {
            throw new IllegalArgumentException("Il primo elemento di 'choices' non e' un oggetto JSON.");
        }
        Object messageObj = ((Map<String, Object>) firstChoice).get("message");
        if (!(messageObj instanceof Map)) {
            throw new IllegalArgumentException("Campo 'message' assente in choices[0].");
        }
        Object contentObj = ((Map<String, Object>) messageObj).get("content");
        if (contentObj == null) {
            throw new IllegalArgumentException("Campo 'content' assente in choices[0].message.");
        }
        return String.valueOf(contentObj);
    }

    private String stripMarkdownFences(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        String withoutOpeningFence = (firstNewline >= 0) ? trimmed.substring(firstNewline + 1) : trimmed;
        int closingFenceIndex = withoutOpeningFence.lastIndexOf("```");
        String withoutClosingFence = (closingFenceIndex >= 0)
                ? withoutOpeningFence.substring(0, closingFenceIndex)
                : withoutOpeningFence;
        return withoutClosingFence.trim();
    }

    private String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLength) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }
}
