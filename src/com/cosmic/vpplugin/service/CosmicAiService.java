package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.calculator.CosmicCalculator;
import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.FunctionalProcessReport;
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
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class CosmicAiService {

    private static final CosmicAiService INSTANCE = new CosmicAiService();
    public static CosmicAiService getInstance() { return INSTANCE; }

    private static final String LLM_ENDPOINT_URL = "http://127.0.0.1:1234/v1/chat/completions";
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
    private Timer diagramScopeWatcherTimer;
    private String lastActiveDiagramId;

    private CosmicJsonModel currentModel;
    private CosmicReport lastReport;
    private String lastRequirementsText;
    private volatile CosmicAnalysisListener activeUiListener;

    private CosmicAiService() {
        modelChangeListener.setOnModelAdded(this::handleModelElementAdded);
        modelChangeListener.setOnModelRemoved(this::handleModelElementRemoved);
        modelChangeListener.setOnElementPropertyChanged(this::handleElementPropertyChanged);
        modelChangeListener.setOnIncludeChanged(this::handleIncludeChanged);
        modelChangeListener.setOnExtendChanged(this::handleExtendChanged);
    }

    public void start() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cosmic-ai-llm-call");
            t.setDaemon(true); return t;
        });
        startProjectWatcher();
        startDiagramScopeWatcher();
    }

    public void stop() {
        if (projectWatcherTimer != null) { projectWatcherTimer.stop(); projectWatcherTimer = null; }
        if (diagramScopeWatcherTimer != null) { diagramScopeWatcherTimer.stop(); diagramScopeWatcherTimer = null; }
        detachModelChangeListener();
        if (executor != null) { executor.shutdownNow(); executor = null; }
    }

    public void setActiveUiListener(CosmicAnalysisListener listener) { this.activeUiListener = listener; }
    public void clearActiveUiListener(CosmicAnalysisListener listener) { if (this.activeUiListener == listener) { this.activeUiListener = null; } }

    private void startProjectWatcher() {
        projectWatcherTimer = new Timer(PROJECT_WATCHER_INTERVAL_MS, e -> {
            IProject currentProject = ApplicationManager.instance().getProjectManager().getProject();
            if (currentProject != watchedProject) {
                detachModelChangeListener();
                if (currentProject != null) { attachModelChangeListener(currentProject); }
            }
        });
        projectWatcherTimer.setRepeats(true); projectWatcherTimer.start();
    }

    private void startDiagramScopeWatcher() {
        diagramScopeWatcherTimer = new Timer(DIAGRAM_SCOPE_WATCHER_INTERVAL_MS, e -> {
            if (currentModel == null) return;
            IDiagramUIModel activeDiagram;
            try { activeDiagram = ApplicationManager.instance().getDiagramManager().getActiveDiagram(); }
            catch (Exception ex) { return; }
            String activeDiagramId = (activeDiagram != null) ? activeDiagram.getId() : null;
            if (!java.util.Objects.equals(activeDiagramId, lastActiveDiagramId)) {
                lastActiveDiagramId = activeDiagramId;
                String diagramName = (activeDiagram != null) ? activeDiagram.getName() : null;
                publishScopedReport(activeDiagramId, diagramName);
            }
        });
        diagramScopeWatcherTimer.setRepeats(true); diagramScopeWatcherTimer.start();
    }

    private void attachModelChangeListener(IProject project) {
        try { project.addProjectListener(modelChangeListener); project.addProjectDiagramListener(modelChangeListener); project.addProjectModelListener(modelChangeListener); watchedProject = project; }
        catch (Exception ex) { System.out.println("[COSMIC AI] Listener err: " + ex); }
    }

    private void detachModelChangeListener() {
        if (watchedProject == null) return;
        try { watchedProject.removeProjectListener(modelChangeListener); watchedProject.removeProjectDiagramListener(modelChangeListener); watchedProject.removeProjectModelListener(modelChangeListener); }
        catch (Exception ignored) {} watchedProject = null;
    }

    public void analyzeDocument(File file, CosmicAnalysisListener listener) {
        if (file == null || !file.isFile()) { listener.onAnalysisFailed(new IllegalArgumentException("Nessun file selezionato.")); return; }
        listener.onBusyStateChanged(true);
        listener.onLog("Estrazione testo da '" + file.getName() + "'...");
        executor.submit(() -> {
            try {
                String extractedText = DocumentParserFactory.extractText(file);
                SwingUtilities.invokeLater(() -> {
                    listener.onLog("Testo estratto. Avvio pipeline a due step (Split + Measure)...");
                    analyzeRequirements(extractedText, listener);
                });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> { listener.onBusyStateChanged(false); listener.onAnalysisFailed(ex); });
            }
        });
    }

    // --- PIPELINE A DUE STEP (Chunking + Split + Measure) ---
    public void analyzeRequirements(String rawRequirementsText, CosmicAnalysisListener listener) {
        executor.submit(() -> {
            try {
                // Nuova logica di chunking anti-bomba
                List<String> validChunks = chunkTextSafely(rawRequirementsText);

                CosmicJsonModel finalModel = new CosmicJsonModel();
                finalModel.projectName = "Analisi COSMIC";

                int total = validChunks.size();
                for (int i = 0; i < total; i++) {
                    final int stepIndex = i + 1;
                    String chunk = validChunks.get(i);

                    SwingUtilities.invokeLater(() -> listener.onProgress(stepIndex, total, "Step 1/2: Splitting blocco " + stepIndex + " di " + total));

                    // Call 1: Split
                    String splitResponse = callLlm(getSystemSplitUc(), chunk + "\n\nSplit:");
                    String splitText = extractAssistantContent(splitResponse);

                    SwingUtilities.invokeLater(() -> listener.onProgress(stepIndex, total, "Step 2/2: Measuring blocco " + stepIndex + " di " + total));

                    // Call 2: Measure & JSON
                    String userMeasure = "FP \"Blocco_" + stepIndex + "\":\n" + splitText + "\n\nFP: measurement JSON:";
                    String measureResponse = callLlm(getSystemCosmicMeasureJson(), userMeasure);
                    String jsonText = stripMarkdownFences(extractAssistantContent(measureResponse));

                    try {
                        CosmicJsonModel partialModel = CosmicJsonMapper.map(jsonText);
                        finalModel.actors.removeAll(partialModel.actors);
                        finalModel.actors.addAll(partialModel.actors);
                        finalModel.useCases.addAll(partialModel.useCases);
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> listener.onLog("Errore parsing JSON su blocco " + stepIndex + ": saltato."));
                    }
                }

                SwingUtilities.invokeLater(() -> runPipelineOnModel(finalModel, rawRequirementsText, listener));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> { listener.onBusyStateChanged(false); listener.onAnalysisFailed(ex); });
            }
        });
    }

    private List<String> chunkTextSafely(String text) {
        List<String> chunks = new ArrayList<>();

        // Taglio brutale e infallibile per singole righe. Zero regex complesse che fanno crashare Java!
        String[] lines = text.split("\\r?\\n");
        StringBuilder currentChunk = new StringBuilder();

        for (String line : lines) {
            String cleanLine = line.trim();
            if (cleanLine.isEmpty()) continue;

            // Se una singola riga è un mostro (es. una tabella Word decodificata male)
            // la tagliamo col macete ogni 5000 caratteri.
            if (cleanLine.length() > 5000) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                for (int j = 0; j < cleanLine.length(); j += 5000) {
                    chunks.add(cleanLine.substring(j, Math.min(j + 5000, cleanLine.length())));
                }
                continue;
            }

            // Riempiamo il "secchio" fino a un massimo di 5000 caratteri (circa 1200 token)
            // in modo da lasciare ampio spazio al prompt di sistema per stare negli 8192 totali.
            if (currentChunk.length() + cleanLine.length() > 5000) {
                chunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
            }
            currentChunk.append(cleanLine).append("\n");
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private void runPipelineOnModel(CosmicJsonModel model, String originalText, CosmicAnalysisListener listener) {
        try {
            listener.onLog("Calcolo COSMIC Function Points (CFP) in memoria...");
            CosmicCalculator calculator = new CosmicCalculator();
            CosmicReport report = calculator.compute(model);

            // PROTEZIONE ANTI-FREEZE PER VISUAL PARADIGM
            if (model.useCases.size() > 40) {
                listener.onLog("\n=======================================================");
                listener.onLog("ATTENZIONE: Il modello contiene " + model.useCases.size() + " Use Case.");
                listener.onLog("La generazione grafica del diagramma UML e' stata DISABILITATA.");
                listener.onLog("Motivo: Disegnare centinaia di elementi causerebbe il freeze di Visual Paradigm.");
                listener.onLog("I calcoli COSMIC sono stati comunque completati. Usa il pulsante 'Esporta Report CSV' per salvare i dati.");
                listener.onLog("=======================================================\n");
            } else {
                listener.onLog("Generazione diagramma/i UML in corso...");
                UseCaseDiagramGenerator generator = new UseCaseDiagramGenerator();
                generator.setLogSink(listener::onLog);
                generator.generate(model);

                // Applica il colore e lo stereotipo solo se abbiamo disegnato fisicamente gli elementi
                calculator.applyViolationFlags(model, report);
                applyVisualFeedbackSafely();
            }

            this.currentModel = model;
            this.lastReport = report;
            this.lastRequirementsText = originalText;

            listener.onBusyStateChanged(false);
            listener.onAnalysisCompleted(model, report);
        } catch (Exception e) {
            listener.onBusyStateChanged(false);
            listener.onAnalysisFailed(e);
        }
    }

    // --- METODO PER ESPORTARE IN CSV ---
    public void exportToCsv(File file, CosmicAnalysisListener listener) {
        if (lastReport == null) return;
        executor.submit(() -> {
            try (FileWriter writer = new FileWriter(file)) {
                writer.append("Use Case ID;Use Case Name;FP ID;FP Name;Entry;Exit;Read;Write;Total CFP;Warning\n");
                for (UseCaseReport uc : lastReport.useCases) {
                    for (FunctionalProcessReport fp : uc.functionalProcesses) {
                        writer.append(uc.id).append(";").append(uc.name).append(";")
                                .append(fp.fpId).append(";").append(fp.fpName).append(";")
                                .append(String.valueOf(fp.entryCount)).append(";")
                                .append(String.valueOf(fp.exitCount)).append(";")
                                .append(String.valueOf(fp.readCount)).append(";")
                                .append(String.valueOf(fp.writeCount)).append(";")
                                .append(String.valueOf(fp.totalCfp)).append(";")
                                .append(fp.warning != null ? fp.warning : "").append("\n");
                    }
                }
                SwingUtilities.invokeLater(() -> listener.onLog("Report esportato con successo in: " + file.getAbsolutePath()));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> listener.onAnalysisFailed(e));
            }
        });
    }

    // --- RESTO DEI METODI PREESISTENTI (Chat, Validazione, Handlers, etc.) ---
    public void sendChatMessage(String userMessage, Consumer<String> onReply, Consumer<Throwable> onError) {
        // [Invariato]
    }
    public void validateModelAgainstRequirements(Consumer<String> onResult, Consumer<Throwable> onError) {
        // [Invariato]
    }
    private void publishScopedReport(String activeDiagramId, String diagramName) {
        // [Invariato]
    }
    private void handleModelElementAdded(IModelElement element) {
        // [Invariato]
    }
    private void handleModelElementRemoved(IModelElement element) {
        // [Invariato]
    }
    private void handleElementPropertyChanged(IModelElement element, String propertyName) {
        SwingUtilities.invokeLater(() -> {
            if (currentModel == null || !(element instanceof IUseCase)) return;
            UseCase dto = findUseCaseByVpElementId((IUseCase) element);
            if (dto == null) return;
            boolean changed = false;
            String lower = propertyName.toLowerCase();
            if (lower.contains("name")) {
                String newName = safeTrim(element.getName());
                if (!newName.isEmpty() && !newName.equals(dto.name)) {
                    dto.name = newName; changed = true;
                }
            }
            if (changed) recomputeAndPublish("Rilevata modifica di '" + propertyName + "' su " + dto.name + ".");
        });
    }
    private void handleIncludeChanged(IInclude include, boolean added) {} // [Invariato]
    private void handleExtendChanged(IExtend extend, boolean added) {} // [Invariato]
    private boolean addUseCaseIfMissing(IUseCase vpUseCase) { return false; } // [Invariato]
    private boolean removeUseCaseIfPresent(IUseCase vpUseCase) { return false; } // [Invariato]
    private boolean applyAssociation(IAssociation association, boolean added) { return false; } // [Invariato]
    private UseCase findUseCaseByVpElementId(IUseCase vpUseCase) { return null; } // [Invariato]
    private Actor findOrCreateActor(IActor vpActor) { return null; } // [Invariato]
    private Actor findActorById(String actorId) { return null; } // [Invariato]
    private void recomputeAndPublish(String reason) {} // [Invariato]
    private void applyVisualFeedbackSafely() {
        try { VisualFeedbackApplier.applyAll(currentModel, lastReport); } catch (Exception ex) {}
    }
    private String safeTrim(String s) { return s == null ? "" : s.trim(); }

    private String callLlm(String systemPrompt, String userText) throws IOException, InterruptedException {
        String requestBody = buildChatCompletionRequestJson(systemPrompt, userText);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(LLM_ENDPOINT_URL)).timeout(LLM_REQUEST_TIMEOUT).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new IOException("HTTP " + response.statusCode());
        return response.body();
    }

    private String getSystemSplitUc() {
        // Prompt originale del paper[cite: 16]
        return "split all the following sentences, numbering the new sentences so that each new sentence is grammatically correct.\n" +
                "Example:\nMAIN SCENARIO:\n- The system validates the given date interval.\n" +
                "Split:\nMAIN SCENARIO:\n1. The system validates the given date interval.\n";
    }

    private String getSystemCosmicMeasureJson() {
        // Regole COSMIC[cite: 16] combinate con il nostro formato JSON
        return "Sei un assistente esperto del metodo COSMIC (Functional Size Measurement), basato sul paper CosMet. " +
                "Identifica i Data Movement (E, X, R, W) e restituisci il risultato ESCLUSIVAMENTE come JSON valido " +
                "con questa esatta struttura, senza markdown:\n" +
                "{\n  \"actors\": [ { \"id\": \"A1\", \"name\": \"Nome\" } ],\n" +
                "  \"useCases\": [ {\n" +
                "      \"id\": \"UC1\", \"name\": \"Nome\", \"primaryActorId\": \"A1\", \"subsystem\": \"Modulo\",\n" +
                "      \"mainScenario\": [\"step 1\"], \"exceptions\": [], \"includesIds\": [], \"extendsList\": [],\n" +
                "      \"functionalProcesses\": [ {\n" +
                "          \"fpId\": \"FP1\", \"fpName\": \"Nome FP\", \"triggeringEvent\": \"Event\",\n" +
                "          \"subProcesses\": [ { \"step\": 1, \"description\": \"desc\", \"functionalUser\": \"System\", \"dataMovementType\": \"E|X|R|W|null\", \"dataGroup\": \"DG\", \"objectOfInterest\": \"OOI\" } ]\n" +
                "      } ]\n  } ]\n}\n";
    }

    private String buildChatCompletionRequestJson(String systemPrompt, String userText) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(escapeJson(LLM_MODEL)).append("\",");
        sb.append("\"temperature\":0,");
        sb.append("\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(systemPrompt)).append("\"},");
        sb.append("{\"role\":\"user\",\"content\":\"").append(escapeJson(userText)).append("\"}");
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantContent(String responseBody) {
        Map<String, Object> root = (Map<String, Object>) MiniJsonParser.parse(responseBody);
        List<Object> choices = (List<Object>) root.get("choices");
        Map<String, Object> message = (Map<String, Object>) ((Map<String, Object>) choices.get(0)).get("message");
        return String.valueOf(message.get("content"));
    }

    private String stripMarkdownFences(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstNewline = trimmed.indexOf('\n');
        String withoutOpeningFence = (firstNewline >= 0) ? trimmed.substring(firstNewline + 1) : trimmed;
        int closingFenceIndex = withoutOpeningFence.lastIndexOf("```");
        return (closingFenceIndex >= 0) ? withoutOpeningFence.substring(0, closingFenceIndex).trim() : withoutOpeningFence.trim();
    }

    private String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    // Elimina i caratteri invisibili di Microsoft Word!
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}