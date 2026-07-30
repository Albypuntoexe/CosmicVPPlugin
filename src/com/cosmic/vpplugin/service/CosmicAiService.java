package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.calculator.CosmicCalculator;
import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.document.DocumentParserFactory;
import com.cosmic.vpplugin.generator.UseCaseDiagramGenerator;
import com.cosmic.vpplugin.generator.VisualFeedbackApplier;
import com.cosmic.vpplugin.json.MiniJsonParser;
import com.cosmic.vpplugin.listener.CosmicModelChangeListener;
import com.cosmic.vpplugin.model.CosmicJsonMapper;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IActor;
import com.vp.plugin.model.IAssociation;
import com.vp.plugin.model.IExtend;
import com.vp.plugin.model.IInclude;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IUseCase;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * AGGIORNAMENTI v2.0 (Ultimate):
 *  - Epic 1: {@link #analyzeDocument} accetta ora anche PDF/DOCX (oltre a
 *    txt/json), delegando l'estrazione testo a
 *    {@link DocumentParserFactory}. {@link #analyzeRequirements} resta
 *    invariata come pipeline di basso livello (testo -> LLM -> modello).
 *  - Epic 2: il system prompt ({@link #buildCosmicSystemPrompt}) e' stato
 *    rafforzato per istruire esplicitamente l'LLM sulla scomposizione
 *    Use Case -> N Functional Process (mai un rapporto 1:1 forzato), come
 *    richiesto esplicitamente dal professore, e per il nuovo campo opzionale
 *    "subsystem" (raggruppamento multi-diagramma, Epic 3).
 *  - Epic 3: un secondo watcher a polling ({@link #startDiagramScopeWatcher})
 *    osserva il diagramma attivo in VP e, quando cambia, calcola un report
 *    COSMIC "scoped" (solo gli Use Case disegnati su QUEL diagramma, via
 *    {@code UseCase.diagramId}) e lo notifica alla UI tramite il nuovo
 *    metodo default {@code CosmicAnalysisListener.onScopeChanged}, senza
 *    rompere le implementazioni esistenti dell'interfaccia.
 *  - Epic 4: la sync bidirezionale ora si basa su {@code UseCase.vpElementId}
 *    (id interno VP) invece che sul nome, gestisce ANCHE {@code IInclude}/
 *    {@code IExtend} aggiunti o rimossi a mano, e reagisce a rename/
 *    modifica-descrizione tramite {@code CosmicModelChangeListener}
 *    (property change).
 *  - Epic 5: dopo ogni ricalcolo, {@link VisualFeedbackApplier} applica
 *    l'etichetta CFP e la colorazione di violazione sulle shape reali.
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
    private static final int DIAGRAM_SCOPE_WATCHER_INTERVAL_MS = 800;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private ExecutorService executor;

    private IProject watchedProject;

    private final CosmicModelChangeListener modelChangeListener = new CosmicModelChangeListener();

    private Timer projectWatcherTimer;

    /** Epic 3: polling del diagramma attivo (VP non espone un evento nativo di "activeDiagramChanged" nell'Open API). */
    private Timer diagramScopeWatcherTimer;
    private String lastActiveDiagramId;

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

    /** Ultimo report COSMIC "Totale Progetto" calcolato su {@link #currentModel}. */
    private CosmicReport lastReport;

    /** Testo dei requisiti originali usato per generare {@link #currentModel} (serve alla validazione, Task 3). */
    private String lastRequirementsText;

    /** Listener della UI attualmente visibile, se presente: riceve gli aggiornamenti del ricalcolo in tempo reale. */
    private volatile CosmicAnalysisListener activeUiListener;

    private CosmicAiService() {
        // Collega il listener di modello al Service: da qui in avanti ogni
        // IUseCase/IAssociation/IInclude/IExtend aggiunto, rimosso o
        // rinominato a mano dall'utente passa da qui (Task 4 / Epic 4).
        modelChangeListener.setOnModelAdded(this::handleModelElementAdded);
        modelChangeListener.setOnModelRemoved(this::handleModelElementRemoved);
        modelChangeListener.setOnElementPropertyChanged(this::handleElementPropertyChanged);
        modelChangeListener.setOnIncludeChanged(this::handleIncludeChanged);
        modelChangeListener.setOnExtendChanged(this::handleExtendChanged);
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
        startDiagramScopeWatcher();
    }

    public void stop() {
        if (projectWatcherTimer != null) {
            projectWatcherTimer.stop();
            projectWatcherTimer = null;
        }
        if (diagramScopeWatcherTimer != null) {
            diagramScopeWatcherTimer.stop();
            diagramScopeWatcherTimer = null;
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

    /**
     * Epic 3 - Context-Awareness: l'Open API di VP non espone un listener
     * nativo per "cambio diagramma attivo" a livello di ApplicationManager
     * (esistono eventi di editing sul singolo diagramma, ma non un evento
     * di progetto per il focus/tab attivo). La soluzione adottata, in linea
     * con lo stesso pattern gia' in uso per {@link #startProjectWatcher()}
     * (che affronta un limite analogo per il cambio di progetto), e' un
     * polling leggero su {@code ViewManager.getActiveDiagram()}.
     */
    private void startDiagramScopeWatcher() {
        diagramScopeWatcherTimer = new Timer(DIAGRAM_SCOPE_WATCHER_INTERVAL_MS, e -> {
            if (currentModel == null) {
                return;
            }
            IDiagramUIModel activeDiagram;
            try {
                activeDiagram = ApplicationManager.instance().getDiagramManager().getActiveDiagram();
            } catch (Exception ex) {
                return; // nessun diagramma attivo/focus non su un diagramma: nulla da fare
            }
            String activeDiagramId = (activeDiagram != null) ? activeDiagram.getId() : null;
            if (!java.util.Objects.equals(activeDiagramId, lastActiveDiagramId)) {
                lastActiveDiagramId = activeDiagramId;
                String diagramName = (activeDiagram != null) ? activeDiagram.getName() : null;
                publishScopedReport(activeDiagramId, diagramName);
            }
        });
        diagramScopeWatcherTimer.setRepeats(true);
        diagramScopeWatcherTimer.start();
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
    // Epic 1: Document Processing Avanzato (PDF/DOCX -> testo -> LLM)
    // ------------------------------------------------------------------

    /**
     * Punto di ingresso preferito dalla UI a partire da v2.0: accetta
     * qualunque file supportato da {@link DocumentParserFactory}
     * (.txt, .json, .docx, .pdf), estrae il testo e delega a
     * {@link #analyzeRequirements}. {@code analyzeRequirements} resta
     * pubblica e invariata per compatibilita' (es. test, MockLlmResponse).
     */
    public void analyzeDocument(File file, CosmicAnalysisListener listener) {
        if (file == null || !file.isFile()) {
            listener.onAnalysisFailed(new IllegalArgumentException("Nessun file selezionato."));
            return;
        }
        listener.onBusyStateChanged(true);
        listener.onLog("Estrazione testo da '" + file.getName() + "'...");

        executor.submit(() -> {
            try {
                String extractedText = DocumentParserFactory.extractText(file);
                SwingUtilities.invokeLater(() -> {
                    listener.onLog("Testo estratto (" + extractedText.length() + " caratteri). Invio all'LLM...");
                    analyzeRequirements(extractedText, listener);
                });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    listener.onBusyStateChanged(false);
                    listener.onAnalysisFailed(ex);
                });
            }
        });
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
        listener.onLog("Invio richiesta al server LLM (" + LLM_ENDPOINT_URL + ", modello " + LLM_MODEL
                + ", temperature=0.0)...");

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
            listener.onLog("Parsing JSON e generazione diagramma/i in corso...");
            CosmicJsonModel model = CosmicJsonMapper.map(jsonText);

            UseCaseDiagramGenerator generator = new UseCaseDiagramGenerator();
            generator.setLogSink(listener::onLog);
            generator.generate(model);

            listener.onLog("Diagramma/i generato/i: " + model.useCases.size() + " Use Case, "
                    + model.actors.size() + " Attori.");

            listener.onLog("Calcolo COSMIC Function Points (CFP) in corso...");
            CosmicCalculator calculator = new CosmicCalculator();
            CosmicReport report = calculator.compute(model);
            calculator.applyViolationFlags(model, report); // Epic 5: prepara i flag di violazione sul modello
            listener.onLog(report.toText());
            listener.onLog("Misurazione completata: " + report.totalCfp + " CFP totali.");

            // Aggiorna la memoria condivisa (Task 2/3/4): da qui in avanti
            // chat, validazione e ricalcolo manuale lavorano su QUESTO modello.
            this.currentModel = model;
            this.lastReport = report;
            this.lastRequirementsText = originalRequirementsText;

            applyVisualFeedbackSafely();

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
          .append("- Un singolo Use Case NON corrisponde necessariamente a un solo Functional Process: puo' ")
          .append("generarne piu' di uno, ed e' normale che il modello ne mostri la scomposizione.\n")
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
    // Epic 3: report "scoped" sul diagramma attivo + Totale Progetto
    // ------------------------------------------------------------------

    /**
     * Calcola il report limitato agli Use Case disegnati sul diagramma
     * indicato ({@code UseCase.diagramId == activeDiagramId}) e lo
     * notifica alla UI insieme al Totale Progetto (mai perso di vista).
     * Se {@code activeDiagramId} e' null (nessun diagramma a fuoco, o
     * l'utente ha selezionato un elemento non-diagramma), la UI riceve lo
     * scope "vuoto" con il solo Totale Progetto, cosi' da non mostrare dati
     * fuorvianti.
     */
    private void publishScopedReport(String activeDiagramId, String diagramName) {
        CosmicAnalysisListener listener = this.activeUiListener;
        if (listener == null || currentModel == null) {
            return;
        }
        Set<String> idsInScope = new HashSet<>();
        for (UseCase uc : currentModel.useCases) {
            if (activeDiagramId != null && activeDiagramId.equals(uc.diagramId)) {
                idsInScope.add(uc.id);
            }
        }
        CosmicReport scopedReport = new CosmicCalculator().computeScoped(currentModel, idsInScope);
        CosmicReport projectReport = (lastReport != null) ? lastReport : scopedReport;
        listener.onScopeChanged(diagramName, scopedReport, projectReport);
    }

    // ------------------------------------------------------------------
    // Task 4 / Epic 4: Ricalcolo in tempo reale (sync bidirezionale UML -> modello)
    // ------------------------------------------------------------------

    /**
     * Chiamato da {@link CosmicModelChangeListener} quando l'utente
     * disegna/crea a mano un elemento sul diagramma.
     *
     * AGGIORNAMENTO Epic 4: la corrispondenza tra {@code IUseCase}/
     * {@code IAssociation} di VP e la entry corrispondente nel
     * {@link CosmicJsonModel} ora usa PRIMA {@code IModelElement.getId()}
     * (salvato in {@code UseCase.vpElementId} da
     * {@code UseCaseDiagramGenerator} al momento della generazione), e solo
     * come fallback il nome (per gli elementi creati a mano dall'utente,
     * che non hanno mai avuto un vpElementId prima di questo momento).
     * Questo risolve il limite dichiarato in precedenza: un rename manuale
     * non "rompe" piu' la sincronizzazione, perche' l'id non cambia.
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

    /**
     * Epic 4: rename o modifica descrizione rilevati da
     * {@code CosmicModelChangeListener} tramite PropertyChangeListener.
     * Aggiorna il DTO corrispondente (per id VP) e ricalcola/repubblica,
     * cosi' che anche il pannello "Struttura COSMIC" (JTree) rifletta
     * subito il nuovo nome senza richiedere una nuova analisi LLM.
     */
    private void handleElementPropertyChanged(IModelElement element, String propertyName) {
        SwingUtilities.invokeLater(() -> {
            if (currentModel == null || !(element instanceof IUseCase)) {
                return;
            }
            UseCase dto = findUseCaseByVpElementId((IUseCase) element);
            if (dto == null) {
                return;
            }
            boolean changed = false;
            String lower = propertyName.toLowerCase();
            if (lower.contains("name")) {
                String newName = safeTrim(element.getName());
                if (!newName.isEmpty() && !newName.equals(dto.name)) {
                    dto.name = newName;
                    changed = true;
                }
            } else if (lower.contains("desc") || lower.contains("doc")) {
                // La description arricchita e' generata da noi (scenario/FP): un
                // rename manuale della description sovrascrive solo la "specification"
                // libera che l'utente sta effettivamente editando a mano.
                dto.specification = safeTrim(element.getDescription());
                changed = true;
            }
            if (changed) {
                recomputeAndPublish("Rilevata modifica di '" + propertyName + "' su " + dto.name + ".");
            }
        });
    }

    /** Epic 4: aggiunta/rimozione manuale di una freccia <<include>>. */
    private void handleIncludeChanged(IInclude include, boolean added) {
        SwingUtilities.invokeLater(() -> {
            if (currentModel == null) {
                return;
            }
            IModelElement fromEl;
            IModelElement toEl;
            try {
                fromEl = (IModelElement) include.getFrom();
                toEl = (IModelElement) include.getTo();
            } catch (Exception ex) {
                return; // relazione gia' scollegata (rimozione avanzata)
            }
            if (!(fromEl instanceof IUseCase) || !(toEl instanceof IUseCase)) {
                return;
            }
            UseCase baseDto = findUseCaseByVpElementId((IUseCase) fromEl);
            UseCase includedDto = findUseCaseByVpElementId((IUseCase) toEl);
            if (baseDto == null || includedDto == null) {
                return;
            }
            boolean changed;
            if (added) {
                changed = !baseDto.includesIds.contains(includedDto.id);
                if (changed) {
                    baseDto.includesIds.add(includedDto.id);
                }
            } else {
                changed = baseDto.includesIds.remove(includedDto.id);
            }
            if (changed) {
                recomputeAndPublish((added ? "Rilevata nuova relazione <<include>>: " : "Rilevata rimozione <<include>>: ")
                        + baseDto.name + " -> " + includedDto.name);
            }
        });
    }

    /** Epic 4: aggiunta/rimozione manuale di una freccia <<extend>>. */
    private void handleExtendChanged(IExtend extend, boolean added) {
        SwingUtilities.invokeLater(() -> {
            if (currentModel == null) {
                return;
            }
            IModelElement fromEl; // extension
            IModelElement toEl;   // base
            try {
                fromEl = (IModelElement) extend.getFrom();
                toEl = (IModelElement) extend.getTo();
            } catch (Exception ex) {
                return;
            }
            if (!(fromEl instanceof IUseCase) || !(toEl instanceof IUseCase)) {
                return;
            }
            UseCase extensionDto = findUseCaseByVpElementId((IUseCase) fromEl);
            UseCase baseDto = findUseCaseByVpElementId((IUseCase) toEl);
            if (extensionDto == null || baseDto == null) {
                return;
            }
            boolean changed;
            if (added) {
                boolean alreadyPresent = extensionDto.extendsList.stream()
                        .anyMatch(e -> baseDto.id.equals(e.targetId));
                changed = !alreadyPresent;
                if (changed) {
                    CosmicJsonModel.ExtendRelation rel = new CosmicJsonModel.ExtendRelation();
                    rel.targetId = baseDto.id;
                    try {
                        rel.extensionPoint = (extend.getExtensionPoint() != null)
                                ? extend.getExtensionPoint().getName() : "";
                    } catch (Exception ex) {
                        rel.extensionPoint = "";
                    }
                    extensionDto.extendsList.add(rel);
                }
            } else {
                changed = extensionDto.extendsList.removeIf(e -> baseDto.id.equals(e.targetId));
            }
            if (changed) {
                recomputeAndPublish((added ? "Rilevata nuova relazione <<extend>>: " : "Rilevata rimozione <<extend>>: ")
                        + extensionDto.name + " -> " + baseDto.name);
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
            if (vpId.equals(existing.vpElementId) || vpName.equalsIgnoreCase(safeTrim(existing.name))) {
                return false; // gia' presente (o e' l'eco della nostra stessa generazione)
            }
        }
        UseCase newUseCase = new UseCase();
        newUseCase.id = "uc_manual_" + UUID.randomUUID();
        newUseCase.vpElementId = vpId; // Epic 4: da subito tracciato per id, non solo per nome
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
                uc -> vpId.equals(uc.vpElementId) || vpName.equalsIgnoreCase(safeTrim(uc.name)));
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

        UseCase targetUseCase = findUseCaseByVpElementId(useCaseEnd);
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

    /** Epic 4: risoluzione robusta per id VP, con fallback per nome per gli elementi non ancora tracciati. */
    private UseCase findUseCaseByVpElementId(IUseCase vpUseCase) {
        String vpId = vpUseCase.getId();
        String vpName = safeTrim(vpUseCase.getName());
        for (UseCase uc : currentModel.useCases) {
            if (vpId.equals(uc.vpElementId)) {
                return uc;
            }
        }
        for (UseCase uc : currentModel.useCases) {
            if (vpName.equalsIgnoreCase(safeTrim(uc.name))) {
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
        CosmicCalculator calculator = new CosmicCalculator();
        CosmicReport report = calculator.compute(currentModel);
        calculator.applyViolationFlags(currentModel, report); // Epic 5
        this.lastReport = report;

        applyVisualFeedbackSafely();

        CosmicAnalysisListener listener = this.activeUiListener;
        if (listener != null) {
            listener.onLog(reason + " Ricalcolo COSMIC eseguito: " + report.totalCfp + " CFP totali.");
            listener.onAnalysisCompleted(currentModel, report);
        }
        // Il diagramma attivo potrebbe non essere cambiato ma il suo
        // contenuto si': ripubblica anche lo scope corrente (Epic 3).
        if (lastActiveDiagramId != null) {
            publishScopedReport(lastActiveDiagramId, null);
        }
    }

    /**
     * Epic 5: incapsula la chiamata a {@link VisualFeedbackApplier} in un
     * try/catch dedicato. Motivo: questo metodo viene invocato anche da
     * percorsi (es. test futuri, o un ricalcolo scatenato mentre l'utente
     * sta chiudendo il progetto) in cui l'Open API di VP potrebbe non
     * essere in uno stato consistente; una eccezione qui NON deve mai
     * impedire alla UI di ricevere comunque i CFP aggiornati.
     */
    private void applyVisualFeedbackSafely() {
        try {
            VisualFeedbackApplier.applyAll(currentModel, lastReport);
        } catch (Exception ex) {
            System.out.println("[COSMIC AI][visual] Feedback visivo non applicato: " + ex);
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

    /**
     * Epic 2 - Scomposizione e Raggruppamento (requisito del professore).
     *
     * Rispetto alla versione precedente, il prompt ora:
     *  1) VIETA esplicitamente all'LLM di assumere un rapporto 1:1 tra Use
     *     Case e Functional Process, istruendolo a scomporre un singolo Use
     *     Case complesso in PIU' FP quando lo scenario descrive fasi
     *     logicamente distinte (es. validazione + salvataggio), rifacendosi
     *     esplicitamente all'esempio "Insert a New Customer" del paper
     *     CosMet (Sez. 2.1, Tab. 1) gia' usato in {@code MockLlmResponse}.
     *  2) Richiede il "Sentence Splitting" preliminare (paper CosMet, Sez.
     *     3.2.1): ogni sotto-processo deve rappresentare un'azione atomica
     *     con un solo movimento dati, non una frase composita.
     *  3) Aggiunge il campo opzionale "subsystem" per il raggruppamento
     *     multi-diagramma (Epic 3).
     */
    private String buildCosmicSystemPrompt() {
        return "Sei un assistente esperto di UML Use Case e del metodo COSMIC (Functional Size "
                + "Measurement), che applica l'approccio CosMet (De Vito et al., IEEE TSE). Riceverai una "
                + "descrizione testuale in linguaggio naturale di uno o piu' Use Case (attori, scenario "
                + "principale, eccezioni). Il tuo compito e' restituire ESCLUSIVAMENTE un oggetto JSON "
                + "valido, senza alcun testo aggiuntivo, senza spiegazioni e senza delimitatori Markdown "
                + "(niente ```), con questa struttura esatta:\n"
                + "{\n"
                + "  \"projectName\": string,\n"
                + "  \"actors\": [ { \"id\": string, \"name\": string, \"description\": string } ],\n"
                + "  \"useCases\": [\n"
                + "    {\n"
                + "      \"id\": string,\n"
                + "      \"name\": string,\n"
                + "      \"primaryActorId\": string,\n"
                + "      \"specification\": string,\n"
                + "      \"subsystem\": string|null,\n"
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
                + "REGOLE DI SCOMPOSIZIONE (fondamentali, non violarle):\n"
                + "1) Un Use Case NON corrisponde necessariamente a un solo Functional Process. Se lo "
                + "scenario descrive fasi logicamente distinte e separabili (es. 'valida i dati' e poi "
                + "'salva e conferma'), genera PIU' FP distinti per quello stesso Use Case (esempio di "
                + "riferimento: 'Insert a New Customer' del paper CosMet si scompone in un FP di "
                + "validazione e un FP di salvataggio). Al contrario, se piu' Use Case descrivono in "
                + "realta' un'unica sequenza di data movement inseparabile, possono confluire in un solo FP.\n"
                + "2) Prima di classificare i Data Movement, scomponi (Sentence Splitting) ogni frase che "
                + "contiene piu' azioni/entita' in step atomici distinti, uno per ogni singolo movimento "
                + "dati: NON accorpare due movimenti diversi (es. 'Entry' + 'Read') nello stesso sub-process.\n"
                + "3) Applica le regole COSMIC standard per identificare i Data Movement (Entry, Exit, "
                + "Read, Write) di ogni sotto-processo. Se un sotto-processo non comporta alcun movimento "
                + "dati (es. un semplice click), usa null per dataMovementType.\n"
                + "4) Un messaggio di conferma/errore mostrato all'utente e' un Exit e va contato UNA sola "
                + "volta per Functional Process, anche se lo scenario ne descrive piu' varianti.\n"
                + "5) Se dal testo emerge un raggruppamento naturale per modulo/area funzionale (es. "
                + "'Prenotazioni', 'Fatturazione', 'Amministrazione'), assegna quel nome al campo "
                + "'subsystem' di ciascun Use Case: verra' usato per generare diagrammi separati per "
                + "modulo invece di un unico diagramma con decine di Use Case. Se non c'e' un "
                + "raggruppamento evidente, lascia 'subsystem' a null.\n"
                + "Non aggiungere campi diversi da quelli elencati e non omettere campi richiesti.";
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
