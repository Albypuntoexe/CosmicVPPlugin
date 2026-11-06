package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.calculator.CosmicCalculator;
import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.FunctionalProcessReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.document.DocumentParserFactory;
import com.cosmic.vpplugin.document.UseCaseChunk;
import com.cosmic.vpplugin.document.UseCaseChunker;
import com.cosmic.vpplugin.generator.UseCaseDescription;
import com.cosmic.vpplugin.generator.UseCaseDiagramGenerator;
import com.cosmic.vpplugin.generator.VisualFeedbackApplier;
import com.cosmic.vpplugin.json.MiniJsonParser;
import com.cosmic.vpplugin.listener.CosmicModelChangeListener;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;
import com.cosmic.vpplugin.model.CosmicModelSerializer;
import com.cosmic.vpplugin.persistence.ProjectModelStore;
import com.cosmic.vpplugin.settings.CosmicSettings;

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
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Orchestratore del plugin COSMIC AI (v4.1 - Persistenza Chunk Fix).
 */
public final class CosmicAiService {

    private static final CosmicAiService INSTANCE = new CosmicAiService();
    public static CosmicAiService getInstance() { return INSTANCE; }

    private static final int LLM_MAX_ATTEMPTS = 3;
    private static final long LLM_RETRY_BASE_MS = 5_000L;
    private static final int MAX_CHUNK_CHARS = 6000;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final int PROJECT_WATCHER_INTERVAL_MS = 1500;
    private static final int DIAGRAM_SCOPE_WATCHER_INTERVAL_MS = 800;
    private static final int CHAT_HISTORY_MESSAGES = 16;
    private static final int PERSIST_DEBOUNCE_MS = 1500;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private ExecutorService pipelineExecutor;
    private ExecutorService auxExecutor;
    private volatile Future<?> runningTask;
    private final AtomicBoolean analysisRunning = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private final AtomicInteger suppressSync = new AtomicInteger(0);
    private boolean recomputing;
    private int manualUseCaseCounter;

    private IProject modelProject;
    private Timer persistTimer;
    private boolean persistDirty;
    private String lastPersistError;

    private IProject watchedProject;
    private final CosmicModelChangeListener modelChangeListener = new CosmicModelChangeListener();
    private Timer projectWatcherTimer;
    private Timer diagramScopeWatcherTimer;
    private String lastActiveDiagramId;

    private volatile CosmicJsonModel currentModel;
    private volatile CosmicReport lastReport;
    private UseCaseDiagramGenerator activeGenerator;
    private Set<String> knownUseCaseIds = new HashSet<>();

    private volatile CosmicAnalysisListener activeUiListener;
    private volatile CosmicAnalysisListener runListener;

    private final List<String[]> chatHistory = new ArrayList<>();

    private CosmicAiService() {
        modelChangeListener.setOnModelAdded(this::handleModelElementAdded);
        modelChangeListener.setOnModelRemoved(this::handleModelElementRemoved);
        modelChangeListener.setOnElementPropertyChanged(this::handleElementPropertyChanged);
        modelChangeListener.setOnIncludeChanged(this::handleIncludeChanged);
        modelChangeListener.setOnExtendChanged(this::handleExtendChanged);
        modelChangeListener.setOnProjectAfterOpened(this::handleProjectAfterOpened);
        modelChangeListener.setOnProjectPreSave(this::handleProjectPreSave);
    }

    public void start() {
        ensureExecutors();
        persistTimer = new Timer(PERSIST_DEBOUNCE_MS, e -> flushPersistence());
        persistTimer.setRepeats(false);
        startProjectWatcher();
        startDiagramScopeWatcher();
    }

    public void stop() {
        cancelAnalysis();
        runOnEdt(this::flushPersistence);
        if (persistTimer != null) { persistTimer.stop(); persistTimer = null; }
        if (projectWatcherTimer != null) { projectWatcherTimer.stop(); projectWatcherTimer = null; }
        if (diagramScopeWatcherTimer != null) { diagramScopeWatcherTimer.stop(); diagramScopeWatcherTimer = null; }
        detachModelChangeListener();
        synchronized (this) {
            if (pipelineExecutor != null) { pipelineExecutor.shutdownNow(); pipelineExecutor = null; }
            if (auxExecutor != null) { auxExecutor.shutdownNow(); auxExecutor = null; }
        }
    }

