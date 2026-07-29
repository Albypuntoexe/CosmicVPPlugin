package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.calculator.CosmicCalculator;
import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.generator.UseCaseDiagramGenerator;
import com.cosmic.vpplugin.json.MiniJsonParser;
import com.cosmic.vpplugin.listener.CosmicModelChangeListener;
import com.cosmic.vpplugin.model.CosmicJsonMapper;
import com.cosmic.vpplugin.model.CosmicJsonModel;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.model.IProject;

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
 * {@link com.cosmic.vpplugin.CosmicPlugin#unloaded}. Il dialog di analisi
 * (view "usa e getta", apribile e richiudibile a piacere) si registra come
 * listener SOLO mentre e' visibile: il Service invece vive sempre, cosi' il
 * futuro ricalcolo automatico o l'assistente di chat potranno agganciarsi
 * senza dover ri-orchestrare le chiamate HTTP o i listener di progetto.
 */
public final class CosmicAiService {

    private static final CosmicAiService INSTANCE = new CosmicAiService();

    public static CosmicAiService getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Configurazione LLM (unica fonte di verita': prima era duplicata
    // dentro il pannello Swing, il che non aveva senso per una classe UI).
    // ------------------------------------------------------------------

    private static final String LLM_ENDPOINT_URL = "http://192.168.4.62:2345/v1/chat/completions";
    private static final String LLM_MODEL = "openai/gpt-oss-20b";
    private static final Duration LLM_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Intervallo del "project watcher" (si veda {@link #startProjectWatcher()}).
     * Un javax.swing.Timer gira SEMPRE sull'EDT: nessun rischio di
     * concorrenza con l'Open API di Visual Paradigm, che non e' garantita
     * thread-safe fuori dall'EDT.
     */
    private static final int PROJECT_WATCHER_INTERVAL_MS = 1500;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * Un solo worker thread, dedicato, daemon: le richieste all'LLM vengono
     * serializzate (un modello locale da 20B non trae comunque beneficio da
     * chiamate concorrenti sull'hardware universitario) e non impediscono
     * la chiusura della JVM host se il plugin viene scaricato senza arresto
     * esplicito.
     */
    private ExecutorService executor;

    /** Riferimento al progetto attualmente agganciato dal listener di modello (null se nessuno). */
    private IProject watchedProject;

    private final CosmicModelChangeListener modelChangeListener = new CosmicModelChangeListener();

    private Timer projectWatcherTimer;

    private CosmicAiService() {
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

    /**
     * FIX rispetto alla versione precedente: il vecchio codice agganciava i
     * listener di modello UNA sola volta, dentro {@code CosmicPlugin.loaded()}.
     * Se il plugin viene caricato all'avvio di Visual Paradigm PRIMA che
     * l'utente apra o crei un progetto (il caso comune), {@code getProject()}
     * restituiva null e i listener non venivano mai agganciati: aprire un
     * progetto in seguito non ri-innescava alcun aggancio (i callback
     * projectOpened/projectNewed di IProjectListener servono a nulla se non
     * si e' GIA' in ascolto di qualcosa).
     *
     * La soluzione adottata qui e' un polling leggero, sempre sull'EDT, che
     * confronta il riferimento al progetto corrente: se e' cambiato (nessun
     * progetto -> progetto aperto, oppure cambio di progetto), i listener
     * vengono staccati dal vecchio progetto (se presente) e agganciati al
     * nuovo. Questo e' il punto di aggancio concreto per la Predisposizione
     * Futura al ricalcolo in tempo reale: da qui in avanti
     * {@link #modelChangeListener} riceve davvero tutti gli eventi, in ogni
     * scenario di apertura/cambio progetto.
     */
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

    /**
     * Avvia l'intera pipeline in modo asincrono. Puo' essere chiamato SOLO
     * dall'EDT (e' li' che vive normalmente un click di bottone); il
     * {@link CosmicAnalysisListener} viene sempre richiamato sull'EDT.
     */
    public void analyzeRequirements(String rawRequirementsText, CosmicAnalysisListener listener) {
        if (rawRequirementsText == null || rawRequirementsText.isBlank()) {
            listener.onAnalysisFailed(new IllegalArgumentException("Nessun testo di requisiti fornito."));
            return;
        }
        listener.onBusyStateChanged(true);
        listener.onLog("Invio richiesta al server LLM (" + LLM_ENDPOINT_URL + ", modello " + LLM_MODEL + ")...");

        executor.submit(() -> {
            try {
                String responseBody = callLlm(rawRequirementsText);
                String assistantContent = extractAssistantContent(responseBody);
                String cleanedJson = stripMarkdownFences(assistantContent);
                SwingUtilities.invokeLater(() -> {
                    listener.onLog("Risposta LLM ricevuta (" + cleanedJson.length() + " caratteri).");
                    runPipelineOnJson(cleanedJson, listener);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    listener.onBusyStateChanged(false);
                    listener.onAnalysisFailed(ex);
                });
            }
        });
    }

    /**
     * Percorso "demo": salta la chiamata di rete e applica direttamente il
     * JSON di esempio del paper CosMet ({@link com.cosmic.vpplugin.mock.MockLlmResponse}).
     * Utile per verificare diagramma e calcolo COSMIC anche senza
     * connettivita' verso l'LLM universitario.
     */
    public void analyzeWithMockData(CosmicAnalysisListener listener) {
        listener.onBusyStateChanged(true);
        listener.onLog("Modalita' demo: uso il JSON di esempio (nessuna chiamata di rete).");
        // La generazione del diagramma tocca l'Open API di VP: resta sull'EDT.
        runPipelineOnJson(com.cosmic.vpplugin.mock.MockLlmResponse.JSON, listener);
    }

    /** Deve essere invocato SEMPRE sull'EDT: tocca l'Open API di Visual Paradigm. */
    private void runPipelineOnJson(String jsonText, CosmicAnalysisListener listener) {
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

            listener.onBusyStateChanged(false);
            listener.onAnalysisCompleted(model, report);
        } catch (Exception e) {
            listener.onBusyStateChanged(false);
            listener.onAnalysisFailed(e);
        }
    }

    // ------------------------------------------------------------------
    // Assistente di chat (predisposizione futura): stesso client HTTP,
    // stesso executor, nessun prompt COSMIC/JSON: risposta libera in testo.
    // ------------------------------------------------------------------

    public void sendChatMessage(String userMessage, Consumer<String> onReply, Consumer<Throwable> onError) {
        executor.submit(() -> {
            try {
                String systemPrompt = "Sei l'assistente COSMIC AI integrato in Visual Paradigm. Rispondi in "
                        + "modo sintetico a domande su Use Case, UML e sul metodo COSMIC (Entry/Exit/Read/Write, "
                        + "Function Point, Data Group, Object of Interest).";
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

    // ------------------------------------------------------------------
    // Plumbing HTTP/JSON per l'endpoint "chat completions"
    // ------------------------------------------------------------------

    private String callLlm(String requirementsText) throws IOException, InterruptedException {
        String requestBody = buildChatCompletionRequestJson(buildCosmicSystemPrompt(), requirementsText);

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