    private synchronized void ensureExecutors() {
        if (pipelineExecutor == null) {
            pipelineExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cosmic-ai-pipeline");
                t.setDaemon(true);
                return t;
            });
        }
        if (auxExecutor == null) {
            auxExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cosmic-ai-aux");
                t.setDaemon(true);
                return t;
            });
        }
    }

    public void setActiveUiListener(CosmicAnalysisListener listener) { this.activeUiListener = listener; }
    public void clearActiveUiListener(CosmicAnalysisListener listener) { if (this.activeUiListener == listener) { this.activeUiListener = null; } }

    private CosmicAnalysisListener uiListener() {
        CosmicAnalysisListener l = activeUiListener;
        return l != null ? l : runListener;
    }

    public boolean isAnalysisRunning() { return analysisRunning.get(); }

    public void cancelAnalysis() {
        if (!analysisRunning.get()) return;
        cancelRequested.set(true);
        Future<?> f = runningTask;
        if (f != null) f.cancel(true);
    }

    private void startProjectWatcher() {
        projectWatcherTimer = new Timer(PROJECT_WATCHER_INTERVAL_MS, e -> {
            IProject currentProject = ApplicationManager.instance().getProjectManager().getProject();
            if (currentProject != watchedProject) {
                detachModelChangeListener();
                if (currentProject != null) {
                    attachModelChangeListener(currentProject);
                    loadPersistedModel(currentProject);
                }
            }
        });
        projectWatcherTimer.setRepeats(true);
        projectWatcherTimer.start();
    }

    private void startDiagramScopeWatcher() {
        diagramScopeWatcherTimer = new Timer(DIAGRAM_SCOPE_WATCHER_INTERVAL_MS, e -> {
            if (currentModel == null) return;
            publishCurrentScope();
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
            System.out.println("[COSMIC AI] Listener err: " + ex);
        }
    }

    private void detachModelChangeListener() {
        if (watchedProject == null) return;
        try {
            watchedProject.removeProjectListener(modelChangeListener);
            watchedProject.removeProjectDiagramListener(modelChangeListener);
            watchedProject.removeProjectModelListener(modelChangeListener);
        } catch (Exception ignored) { }
        watchedProject = null;
    }

    public void analyzeDocument(File file, CosmicAnalysisListener listener) {
        if (file == null || !file.isFile()) {
            listener.onAnalysisFailed(new IllegalArgumentException("Nessun file selezionato."));
            return;
        }
        if (!beginRun(listener, false)) return;
        listener.onLog("Estrazione testo da '" + file.getName() + "'...");
        final String projectName = stripExtension(file.getName());
        runningTask = pipelineExecutor.submit(() -> {
            try {
                String text = DocumentParserFactory.extractText(file);
                postLog(listener, "Testo estratto: " + text.length() + " caratteri.");
                runPipeline(text, projectName, listener);
            } catch (Throwable ex) {
                failRun(listener, ex);
            }
        });
    }

    public void analyzeRequirements(String rawRequirementsText, CosmicAnalysisListener listener) {
        analyzeRequirements(rawRequirementsText, "Analisi COSMIC", listener);
    }

    public void analyzeRequirements(String rawRequirementsText, String projectName, CosmicAnalysisListener listener) {
        if (!beginRun(listener, false)) return;
        runningTask = pipelineExecutor.submit(() -> {
            try {
                runPipeline(rawRequirementsText, projectName, listener);
            } catch (Throwable ex) {
                failRun(listener, ex);
            }
        });
    }

    private boolean beginRun(CosmicAnalysisListener listener, boolean incremental) {
        if (!analysisRunning.compareAndSet(false, true)) {
            listener.onAnalysisFailed(new IllegalStateException("Un'analisi e' gia' in corso."));
            return false;
        }
        ensureExecutors();
        cancelRequested.set(false);
        runListener = listener;
        listener.onRunStarted(incremental);
        listener.onBusyStateChanged(true);
        return true;
    }

    private void failRun(CosmicAnalysisListener listener, Throwable ex) {
        Thread.interrupted();
        analysisRunning.set(false);
        SwingUtilities.invokeLater(() -> {
            listener.onBusyStateChanged(false);
            if (ex instanceof AnalysisCancelled || ex instanceof InterruptedException) {
                listener.onLog("Analisi interrotta dall'utente.");
            } else {
                listener.onAnalysisFailed(ex);
            }
        });
    }

    private static final class AnalysisCancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
        AnalysisCancelled() { super("annullata", null, false, false); }
    }

    private void checkCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) throw new AnalysisCancelled();
    }

    private void runPipeline(String text, String projectName, CosmicAnalysisListener ui) throws Exception {
        final long t0 = System.currentTimeMillis();

        UseCaseChunker.ChunkingResult chunking = UseCaseChunker.chunk(text);
        final List<UseCaseChunk> chunks = chunking.chunks;
        if (chunks.isEmpty()) throw new IOException("Nessun use case individuato nel documento.");

        final int total = chunks.size();
        postLog(ui, "Individuati " + total + " use case (strategia: " + chunking.strategy + ").");
        for (String w : chunking.warnings) postLog(ui, "ATTENZIONE: " + w);

        onEdtAndWait(() -> beginSession(projectName, chunks, ui));

        final CosmicCalculator calculator = new CosmicCalculator();
        int processed = 0;
        int failures = 0;
        int consecutiveFailures = 0;
        boolean aborted = false;
        String abortReason = null;
        List<String> failedIds = new ArrayList<>();

        try {
            for (int i = 0; i < total; i++) {
                checkCancelled();
                final UseCaseChunk chunk = chunks.get(i);
                final int doneBefore = i;
                final String eta = estimate(t0, i, total);
                final String label = "UC " + (i + 1) + "/" + total + " [" + chunk.id + "] " + chunk.name;
                postProgress(ui, doneBefore, total, label + " - Step 1/2: split" + eta);

                Measured measured = new Measured();
                try {
                    measureChunk(chunk, label, doneBefore, total, eta, ui, measured);
                    if (measured.unusable) {
                        failures++;
                        consecutiveFailures++;
                        failedIds.add(chunk.id);
                    } else {
                        consecutiveFailures = 0;
                    }
                } catch (AnalysisCancelled | InterruptedException c) {
                    throw c;
                } catch (Exception e) {
                    measured.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    failures++;
                    consecutiveFailures++;
                    failedIds.add(chunk.id);
                    postLog(ui, "ERRORE su [" + chunk.id + "] " + chunk.name + ": " + measured.error
                            + " (use case registrato senza misura, si prosegue).");
                }

                final int doneAfter = i + 1;
                onEdtAndWait(() -> commitUseCase(chunk, measured, doneAfter, total, ui));
                processed = doneAfter;

                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    aborted = true;
                    abortReason = MAX_CONSECUTIVE_FAILURES + " use case consecutivi non misurabili: l'LLM non risponde o "
                            + "e' mal configurato.";
                    break;
                }
            }
        } catch (AnalysisCancelled | InterruptedException c) {
            Thread.interrupted();
            aborted = true;
            abortReason = "interrotta dall'utente";
        }

        final boolean fAborted = aborted;
        final String fReason = abortReason;
        final int fProcessed = processed;
        final int fFailures = failures;
        final List<String> fFailedIds = failedIds;
        analysisRunning.set(false);
        SwingUtilities.invokeLater(() -> finishSession(ui, fProcessed, total, fFailures, fFailedIds, fAborted, fReason, t0));
    }

    private static String estimate(long t0, int done, int total) {
        if (done == 0) return "";
        long avg = (System.currentTimeMillis() - t0) / done;
        return "  (~" + formatDuration(avg * (total - done)) + " rimanenti)";
    }

    private static String formatDuration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) return (s / 3600) + "h " + ((s % 3600) / 60) + "min";
        if (s >= 60) return (s / 60) + "min " + (s % 60) + "s";
        return s + "s";
    }

    private static final class Measured {
        final List<FunctionalProcess> functionalProcesses = new ArrayList<>();
        String error;
        boolean unusable;
    }

    private void measureChunk(UseCaseChunk chunk, String label, int done, int total, String eta,
                              CosmicAnalysisListener ui, Measured out) throws IOException, InterruptedException {
        List<String> parts = chunk.toParts(MAX_CHUNK_CHARS);
        if (parts.size() > 1) {
            postLog(ui, "[" + chunk.id + "] scenario lungo: diviso in " + parts.size() + " parti (stesso use case).");
        }
        measureScenario(chunk.id, chunk.name, parts, label, done, total, eta, ui, out);
    }

    private void measureScenario(String ucId, String ucName, List<String> parts, String label, int done, int total, String eta,
                                 CosmicAnalysisListener ui, Measured out) throws IOException, InterruptedException {
        List<FunctionalProcess> all = new ArrayList<>();
        int sentences = 0;
        int failed = 0;
        for (int p = 0; p < parts.size(); p++) {
            checkCancelled();
            String suffix = parts.size() > 1 ? " (parte " + (p + 1) + "/" + parts.size() + ")" : "";
            String part = trimNewlines(parts.get(p));

            postProgress(ui, done, total, label + " - Step 1/2: split" + suffix + eta);
            String splitRaw = callLlmWithRetry(CosmicPrompts.SYSTEM_SPLIT_UC, CosmicPrompts.userSplit(part));
            String splitText = cleanSplitOutput(splitRaw, part);
            if (splitText == part) {
                postLog(ui, "[" + ucId + "] output dello Split vuoto: uso lo scenario originale per lo Step 2.");
            }

            final String partSuffix = suffix;
            CosmicMicroLoop loop = new CosmicMicroLoop(this::callLlmWithRetry, new CosmicMicroLoop.Observer() {
                @Override public void onSentence(int index, int count, String sentence) {
                    postProgress(ui, done, total, label + " - Step 2/2: frase " + index + "/" + count + partSuffix + eta);
                }
                @Override public void onLog(String message) { postLog(ui, "[" + ucId + "] " + message); }
                @Override public void checkCancelled() { CosmicAiService.this.checkCancelled(); }
            });
            CosmicMicroLoop.Result r = loop.measure(ucName, splitText, part);
            sentences += r.sentences;
            failed += r.failed.size();
            all.add(r.functionalProcess);
        }
        if (all.size() > 1) {
            all = Collections.singletonList(mergeFunctionalProcesses(all));
        }
        out.functionalProcesses.addAll(all);
        if (failed > 0) {
            postLog(ui, "[" + ucId + "] " + failed + "/" + sentences + " frasi non misurate (evidenziate nella tab Struttura: correggile a mano).");
        }
        out.unusable = sentences > 0 && failed == sentences;
    }

    private static String trimNewlines(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '\n' || s.charAt(a) == '\r')) a++;
        while (b > a && (s.charAt(b - 1) == '\n' || s.charAt(b - 1) == '\r')) b--;
        return s.substring(a, b);
    }

    private static String cleanSplitOutput(String raw, String original) {
        String t = stripMarkdownFences(raw).trim();
        t = t.replaceFirst("(?i)^split\\s*:\\s*", "").trim();
        return t.isEmpty() ? original : t;
    }

    private static FunctionalProcess mergeFunctionalProcesses(List<FunctionalProcess> fps) {
        FunctionalProcess merged = new FunctionalProcess();
        merged.triggeringEvent = fps.get(0).triggeringEvent;
        for (FunctionalProcess fp : fps) merged.subProcesses.addAll(fp.subProcesses);
        renumber(merged);
        return merged;
    }

    private static void renumber(FunctionalProcess fp) {
        int n = 1;
        for (SubProcess sp : fp.subProcesses) sp.step = n++;
    }

    private void beginSession(String projectName, List<UseCaseChunk> chunks, CosmicAnalysisListener ui) {
        CosmicJsonModel model = new CosmicJsonModel();
        model.projectName = projectName;
        // PERSISTENCE FIX: Salviamo i chunk originali direttamente nel modello per la serializzazione
        model.originalChunks = new ArrayList<>(chunks);

        currentModel = model;
        modelProject = ApplicationManager.instance().getProjectManager().getProject();

        knownUseCaseIds = new HashSet<>();
        Set<String> subsystems = new HashSet<>();
        for (UseCaseChunk c : chunks) {
            knownUseCaseIds.add(c.id);
            subsystems.add(c.subsystem == null ? "" : c.subsystem);
        }
        lastReport = new CosmicCalculator().compute(model);
        manualUseCaseCounter = 0;
        synchronized (chatHistory) { chatHistory.clear(); }

        suppressSync.incrementAndGet();
        try {
            activeGenerator = new UseCaseDiagramGenerator();
            activeGenerator.setLogSink(ui::onLog);
            activeGenerator.begin(projectName, subsystems.size() > 1);
        } finally {
            suppressSync.decrementAndGet();
        }
        ui.onLog("Pipeline a due step (Split + Measure) su " + chunks.size() + " use case, uno alla volta. "
                + "Il diagramma si costruisce man mano.");
    }

    private void commitUseCase(UseCaseChunk chunk, Measured measured, int processed, int total, CosmicAnalysisListener ui) {
        CosmicJsonModel model = currentModel;
        if (model == null) return;
        suppressSync.incrementAndGet();
        try {
            UseCase uc = buildUseCase(chunk, measured, model);
            uc.specFingerprint = UseCaseDescription.fingerprint(uc.name,
                    UseCaseDescription.specificationOf(UseCaseDescription.build(uc), uc.id));
            model.useCases.add(uc);

            CosmicCalculator calculator = new CosmicCalculator();
            lastReport = calculator.compute(model);
            calculator.applyViolationFlags(model, lastReport);
            UseCaseReport ucReport = findUseCaseReport(lastReport, uc.id);

            try {
                activeGenerator.appendUseCase(model, uc);
                VisualFeedbackApplier.applyToUseCase(uc, ucReport);
            } catch (Exception drawEx) {
                ui.onLog("Errore nel disegno di [" + uc.id + "]: " + drawEx + " (i dati COSMIC sono comunque nel modello).");
            }

            publishCurrentScope();
            markDirty();
            ui.onUseCaseProcessed(uc, ucReport, lastReport, processed, total);
            ui.onProgress(processed, total, "UC " + processed + "/" + total + " completato: [" + uc.id + "] " + uc.name
                    + " -> " + (ucReport == null ? 0 : ucReport.totalCfp) + " CFP");
        } finally {
            suppressSync.decrementAndGet();
        }
    }

    private UseCase buildUseCase(UseCaseChunk chunk, Measured measured, CosmicJsonModel model) {
        UseCase uc = new UseCase();
        uc.id = chunk.id;
        uc.name = chunk.name;
        uc.subsystem = chunk.subsystem;
        uc.specification = chunk.reference == null ? "" : "Riferimento nel documento: " + chunk.reference;
        uc.mainScenario.addAll(chunk.mainSentences);
        uc.exceptions.addAll(chunk.otherSentences);

        for (String inc : chunk.includedIds) {
            if (knownUseCaseIds.contains(inc) && !inc.equals(uc.id) && !uc.includesIds.contains(inc)) uc.includesIds.add(inc);
        }

        if (measured.functionalProcesses.isEmpty()) {
            FunctionalProcess empty = new FunctionalProcess();
            empty.triggeringEvent = measured.error == null ? "" : "(misura non riuscita: " + measured.error + ")";
            measured.functionalProcesses.add(empty);
        }
        int n = 1;
        for (FunctionalProcess fp : measured.functionalProcesses) {
            fp.fpId = "FP" + n;
            fp.fpName = measured.functionalProcesses.size() == 1 ? uc.name : uc.name + " - FP" + n;
            uc.functionalProcesses.add(fp);
            n++;
        }

        List<String> actorNames = new ArrayList<>(chunk.primaryActors);
        if (actorNames.isEmpty()) {
            String fu = firstEntryUser(uc.functionalProcesses.get(0));
            if (fu != null) actorNames.add(fu);
        }
        for (String name : actorNames) {
            Actor actor = findOrCreateActorByName(model, name);
            if (uc.primaryActorId == null) uc.primaryActorId = actor.id;
            else if (!uc.coActorIds.contains(actor.id)) uc.coActorIds.add(actor.id);
        }
        return uc;
    }

    static String firstEntryUser(FunctionalProcess fp) {
        String fallback = null;
        for (SubProcess sp : fp.subProcesses) {
            if (sp.functionalUser == null) continue;
            String u = sp.functionalUser.trim();
            if (u.isEmpty() || u.equalsIgnoreCase("system") || u.equalsIgnoreCase("the system")) continue;
            if ("E".equals(sp.dataMovementType)) return u;
            if (fallback == null) fallback = u;
        }
        return fallback;
    }

    private static Actor findOrCreateActorByName(CosmicJsonModel model, String name) {
        String key = name.trim().toLowerCase();
        for (Actor a : model.actors) {
            if (a.name != null && a.name.trim().toLowerCase().equals(key)) return a;
        }
        Set<String> used = new HashSet<>();
        for (Actor a : model.actors) used.add(a.id);
        int n = model.actors.size() + 1;
        while (used.contains("A" + n)) n++;
        Actor actor = new Actor();
        actor.id = "A" + n;
        actor.name = name.trim();
        actor.description = "";
        model.actors.add(actor);
        return actor;
    }

    private static UseCaseReport findUseCaseReport(CosmicReport report, String ucId) {
        if (report == null) return null;
        for (UseCaseReport r : report.useCases) if (r.id != null && r.id.equals(ucId)) return r;
        return null;
    }

    private void finishSession(CosmicAnalysisListener ui, int processed, int total, int failures,
                               List<String> failedIds, boolean aborted, String abortReason, long t0) {
        CosmicJsonModel model = currentModel;
        try {
            suppressSync.incrementAndGet();
            try {
                if (activeGenerator != null) activeGenerator.finish();
            } finally {
                suppressSync.decrementAndGet();
            }
            if (model != null) {
                CosmicCalculator calculator = new CosmicCalculator();
                lastReport = calculator.compute(model);
                calculator.applyViolationFlags(model, lastReport);
            }
            int violations = 0;
            if (model != null) for (UseCase uc : model.useCases) if (uc.violation != null) violations++;
            StringBuilder sum = new StringBuilder();
            sum.append(aborted ? "Analisi INTERROTTA (" + abortReason + "): " : "Analisi completata: ")
                    .append(processed).append("/").append(total).append(" use case");
            if (lastReport != null) sum.append(", ").append(lastReport.totalCfp).append(" CFP totali");
            sum.append(", ").append(violations).append(" con violazioni COSMIC, ")
                    .append(failures).append(" non misurati, in ").append(formatDuration(System.currentTimeMillis() - t0)).append(".");
            ui.onLog(sum.toString());
            if (!failedIds.isEmpty()) ui.onLog("Use case non misurati: " + String.join(", ", failedIds));
            int unmeasured = model == null ? 0 : countUnmeasuredSentences(model);
            if (unmeasured > 0) {
                ui.onLog(unmeasured + " frasi non misurate dall'LLM (nessun data movement assegnato): sono evidenziate nella tab "
                        + "'Struttura COSMIC', dove puoi assegnare il data movement a mano.");
            }
            markDirty();
            flushPersistence();
            ui.onBusyStateChanged(false);
            if (model != null && lastReport != null) ui.onAnalysisCompleted(model, lastReport);
            publishCurrentScope();
        } catch (Exception e) {
            ui.onBusyStateChanged(false);
            ui.onAnalysisFailed(e);
        }
    }

    public void exportToCsv(File file, CosmicAnalysisListener listener) {
        ensureExecutors();
        runOnEdt(() -> {
            if (lastReport == null || currentModel == null || currentModel.useCases.isEmpty()) {
                listener.onAnalysisFailed(new IllegalStateException("Nessuna analisi da esportare."));
                return;
            }
            final String summary = buildSummaryCsv(currentModel, lastReport);
            final String detail = buildDetailCsv(currentModel);
            final File detailFile = new File(file.getParentFile(), file.getName().replaceFirst("(?i)\\.csv$", "") + "_dettaglio.csv");
            auxExecutor.submit(() -> {
                try {
                    Files.write(file.toPath(), ("\uFEFF" + summary).getBytes(StandardCharsets.UTF_8));
                    Files.write(detailFile.toPath(), ("\uFEFF" + detail).getBytes(StandardCharsets.UTF_8));
                    SwingUtilities.invokeLater(() -> listener.onLog("Report esportato in: " + file.getAbsolutePath()
                            + "  (dettaglio per frase: " + detailFile.getName() + ")"));
                } catch (IOException e) {
                    SwingUtilities.invokeLater(() -> listener.onAnalysisFailed(e));
                }
            });
        });
    }

    private static String buildSummaryCsv(CosmicJsonModel model, CosmicReport report) {
        Map<String, UseCase> byId = new LinkedHashMap<>();
        for (UseCase uc : model.useCases) byId.put(uc.id, uc);
        StringBuilder sb = new StringBuilder();
        sb.append("Use Case ID;Use Case Name;FP ID;FP Name;Entry;Exit;Read;Write;Total CFP;Warning;Triggering Event;Functional User;Subsystem\n");
        for (UseCaseReport uc : report.useCases) {
            UseCase dto = byId.get(uc.id);
            String subsystem = dto == null || dto.subsystem == null ? "" : dto.subsystem;
            if (uc.functionalProcesses.isEmpty()) {
                row(sb, uc.id, uc.name, "", "", "0", "0", "0", "0", "0", "Nessun Functional Process misurato", "", "", subsystem);
                continue;
            }
            for (int i = 0; i < uc.functionalProcesses.size(); i++) {
                FunctionalProcessReport fp = uc.functionalProcesses.get(i);
                FunctionalProcess fpDto = (dto != null && i < dto.functionalProcesses.size()) ? dto.functionalProcesses.get(i) : null;
                String fu = fpDto == null ? "" : String.valueOf(firstEntryUser(fpDto) == null ? "" : firstEntryUser(fpDto));
                row(sb, uc.id, uc.name, fp.fpId, fp.fpName,
                        String.valueOf(fp.entryCount), String.valueOf(fp.exitCount), String.valueOf(fp.readCount),
                        String.valueOf(fp.writeCount), String.valueOf(fp.totalCfp),
                        fp.warning == null ? "" : fp.warning,
                        fp.triggeringEvent == null ? "" : fp.triggeringEvent, fu, subsystem);
            }
        }
        return sb.toString();
    }

    private static String buildDetailCsv(CosmicJsonModel model) {
        CosmicCalculator calculator = new CosmicCalculator();
        StringBuilder sb = new StringBuilder();
        sb.append("Use Case ID;Use Case Name;FP ID;Step;Sub-process;FU;DG;OOI;DM;CFP\n");
        for (UseCase uc : model.useCases) {
            for (FunctionalProcess fp : uc.functionalProcesses) {
                int[] cfp = calculator.cfpPerSubProcess(fp);
                for (int i = 0; i < fp.subProcesses.size(); i++) {
                    SubProcess sp = fp.subProcesses.get(i);
                    row(sb, uc.id, uc.name, fp.fpId, String.valueOf(sp.step), sp.description,
                            sp.functionalUser == null ? "" : sp.functionalUser,
                            sp.dataGroup == null ? "" : sp.dataGroup,
                            sp.objectOfInterest == null ? "" : sp.objectOfInterest,
                            movementWord(sp.dataMovementType), String.valueOf(cfp[i]));
                }
            }
        }
        return sb.toString();
    }

    private static String movementWord(String dm) {
        if (dm == null || dm.trim().isEmpty()) return "No data movement";
        switch (dm.trim().toUpperCase()) {
            case "E": return "Entry";
            case "X": return "Exit";
            case "R": return "Read";
            case "W": return "Write";
            default: return dm;
        }
    }

    private static void row(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(csv(cells[i]));
        }
        sb.append('\n');
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public void validateModelAgainstRequirements(Consumer<String> onResult, Consumer<Throwable> onError) {
        runOnEdt(() -> {
            try {
                CosmicJsonModel model = currentModel;
                if (model == null) {
                    onError.accept(new IllegalStateException("Nessuna analisi eseguita: carica un documento e premi Analizza."));
                    return;
                }

                // PERSISTENCE FIX: Usiamo i chunk salvati nel modello!
                List<UseCaseChunk> chunks = model.originalChunks;

                if (chunks == null || chunks.isEmpty()) {
                    onError.accept(new IllegalStateException("I requisiti originali non sono in memoria (il progetto e' stato creato interamente a mano o caricato da un file antecedente alla versione 4.1). La validazione richiede un documento sorgente."));
                    return;
                }

                onResult.accept(buildValidationReport(model, chunks));
            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }

    private String buildValidationReport(CosmicJsonModel model, List<UseCaseChunk> chunks) {
        Map<String, UseCase> modelById = new LinkedHashMap<>();
        for (UseCase uc : model.useCases) modelById.put(uc.id, uc);
        Set<String> chunkIds = new LinkedHashSet<>();
        for (UseCaseChunk c : chunks) chunkIds.add(c.id);

        List<String> missing = new ArrayList<>(), extra = new ArrayList<>(), renamed = new ArrayList<>(),
                omissions = new ArrayList<>(), actors = new ArrayList<>(), includes = new ArrayList<>(),
                violations = new ArrayList<>(), unmeasured = new ArrayList<>();

        for (UseCaseChunk c : chunks) {
            UseCase uc = modelById.get(c.id);
            if (uc == null) { missing.add("[" + c.id + "] " + c.name); continue; }
            if (!safe(uc.name).equals(c.name)) renamed.add("[" + c.id + "] requisiti \"" + c.name + "\" -> modello \"" + uc.name + "\"");

            int measured = 0;
            for (FunctionalProcess fp : uc.functionalProcesses) measured += fp.subProcesses.size();
            if (measured == 0) unmeasured.add("[" + c.id + "] " + c.name);
            else if (measured < c.sentenceCount) {
                omissions.add("[" + c.id + "] i requisiti hanno " + c.sentenceCount + " frasi, il modello solo " + measured + " sotto-processi");
            }

            Set<String> present = new HashSet<>();
            for (Actor a : model.actors) {
                if (a.id != null && (a.id.equals(uc.primaryActorId) || uc.coActorIds.contains(a.id))) present.add(safe(a.name).toLowerCase());
            }
            for (String expected : c.primaryActors) {
                if (!present.contains(expected.toLowerCase())) actors.add("[" + c.id + "] attore '" + expected + "' dichiarato nei requisiti ma non associato nel modello");
            }
            for (String inc : c.includedIds) {
                if (chunkIds.contains(inc) && !uc.includesIds.contains(inc)) includes.add("[" + c.id + "] include " + inc + " (nei requisiti) assente nel modello");
            }
            if (uc.violation != null) violations.add("[" + c.id + "] " + uc.violation);
        }
        for (UseCase uc : model.useCases) {
            if (!chunkIds.contains(uc.id)) {
                extra.add("[" + uc.id + "] " + uc.name + (uc.id.startsWith("UC-USER-") ? " (aggiunto a mano su VP)" : ""));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== VALIDAZIONE MODELLO vs REQUISITI ===\n");
        sb.append("Use case nei requisiti: ").append(chunks.size()).append(" | nel modello: ").append(model.useCases.size());
        if (lastReport != null) sb.append(" | CFP totali: ").append(lastReport.totalCfp);
        sb.append("\n");
        int issues = section(sb, "Use case dei requisiti MANCANTI nel modello", missing)
                + section(sb, "Use case nel modello NON presenti nei requisiti", extra)
                + section(sb, "Use case RINOMINATI rispetto ai requisiti", renamed)
                + section(sb, "Use case senza alcuna misura COSMIC", unmeasured)
                + section(sb, "Possibili frasi non coperte", omissions)
                + section(sb, "Attori dei requisiti non associati", actors)
                + section(sb, "Relazioni <<include>> dei requisiti mancanti", includes)
                + section(sb, "Violazioni della regola minima COSMIC (>= 2 CFP, Entry + Write/Exit)", violations);
        sb.append(issues == 0 ? "\nEsito: il modello e' coerente con i requisiti.\n"
                : "\nEsito: " + issues + " segnalazioni da verificare.\n");
        return sb.toString();
    }

    private static int section(StringBuilder sb, String title, List<String> items) {
        if (items.isEmpty()) return 0;
        sb.append("\n").append(title).append(" (").append(items.size()).append("):\n");
        int shown = Math.min(items.size(), 25);
        for (int i = 0; i < shown; i++) sb.append("  - ").append(items.get(i)).append("\n");
        if (items.size() > shown) sb.append("  ... e altri ").append(items.size() - shown).append("\n");
        return items.size();
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    public void sendChatMessage(String userMessage, Consumer<String> onReply, Consumer<Throwable> onError) {
        ensureExecutors();
        runOnEdt(() -> {
            final String context = buildModelContext(userMessage);
            final List<String[]> messages = new ArrayList<>();
            messages.add(new String[]{"system",
                    "Sei l'assistente COSMIC AI integrato in Visual Paradigm. Rispondi in italiano, in modo conciso e preciso, "
                            + "su misurazione COSMIC (Entry/Exit/Read/Write), Use Case e CFP. Usa SOLO i dati del modello qui sotto: "
                            + "se un dato non c'e', dillo.\n\n=== MODELLO CORRENTE ===\n" + context});
            synchronized (chatHistory) { messages.addAll(chatHistory); }
            messages.add(new String[]{"user", userMessage});
            auxExecutor.submit(() -> {
                try {
                    String reply = extractAssistantContent(callLlmMessages(messages));
                    synchronized (chatHistory) {
                        chatHistory.add(new String[]{"user", userMessage});
                        chatHistory.add(new String[]{"assistant", reply});
                        while (chatHistory.size() > CHAT_HISTORY_MESSAGES) chatHistory.remove(0);
                    }
                    SwingUtilities.invokeLater(() -> onReply.accept(reply));
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> onError.accept(e));
                }
            });
        });
    }

    private String buildModelContext(String userMessage) {
        CosmicJsonModel m = currentModel;
        CosmicReport r = lastReport;
        if (m == null || r == null || m.useCases.isEmpty()) return "Nessuna analisi eseguita: il modello e' vuoto.";
        StringBuilder sb = new StringBuilder();
        sb.append("Progetto: ").append(m.projectName).append(" | use case: ").append(m.useCases.size())
                .append(" | CFP totali: ").append(r.totalCfp).append("\n");
        Map<String, UseCaseReport> byId = new LinkedHashMap<>();
        for (UseCaseReport ur : r.useCases) byId.put(ur.id, ur);
        for (UseCase uc : m.useCases) {
            if (sb.length() > 6000) { sb.append("(elenco troncato)\n"); break; }
            UseCaseReport ur = byId.get(uc.id);
            sb.append("- [").append(uc.id).append("] ").append(uc.name).append(" | ")
                    .append(ur == null ? 0 : ur.totalCfp).append(" CFP");
            if (uc.subsystem != null) sb.append(" | ").append(uc.subsystem);
            if (uc.violation != null) sb.append(" | VIOLAZIONE");
            sb.append("\n");
        }
        String lower = userMessage.toLowerCase();
        int shown = 0;
        for (UseCase uc : m.useCases) {
            boolean mentioned = lower.contains(uc.id.toLowerCase()) || (uc.name.length() > 4 && lower.contains(uc.name.toLowerCase()));
            if (!mentioned) continue;
            sb.append("\nDettaglio [").append(uc.id).append("] ").append(uc.name).append(":\n");
            for (FunctionalProcess fp : uc.functionalProcesses) {
                sb.append("  Triggering Event: ").append(fp.triggeringEvent).append("\n");
                for (SubProcess sp : fp.subProcesses) {
                    sb.append("  ").append(sp.step).append(". ").append(sp.description).append(" [")
                            .append(movementWord(sp.dataMovementType)).append(" | DG: ").append(sp.dataGroup)
                            .append(" | OOI: ").append(sp.objectOfInterest).append("]\n");
                }
            }
            if (uc.violation != null) sb.append("  Violazione: ").append(uc.violation).append("\n");
            if (++shown >= 3) break;
        }
        return sb.toString();
    }

    private void handleModelElementAdded(IModelElement element) {
        if (suppressSync.get() > 0 || currentModel == null) return;
        SwingUtilities.invokeLater(() -> {
            if (currentModel == null) return;
            if (element instanceof IUseCase) {
                UseCase added = addUseCaseIfMissing((IUseCase) element);
                if (added != null) recomputeAndPublish("Use case aggiunto manualmente su VP: '" + added.name + "'.", added);
            } else if (element instanceof IAssociation) {
                if (applyAssociation((IAssociation) element, true)) recomputeAndPublish("Associazione attore-use case aggiunta su VP.", null);
            }
        });
    }

    private void handleModelElementRemoved(IModelElement element) {
        if (suppressSync.get() > 0 || currentModel == null) return;
        SwingUtilities.invokeLater(() -> {
            if (currentModel == null) return;
            if (element instanceof IUseCase) {
                if (removeUseCaseIfPresent((IUseCase) element)) recomputeAndPublish("Use case rimosso da VP: i CFP sono stati ricalcolati.", null);
            } else if (element instanceof IAssociation) {
                if (applyAssociation((IAssociation) element, false)) recomputeAndPublish("Associazione attore-use case rimossa su VP.", null);
            }
        });
    }

    private void handleElementPropertyChanged(IModelElement element, String propertyName) {
        if (suppressSync.get() > 0 || currentModel == null) return;
        SwingUtilities.invokeLater(() -> {
            if (currentModel == null || propertyName == null || !propertyName.toLowerCase().contains("name")) return;
            String newName = safeTrim(element.getName());
            if (newName.isEmpty()) return;
            if (element instanceof IUseCase) {
                UseCase dto = findUseCaseByVpElementId((IUseCase) element);
                if (dto == null || newName.equals(dto.name)) return;
                String old = dto.name;
                dto.name = newName;
                for (FunctionalProcess fp : dto.functionalProcesses) {
                    if (fp.fpName == null || fp.fpName.equals(old)) fp.fpName = newName;
                    else if (fp.fpName.startsWith(old + " - ")) fp.fpName = newName + fp.fpName.substring(old.length());
                }
                recomputeAndPublish("Rinominato use case '" + old + "' -> '" + newName + "': CFP ricalcolati.", dto);
            } else if (element instanceof IActor) {
                Actor actor = findActorByVpElementId(element.getId());
                if (actor == null || newName.equals(actor.name)) return;
                String old = actor.name;
                actor.name = newName;
                recomputeAndPublish("Rinominato attore '" + old + "' -> '" + newName + "'.", null);
            }
        });
    }

    private void handleIncludeChanged(IInclude include, boolean added) {
        if (suppressSync.get() > 0 || currentModel == null) return;
        SwingUtilities.invokeLater(() -> {
            UseCase base = findUseCaseByVpObject(endOf(include, "getFrom"));
            UseCase target = findUseCaseByVpObject(endOf(include, "getTo"));
            if (base == null || target == null) return;
            boolean changed = added ? (!base.includesIds.contains(target.id) && base.includesIds.add(target.id))
                    : base.includesIds.remove(target.id);
            if (changed) recomputeAndPublish((added ? "Aggiunto" : "Rimosso") + " <<include>> " + base.id + " -> " + target.id + ".", null);
        });
    }

    private void handleExtendChanged(IExtend extend, boolean added) {
        if (suppressSync.get() > 0 || currentModel == null) return;
        SwingUtilities.invokeLater(() -> {
            UseCase extension = findUseCaseByVpObject(endOf(extend, "getFrom"));
            UseCase base = findUseCaseByVpObject(endOf(extend, "getTo"));
            if (extension == null || base == null) return;
            boolean exists = false;
            for (CosmicJsonModel.ExtendRelation e : extension.extendsList) if (base.id.equals(e.targetId)) exists = true;
            boolean changed = false;
            if (added && !exists) {
                CosmicJsonModel.ExtendRelation rel = new CosmicJsonModel.ExtendRelation();
                rel.targetId = base.id;
                rel.extensionPoint = "";
                extension.extendsList.add(rel);
                changed = true;
            } else if (!added && exists) {
                changed = extension.extendsList.removeIf(e -> base.id.equals(e.targetId));
            }
            if (changed) recomputeAndPublish((added ? "Aggiunto" : "Rimosso") + " <<extend>> " + extension.id + " -> " + base.id + ".", null);
        });
    }

    private UseCase addUseCaseIfMissing(IUseCase vpUseCase) {
        if (findUseCaseByVpElementId(vpUseCase) != null) return null;
        UseCase dto = new UseCase();
        dto.id = nextManualUseCaseId(currentModel);
        dto.name = safeTrim(vpUseCase.getName()).isEmpty() ? dto.id : safeTrim(vpUseCase.getName());
        dto.vpElementId = vpUseCase.getId();
        try {
            IDiagramUIModel active = ApplicationManager.instance().getDiagramManager().getActiveDiagram();
            dto.diagramId = active == null ? null : active.getId();
        } catch (Exception ignored) { }
        currentModel.useCases.add(dto);
        return dto;
    }

    private boolean removeUseCaseIfPresent(IUseCase vpUseCase) {
        UseCase dto = findUseCaseByVpElementId(vpUseCase);
        if (dto == null) return false;
        currentModel.useCases.remove(dto);
        for (UseCase other : currentModel.useCases) {
            other.includesIds.remove(dto.id);
            other.extendsList.removeIf(e -> dto.id.equals(e.targetId));
        }
        return true;
    }

    private boolean applyAssociation(IAssociation association, boolean added) {
        Object from = endOf(association, "getFrom");
        Object to = endOf(association, "getTo");
        IActor vpActor = null;
        IUseCase vpUseCase = null;
        if (from instanceof IActor && to instanceof IUseCase) { vpActor = (IActor) from; vpUseCase = (IUseCase) to; }
        else if (from instanceof IUseCase && to instanceof IActor) { vpActor = (IActor) to; vpUseCase = (IUseCase) from; }
        if (vpActor == null) return false;
        UseCase dto = findUseCaseByVpElementId(vpUseCase);
        if (dto == null) return false;
        Actor actor = findOrCreateActor(vpActor);
        if (added) {
            if (actor.id.equals(dto.primaryActorId) || dto.coActorIds.contains(actor.id)) return false;
            if (dto.primaryActorId == null) dto.primaryActorId = actor.id; else dto.coActorIds.add(actor.id);
            return true;
        }
        if (actor.id.equals(dto.primaryActorId)) {
            dto.primaryActorId = dto.coActorIds.isEmpty() ? null : dto.coActorIds.remove(0);
            return true;
        }
        return dto.coActorIds.remove(actor.id);
    }

    private UseCase findUseCaseByVpElementId(IUseCase vpUseCase) {
        if (currentModel == null || vpUseCase == null) return null;
        String id = vpUseCase.getId();
        for (UseCase uc : currentModel.useCases) if (id != null && id.equals(uc.vpElementId)) return uc;
        return null;
    }

    private UseCase findUseCaseByVpObject(Object o) {
        return (o instanceof IUseCase) ? findUseCaseByVpElementId((IUseCase) o) : null;
    }

    private Actor findActorByVpElementId(String vpId) {
        if (currentModel == null || vpId == null) return null;
        for (Actor a : currentModel.actors) if (vpId.equals(a.vpElementId)) return a;
        return null;
    }

    private Actor findOrCreateActor(IActor vpActor) {
        Actor byId = findActorByVpElementId(vpActor.getId());
        if (byId != null) return byId;
        Actor actor = findOrCreateActorByName(currentModel, safeTrim(vpActor.getName()).isEmpty() ? "Actor" : vpActor.getName());
        if (actor.vpElementId == null) actor.vpElementId = vpActor.getId();
        return actor;
    }

    private void recomputeAndPublish(String reason, UseCase affected) {
        if (currentModel == null || recomputing) return;
        recomputing = true;
        try {
            CosmicCalculator calculator = new CosmicCalculator();
            lastReport = calculator.compute(currentModel);
            calculator.applyViolationFlags(currentModel, lastReport);
            if (affected != null) {
                suppressSync.incrementAndGet();
                try {
                    VisualFeedbackApplier.applyToUseCase(affected, findUseCaseReport(lastReport, affected.id));
                } catch (Exception ex) {
                    System.out.println("[COSMIC AI] Feedback visivo non applicato: " + ex);
                } finally {
                    suppressSync.decrementAndGet();
                }
            }
            markDirty();
            CosmicAnalysisListener ui = uiListener();
            if (ui != null) ui.onModelSynchronized(currentModel, lastReport, reason);
            publishCurrentScope();
        } finally {
            recomputing = false;
        }
    }

    private void publishCurrentScope() {
        CosmicAnalysisListener ui = activeUiListener;
        CosmicJsonModel model = currentModel;
        CosmicReport total = lastReport;
        if (ui == null || model == null || total == null) return;
        IDiagramUIModel active;
        try {
            active = ApplicationManager.instance().getDiagramManager().getActiveDiagram();
        } catch (Exception ex) {
            return;
        }
        String id = active == null ? null : active.getId();
        String name = active == null ? null : active.getName();
        Set<String> ids = new HashSet<>();
        if (id != null) for (UseCase uc : model.useCases) if (id.equals(uc.diagramId)) ids.add(uc.id);
        CosmicReport scoped = new CosmicCalculator().computeScoped(model, ids);
        lastActiveDiagramId = id;
        ui.onScopeChanged(name, scoped, total);
    }

    private static Object endOf(Object relationship, String getter) {
        return VpProjectReader.endOf(relationship, getter);
    }

    private static String safeTrim(String s) { return s == null ? "" : s.trim(); }

    private void markDirty() {
        if (currentModel == null) return;
        persistDirty = true;
        if (persistTimer != null) persistTimer.restart();
    }

    private void flushPersistence() {
        if (!persistDirty) return;
        CosmicJsonModel model = currentModel;
        IProject project = modelProject;
        if (model == null || project == null) { persistDirty = false; return; }
        if (ApplicationManager.instance().getProjectManager().getProject() != project) return;
        suppressSync.incrementAndGet();
        try {
            ProjectModelStore.save(project, CosmicModelSerializer.toJson(model));
            persistDirty = false;
            lastPersistError = null;
        } catch (ProjectModelStore.StoreException | RuntimeException e) {
            persistDirty = false;
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (!msg.equals(lastPersistError)) {
                lastPersistError = msg;
                CosmicAnalysisListener ui = uiListener();
                String text = "ATTENZIONE: modello COSMIC NON salvato nel progetto (.vpp): " + msg;
                if (ui != null) ui.onLog(text); else System.out.println("[COSMIC AI] " + text);
            }
        } finally {
            suppressSync.decrementAndGet();
        }
    }

    private void handleProjectPreSave(IProject project) {
        try {
            onEdtAndWait(() -> {
                if (project != modelProject || currentModel == null) return;
                persistDirty = true;
                flushPersistence();
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleProjectAfterOpened(IProject project) {
        SwingUtilities.invokeLater(() -> loadPersistedModel(project));
    }

    private void loadPersistedModel(IProject project) {
        if (project == null || analysisRunning.get()) return;
        if (modelProject == project && currentModel != null) return;
        CosmicAnalysisListener ui = uiListener();

        String json = null;
        try {
            json = ProjectModelStore.load(project);
        } catch (ProjectModelStore.StoreException e) {
            String text = "Modello COSMIC salvato nel progetto non utilizzabile: " + e.getMessage();
            if (ui != null) ui.onLog(text); else System.out.println("[COSMIC AI] " + text);
        }

        if (json == null) {
            if (modelProject != project) {
                boolean hadModel = currentModel != null;
                currentModel = null;
                lastReport = null;
                knownUseCaseIds = new HashSet<>();
                activeGenerator = null;
                persistDirty = false;
                modelProject = project;
                if (hadModel && ui != null) ui.onModelCleared();
            }
            return;
        }

        CosmicJsonModel model;
        try {
            model = CosmicModelSerializer.fromJson(json);
        } catch (RuntimeException e) {
            String text = "Modello COSMIC salvato nel progetto non leggibile (" + e.getMessage() + "): ignorato.";
            if (ui != null) ui.onLog(text); else System.out.println("[COSMIC AI] " + text);
            return;
        }

        List<String> notes = new ArrayList<>();
        boolean changed = syncModelWithVp(project, model, notes);

        currentModel = model;
        modelProject = project;
        activeGenerator = null;
        knownUseCaseIds = new HashSet<>();
        for (UseCase uc : model.useCases) knownUseCaseIds.add(uc.id);
        manualUseCaseCounter = 0;
        CosmicCalculator calculator = new CosmicCalculator();
        lastReport = calculator.compute(model);
        calculator.applyViolationFlags(model, lastReport);
        synchronized (chatHistory) { chatHistory.clear(); }
        if (changed) markDirty();

        if (ui != null) {
            ui.onLog("Modello COSMIC ripristinato dal progetto: " + model.useCases.size() + " use case, "
                    + lastReport.totalCfp + " CFP totali.");
            for (String n : notes) ui.onLog("  riconciliazione: " + n);
            ui.onAnalysisCompleted(model, lastReport);
        }
        publishCurrentScope();
    }

    public void publishCurrentModelTo(CosmicAnalysisListener listener) {
        runOnEdt(() -> {
            CosmicJsonModel model = currentModel;
            CosmicReport report = lastReport;
            if (listener != null && model != null && report != null && !analysisRunning.get()) {
                listener.onAnalysisCompleted(model, report);
            }
            publishCurrentScope();
        });
    }

    private boolean syncModelWithVp(IProject project, CosmicJsonModel model, List<String> notes) {
        boolean changed = false;
        VpProjectReader.Snapshot snap = VpProjectReader.read(project);
        Map<String, VpProjectReader.UseCaseSnap> vpById = new LinkedHashMap<>();
        for (VpProjectReader.UseCaseSnap u : snap.useCases) vpById.put(u.vpId, u);

        List<UseCase> gone = new ArrayList<>();
        int tracked = 0;
        for (UseCase uc : model.useCases) {
            if (uc.vpElementId == null) continue;
            tracked++;
            if (vpById.containsKey(uc.vpElementId)) continue;
            IModelElement el = project.getModelElementById(uc.vpElementId);
            if (!(el instanceof IUseCase)) gone.add(uc);
        }
        if (tracked > 0 && gone.size() == tracked) {
            notes.add("nessuno degli use case salvati e' presente nel progetto: non rimuovo nulla.");
            return false;
        }
        for (UseCase uc : gone) {
            model.useCases.remove(uc);
            for (UseCase other : model.useCases) {
                other.includesIds.remove(uc.id);
                other.extendsList.removeIf(e -> uc.id.equals(e.targetId));
            }
            changed = true;
        }
        if (!gone.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (UseCase uc : gone) ids.append(ids.length() == 0 ? "" : ", ").append(uc.id);
            notes.add(gone.size() + " use case non piu' presenti sul canvas, rimossi dal modello: " + ids);
        }

        Map<String, UseCase> byVp = new LinkedHashMap<>();
        for (UseCase uc : model.useCases) if (uc.vpElementId != null) byVp.put(uc.vpElementId, uc);
        int added = 0;
        for (VpProjectReader.UseCaseSnap u : snap.useCases) {
            UseCase uc = byVp.get(u.vpId);
            if (uc == null) {
                uc = new UseCase();
                uc.id = nextManualUseCaseId(model);
                uc.vpElementId = u.vpId;
                uc.name = u.name.isEmpty() ? uc.id : u.name;
                model.useCases.add(uc);
                byVp.put(u.vpId, uc);
                added++;
                changed = true;
            } else if (!u.name.isEmpty() && !u.name.equals(uc.name)) {
                applyRename(uc, u.name);
                changed = true;
            }
        }
        if (added > 0) notes.add(added + " use case presenti su VP ma non nel modello: aggiunti senza misura (usa 'Rimisura da VP').");

        changed |= rebuildRelations(model, snap, byVp, notes);

        Set<String> referenced = new HashSet<>();
        for (UseCase uc : model.useCases) {
            if (uc.primaryActorId != null) referenced.add(uc.primaryActorId);
            referenced.addAll(uc.coActorIds);
        }
        Set<String> vpActorIds = new HashSet<>();
        for (VpProjectReader.ActorSnap a : snap.actors) vpActorIds.add(a.vpId);
        if (!vpActorIds.isEmpty()) {
            changed |= model.actors.removeIf(a -> a.vpElementId != null && !vpActorIds.contains(a.vpElementId) && !referenced.contains(a.id));
        }
        return changed;
    }

    private boolean rebuildRelations(CosmicJsonModel model, VpProjectReader.Snapshot snap, Map<String, UseCase> byVp, List<String> notes) {
        boolean changed = false;

        boolean modelHasIncludes = false;
        boolean modelHasExtends = false;
        for (UseCase uc : model.useCases) {
            if (!uc.includesIds.isEmpty()) modelHasIncludes = true;
            if (!uc.extendsList.isEmpty()) modelHasExtends = true;
        }
        if (!snap.includes.isEmpty() || !modelHasIncludes) {
            for (UseCase uc : model.useCases) {
                if (uc.vpElementId == null) continue;
                List<String> fresh = new ArrayList<>();
                for (VpProjectReader.LinkSnap l : snap.includes) {
                    UseCase target = uc.vpElementId.equals(l.fromVpId) ? byVp.get(l.toVpId) : null;
                    if (target != null && target != uc && !fresh.contains(target.id)) fresh.add(target.id);
                }
                if (!fresh.equals(uc.includesIds)) { uc.includesIds.clear(); uc.includesIds.addAll(fresh); changed = true; }
            }
        } else {
            notes.add("nessuna relazione <<include>> letta da VP: conservo quelle del modello.");
        }

        if (!snap.extendsLinks.isEmpty() || !modelHasExtends) {
            for (UseCase uc : model.useCases) {
                if (uc.vpElementId == null) continue;
                List<CosmicJsonModel.ExtendRelation> fresh = new ArrayList<>();
                for (VpProjectReader.LinkSnap l : snap.extendsLinks) {
                    UseCase base = uc.vpElementId.equals(l.fromVpId) ? byVp.get(l.toVpId) : null;
                    if (base == null || base == uc) continue;
                    CosmicJsonModel.ExtendRelation rel = new CosmicJsonModel.ExtendRelation();
                    rel.targetId = base.id;
                    rel.extensionPoint = "";
                    for (CosmicJsonModel.ExtendRelation old : uc.extendsList) {
                        if (base.id.equals(old.targetId) && old.extensionPoint != null) rel.extensionPoint = old.extensionPoint;
                    }
                    boolean dup = false;
                    for (CosmicJsonModel.ExtendRelation f : fresh) if (f.targetId.equals(rel.targetId)) dup = true;
                    if (!dup) fresh.add(rel);
                }
                boolean same = fresh.size() == uc.extendsList.size();
                for (int i = 0; same && i < fresh.size(); i++) same = fresh.get(i).targetId.equals(uc.extendsList.get(i).targetId);
                if (!same) { uc.extendsList.clear(); uc.extendsList.addAll(fresh); changed = true; }
            }
        } else {
            notes.add("nessuna relazione <<extend>> letta da VP: conservo quelle del modello.");
        }

        boolean modelHasActors = false;
        for (UseCase uc : model.useCases) if (uc.primaryActorId != null) modelHasActors = true;
        if (!snap.associations.isEmpty() || !modelHasActors) {
            for (UseCase uc : model.useCases) {
                if (uc.vpElementId == null) continue;
                List<String> actorIds = new ArrayList<>();
                for (VpProjectReader.LinkSnap l : snap.associations) {
                    if (!uc.vpElementId.equals(l.toVpId)) continue;
                    Actor a = resolveActor(model, snap, l.fromVpId);
                    if (a != null && !actorIds.contains(a.id)) actorIds.add(a.id);
                }
                String primary = actorIds.contains(uc.primaryActorId) ? uc.primaryActorId : (actorIds.isEmpty() ? null : actorIds.get(0));
                List<String> co = new ArrayList<>(actorIds);
                if (primary != null) co.remove(primary);
                boolean same = java.util.Objects.equals(primary, uc.primaryActorId) && co.equals(uc.coActorIds);
                if (!same) {
                    uc.primaryActorId = primary;
                    uc.coActorIds.clear();
                    uc.coActorIds.addAll(co);
                    changed = true;
                }
            }
        } else {
            notes.add("nessuna associazione attore-use case letta da VP: conservo gli attori del modello.");
        }
        return changed;
    }

    private static Actor resolveActor(CosmicJsonModel model, VpProjectReader.Snapshot snap, String actorVpId) {
        for (Actor a : model.actors) if (actorVpId.equals(a.vpElementId)) return a;
        for (VpProjectReader.ActorSnap s : snap.actors) {
            if (!s.vpId.equals(actorVpId)) continue;
            Actor a = findOrCreateActorByName(model, s.name.isEmpty() ? "Actor" : s.name);
            if (a.vpElementId == null) a.vpElementId = actorVpId;
            return a;
        }
        return null;
    }

    private String nextManualUseCaseId(CosmicJsonModel model) {
        Set<String> ids = new HashSet<>();
        for (UseCase uc : model.useCases) ids.add(uc.id);
        int n = manualUseCaseCounter;
        String id;
        do { id = "UC-USER-" + (++n); } while (ids.contains(id));
        manualUseCaseCounter = n;
        return id;
    }

    private static void applyRename(UseCase dto, String newName) {
        String old = dto.name;
        dto.name = newName;
        for (FunctionalProcess fp : dto.functionalProcesses) {
            if (fp.fpName == null || fp.fpName.equals(old)) fp.fpName = newName;
            else if (old != null && fp.fpName.startsWith(old + " - ")) fp.fpName = newName + fp.fpName.substring(old.length());
        }
    }

    private static int countUnmeasuredSentences(CosmicJsonModel model) {
        int n = 0;
        for (UseCase uc : model.useCases)
            for (FunctionalProcess fp : uc.functionalProcesses)
                for (SubProcess sp : fp.subProcesses) if (sp.unmeasured) n++;
        return n;
    }

    private static boolean hasAnySubProcess(UseCase uc) {
        for (FunctionalProcess fp : uc.functionalProcesses) if (!fp.subProcesses.isEmpty()) return true;
        return false;
    }

    public boolean applyManualEdit(String ucId, String fpId, int step, ManualField field, String value) {
        CosmicJsonModel model = currentModel;
        if (model == null || analysisRunning.get()) return false;
        UseCase uc = null;
        for (UseCase u : model.useCases) if (u.id != null && u.id.equals(ucId)) uc = u;
        if (uc == null) return false;
        SubProcess target = null;
        for (FunctionalProcess fp : uc.functionalProcesses) {
            if (fpId != null && !fpId.equals(fp.fpId)) continue;
            for (SubProcess sp : fp.subProcesses) if (sp.step == step) target = sp;
        }
        if (target == null) return false;

        String text = value == null ? "" : value.trim();
        String description;
        switch (field) {
            case DATA_MOVEMENT: {
                String type = CosmicMicroLoop.canonicalMovement(text);
                if (CosmicMicroLoop.INVALID.equals(type)) return false;
                if (java.util.Objects.equals(type, target.dataMovementType) && !target.unmeasured) return false;
                target.dataMovementType = type;
                description = "data movement -> " + (type == null ? "No data movement" : type);
                break;
            }
            case FUNCTIONAL_USER:
                if (java.util.Objects.equals(emptyToNull(text), target.functionalUser)) return false;
                target.functionalUser = emptyToNull(text);
                description = "functional user -> " + text;
                break;
            case DATA_GROUP:
                if (java.util.Objects.equals(emptyToNull(text), target.dataGroup)) return false;
                target.dataGroup = emptyToNull(text);
                description = "data group -> " + text;
                break;
            case OBJECT_OF_INTEREST:
                if (java.util.Objects.equals(emptyToNull(text), target.objectOfInterest)) return false;
                target.objectOfInterest = emptyToNull(text);
                description = "object of interest -> " + text;
                break;
            default:
                return false;
        }
        target.manuallyEdited = true;
        target.unmeasured = false;
        recomputeAndPublish("Modifica manuale [" + uc.id + "] passo " + step + ": " + description + ". CFP ricalcolati.", uc);
        return true;
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    public enum ManualField { FUNCTIONAL_USER, DATA_MOVEMENT, DATA_GROUP, OBJECT_OF_INTEREST }

    private static final class RemeasureItem {
        final String ucId;
        final String name;
        final String spec;
        final String fingerprint;
        final boolean measure;

        RemeasureItem(String ucId, String name, String spec, String fingerprint, boolean measure) {
            this.ucId = ucId;
            this.name = name;
            this.spec = spec;
            this.fingerprint = fingerprint;
            this.measure = measure;
        }
    }

    public void remeasureFromVp(boolean onlyChanged, CosmicAnalysisListener listener) {
        if (!beginRun(listener, true)) return;
        runningTask = pipelineExecutor.submit(() -> {
            try {
                runRemeasure(onlyChanged, listener);
            } catch (Throwable ex) {
                failRun(listener, ex);
            }
        });
    }

    private void runRemeasure(boolean onlyChanged, CosmicAnalysisListener ui) throws Exception {
        final long t0 = System.currentTimeMillis();

        final List<RemeasureItem> items = new ArrayList<>();
        onEdtAndWait(() -> planRemeasure(onlyChanged, items, ui));
        final List<RemeasureItem> work = new ArrayList<>();
        for (RemeasureItem it : items) if (it.measure) work.add(it);
        final int total = work.size();
        postLog(ui, "Rimisura da VP (" + (onlyChanged ? "solo use case nuovi/modificati" : "tutti gli use case") + "): "
                + items.size() + " use case nel progetto, " + total + " da misurare.");

        int done = 0;
        int failures = 0;
        int consecutiveFailures = 0;
        boolean aborted = false;
        String abortReason = null;
        List<String> failedIds = new ArrayList<>();

        try {
            for (RemeasureItem it : work) {
                checkCancelled();
                final String eta = estimate(t0, done, total);
                final String label = "UC " + (done + 1) + "/" + total + " [" + it.ucId + "] " + it.name;
                postProgress(ui, done, total, label + " - Step 1/2: split" + eta);

                Measured measured = new Measured();
                try {
                    measureScenario(it.ucId, it.name, splitIntoParts(it.spec, MAX_CHUNK_CHARS), label, done, total, eta, ui, measured);
                    if (measured.unusable) { failures++; consecutiveFailures++; failedIds.add(it.ucId); }
                    else consecutiveFailures = 0;
                } catch (AnalysisCancelled | InterruptedException c) {
                    throw c;
                } catch (Exception e) {
                    measured.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    failures++;
                    consecutiveFailures++;
                    failedIds.add(it.ucId);
                    postLog(ui, "ERRORE su [" + it.ucId + "] " + it.name + ": " + measured.error + " (misura precedente conservata).");
                }
                final int doneAfter = ++done;
                onEdtAndWait(() -> commitRemeasured(it, measured, doneAfter, total, ui));

                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    aborted = true;
                    abortReason = MAX_CONSECUTIVE_FAILURES + " use case consecutivi non misurabili: l'LLM non risponde o e' mal configurato.";
                    break;
                }
            }
        } catch (AnalysisCancelled | InterruptedException c) {
            Thread.interrupted();
            aborted = true;
            abortReason = "interrotta dall'utente";
        }

        final boolean fAborted = aborted;
        final String fReason = abortReason;
        final int fDone = done;
        final int fFailures = failures;
        final List<String> fFailed = failedIds;
        analysisRunning.set(false);
        SwingUtilities.invokeLater(() -> finishRemeasure(ui, fDone, total, fFailures, fFailed, fAborted, fReason, t0));
    }

    private void planRemeasure(boolean onlyChanged, List<RemeasureItem> items, CosmicAnalysisListener ui) {
        IProject project = ApplicationManager.instance().getProjectManager().getProject();
        if (project == null) throw new IllegalStateException("Nessun progetto aperto in Visual Paradigm.");
        VpProjectReader.Snapshot snap = VpProjectReader.read(project);
        if (snap.useCases.isEmpty()) {
            throw new IllegalStateException("Il progetto non contiene use case.");
        }
        CosmicJsonModel model = currentModel;
        if (model == null || modelProject != project) {
            model = new CosmicJsonModel();
            model.projectName = "Progetto Visual Paradigm";
            currentModel = model;
            modelProject = project;
            knownUseCaseIds = new HashSet<>();
            manualUseCaseCounter = 0;
        }
        Map<String, UseCase> byVp = new LinkedHashMap<>();
        for (UseCase uc : model.useCases) if (uc.vpElementId != null) byVp.put(uc.vpElementId, uc);

        for (VpProjectReader.UseCaseSnap u : snap.useCases) {
            UseCase uc = byVp.get(u.vpId);
            if (uc == null) {
                uc = new UseCase();
                uc.id = nextManualUseCaseId(model);
                uc.vpElementId = u.vpId;
                uc.name = u.name.isEmpty() ? uc.id : u.name;
                try {
                    IDiagramUIModel active = ApplicationManager.instance().getDiagramManager().getActiveDiagram();
                    uc.diagramId = active == null ? null : active.getId();
                } catch (Exception ignored) { }
                model.useCases.add(uc);
                byVp.put(u.vpId, uc);
            } else if (!u.name.isEmpty() && !u.name.equals(uc.name)) {
                applyRename(uc, u.name);
            }
            knownUseCaseIds.add(uc.id);

            String spec = UseCaseDescription.specificationOf(u.description, uc.id);
            String fingerprint = UseCaseDescription.fingerprint(uc.name, spec);
            boolean unchanged = fingerprint.equals(uc.specFingerprint);
            boolean needs = !spec.isEmpty()
                    && (!onlyChanged || !hasAnySubProcess(uc) || !unchanged || hasUnmeasuredSentence(uc));
            if (spec.isEmpty()) {
                ui.onLog("[" + uc.id + "] " + uc.name + ": campo Specification vuoto, use case non misurabile.");
            }
            items.add(new RemeasureItem(uc.id, uc.name, spec, fingerprint, needs));
        }
    }

    private static boolean hasUnmeasuredSentence(UseCase uc) {
        for (FunctionalProcess fp : uc.functionalProcesses)
            for (SubProcess sp : fp.subProcesses) if (sp.unmeasured) return true;
        return false;
    }

    private void commitRemeasured(RemeasureItem item, Measured measured, int done, int total, CosmicAnalysisListener ui) {
        CosmicJsonModel model = currentModel;
        if (model == null) return;
        UseCase uc = null;
        for (UseCase u : model.useCases) if (item.ucId.equals(u.id)) uc = u;
        if (uc == null) return;
        suppressSync.incrementAndGet();
        try {
            boolean keepOld = measured.functionalProcesses.isEmpty()
                    || measured.error != null
                    || (measured.unusable && hasAnySubProcess(uc));
            if (keepOld) {
                if (measured.error == null) ui.onLog("[" + uc.id + "] nessuna frase misurata: conservo la misura precedente.");
            } else {
                uc.functionalProcesses.clear();
                int n = 1;
                for (FunctionalProcess fp : measured.functionalProcesses) {
                    fp.fpId = "FP" + n;
                    fp.fpName = measured.functionalProcesses.size() == 1 ? uc.name : uc.name + " - FP" + n;
                    uc.functionalProcesses.add(fp);
                    n++;
                }
                uc.mainScenario.clear();
                uc.exceptions.clear();
                for (SubProcess sp : uc.functionalProcesses.get(0).subProcesses) uc.mainScenario.add(sp.description);
                uc.specFingerprint = item.fingerprint;
                if (uc.primaryActorId == null) {
                    String fu = firstEntryUser(uc.functionalProcesses.get(0));
                    if (fu != null) uc.primaryActorId = findOrCreateActorByName(model, fu).id;
                }
            }

            CosmicCalculator calculator = new CosmicCalculator();
            lastReport = calculator.compute(model);
            calculator.applyViolationFlags(model, lastReport);
            UseCaseReport ucReport = findUseCaseReport(lastReport, uc.id);
            try {
                VisualFeedbackApplier.applyToUseCase(uc, ucReport);
            } catch (Exception drawEx) {
                ui.onLog("Errore nell'aggiornamento di [" + uc.id + "] su VP: " + drawEx);
            }
            markDirty();
            publishCurrentScope();
            ui.onUseCaseProcessed(uc, ucReport, lastReport, done, total);
            ui.onProgress(done, total, "UC " + done + "/" + total + " rimisurato: [" + uc.id + "] " + uc.name
                    + " -> " + (ucReport == null ? 0 : ucReport.totalCfp) + " CFP");
        } finally {
            suppressSync.decrementAndGet();
        }
    }

    private void finishRemeasure(CosmicAnalysisListener ui, int done, int total, int failures, List<String> failedIds,
                                 boolean aborted, String abortReason, long t0) {
        CosmicJsonModel model = currentModel;
        try {
            List<String> notes = new ArrayList<>();
            IProject project = ApplicationManager.instance().getProjectManager().getProject();
            if (model != null && project != null && project == modelProject) {
                suppressSync.incrementAndGet();
                try {
                    syncModelWithVp(project, model, notes);
                } finally {
                    suppressSync.decrementAndGet();
                }
            }
            if (model != null) {
                CosmicCalculator calculator = new CosmicCalculator();
                lastReport = calculator.compute(model);
                calculator.applyViolationFlags(model, lastReport);
                for (UseCase uc : model.useCases) {
                    try {
                        VisualFeedbackApplier.applyToUseCase(uc, findUseCaseReport(lastReport, uc.id));
                    } catch (Exception ignored) { }
                }
            }
            StringBuilder sum = new StringBuilder();
            sum.append(aborted ? "Rimisura INTERROTTA (" + abortReason + "): " : "Rimisura completata: ")
                    .append(done).append("/").append(total).append(" use case misurati");
            if (lastReport != null) sum.append(", ").append(lastReport.totalCfp).append(" CFP totali");
            sum.append(", ").append(failures).append(" non riusciti, in ").append(formatDuration(System.currentTimeMillis() - t0)).append(".");
            ui.onLog(sum.toString());
            if (!failedIds.isEmpty()) ui.onLog("Use case non rimisurati: " + String.join(", ", failedIds));
            for (String n : notes) ui.onLog("  riallineamento con VP: " + n);
            int unmeasured = model == null ? 0 : countUnmeasuredSentences(model);
            if (unmeasured > 0) ui.onLog(unmeasured + " frasi non misurate: evidenziate nella tab 'Struttura COSMIC'.");
            markDirty();
            flushPersistence();
            ui.onBusyStateChanged(false);
            if (model != null && lastReport != null) ui.onAnalysisCompleted(model, lastReport);
            publishCurrentScope();
        } catch (Exception e) {
            ui.onBusyStateChanged(false);
            ui.onAnalysisFailed(e);
        }
    }

    static List<String> splitIntoParts(String text, int max) {
        List<String> parts = new ArrayList<>();
        if (text.length() <= max) { parts.add(text); return parts; }
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (cur.length() > 0 && cur.length() + line.length() + 1 > max) {
                parts.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts;
    }

    private static final class LlmHttpException extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        LlmHttpException(int status, String body) {
            super("HTTP " + status + (body == null || body.isEmpty() ? "" : ": " + abbreviate(body, 300)));
            this.status = status;
        }
    }

    private static final class LlmConfigException extends IOException {
        private static final long serialVersionUID = 1L;
        LlmConfigException(String message, Throwable cause) { super(message, cause); }
    }

    private static String httpStatusHint(int status) {
        switch (status) {
            case 400: return " - probabile 'context length' troppo piccolo in LM Studio o modello errato.";
            case 401: case 403: return " - API key mancante o non valida.";
            case 404: return " - endpoint non trovato.";
            default: return "";
        }
    }

    private static String abbreviate(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private String callLlmWithRetry(String systemPrompt, String userText) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= LLM_MAX_ATTEMPTS; attempt++) {
            checkCancelled();
            try {
                return extractAssistantContent(callLlm(systemPrompt, userText));
            } catch (LlmConfigException e) {
                throw e;
            } catch (LlmHttpException e) {
                boolean retriable = e.status >= 500 || e.status == 408 || e.status == 429;
                if (!retriable) {
                    throw new IOException(e.getMessage() + httpStatusHint(e.status), e);
                }
                last = e;
            } catch (IOException e) {
                last = e;
            }
            if (attempt < LLM_MAX_ATTEMPTS) Thread.sleep(LLM_RETRY_BASE_MS << (attempt - 1));
        }
        throw last == null ? new IOException("LLM non raggiungibile") : last;
    }

    private String callLlm(String systemPrompt, String userText) throws IOException, InterruptedException {
        List<String[]> messages = new ArrayList<>();
        messages.add(new String[]{"system", systemPrompt});
        messages.add(new String[]{"user", userText});
        return callLlmMessages(messages);
    }

    private String callLlmMessages(List<String[]> messages) throws IOException, InterruptedException {
        CosmicSettings.Snapshot cfg = CosmicSettings.load();
        String requestBody = buildChatCompletionRequestJson(cfg.modelName, messages);
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder().uri(URI.create(cfg.endpointUrl));
        } catch (IllegalArgumentException e) {
            throw new LlmConfigException("URL dell'LLM non valido ('" + cfg.endpointUrl + "').", e);
        }
        builder.timeout(Duration.ofSeconds(cfg.timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (cfg.hasApiKey()) {
            builder.header("Authorization", "Bearer " + cfg.apiKey.trim());
        }
        HttpRequest request = builder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new LlmHttpException(response.statusCode(), response.body());
        return response.body();
    }

    private String buildChatCompletionRequestJson(String model, List<String[]> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(escapeJson(model)).append("\",\"temperature\":0,\"stream\":false,\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"role\":\"").append(messages.get(i)[0]).append("\",\"content\":\"")
                    .append(escapeJson(messages.get(i)[1])).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantContent(String responseBody) throws IOException {
        Object parsed;
        try {
            parsed = MiniJsonParser.parse(responseBody);
        } catch (RuntimeException e) {
            throw new IOException("risposta HTTP non in formato JSON: " + abbreviate(responseBody, 200));
        }
        if (!(parsed instanceof Map)) throw new IOException("risposta HTTP inattesa: " + abbreviate(responseBody, 200));
        Map<String, Object> root = (Map<String, Object>) parsed;
        if (root.get("error") != null) throw new IOException("errore dal server LLM: " + abbreviate(String.valueOf(root.get("error")), 300));
        Object choicesObj = root.get("choices");
        if (!(choicesObj instanceof List) || ((List<Object>) choicesObj).isEmpty()) throw new IOException("risposta senza 'choices'");
        Object first = ((List<Object>) choicesObj).get(0);
        Object message = (first instanceof Map) ? ((Map<String, Object>) first).get("message") : null;
        Object content = (message instanceof Map) ? ((Map<String, Object>) message).get("content") : null;
        String text = content == null ? "" : String.valueOf(content);
        text = stripReasoningArtifacts(text);
        if (text.trim().isEmpty()) {
            Object finish = (first instanceof Map) ? ((Map<String, Object>) first).get("finish_reason") : null;
            throw new IOException("risposta vuota dall'LLM (finish_reason=" + finish + ")");
        }
        return text;
    }

    private static String stripReasoningArtifacts(String text) {
        String t = text;
        int ch = t.lastIndexOf("<|channel|>final<|message|>");
        if (ch >= 0) t = t.substring(ch + "<|channel|>final<|message|>".length());
        t = t.replaceAll("<\\|(?:return|end|start)\\|>", "");
        t = t.replaceAll("(?s)<think>.*?</think>", "");
        return t.trim();
    }

    private static String escapeJson(String s) {
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
    private static String stripMarkdownFences(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstNewline = trimmed.indexOf('\n');
        String withoutOpeningFence = (firstNewline >= 0) ? trimmed.substring(firstNewline + 1) : trimmed;
        int closingFenceIndex = withoutOpeningFence.lastIndexOf("```");
        return (closingFenceIndex >= 0) ? withoutOpeningFence.substring(0, closingFenceIndex).trim() : withoutOpeningFence.trim();
    }

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeLater(r);
    }

    private static void onEdtAndWait(Runnable r) throws InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) { r.run(); return; }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            if (c instanceof Error) throw (Error) c;
            throw new RuntimeException(c);
        }
    }

    private static void postLog(CosmicAnalysisListener ui, String msg) {
        SwingUtilities.invokeLater(() -> ui.onLog(msg));
    }

    private static void postProgress(CosmicAnalysisListener ui, int done, int total, String desc) {
        SwingUtilities.invokeLater(() -> ui.onProgress(done, total, desc));
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}