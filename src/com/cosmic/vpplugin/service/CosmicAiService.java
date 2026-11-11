package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.calculator.CosmicCalculator;
import com.cosmic.vpplugin.calculator.CosmicCalculator.AreaReport;
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
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalArea;
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
 * Orchestratore del plugin COSMIC AI (v4.0).
 *
 * <h3>Pipeline (replica dello script del professore, functions.py / prompt.py, con Micro-Loop)</h3>
 * Per OGNI use case, in loop e uno alla volta:
 * <ol>
 *   <li><b>Step 1 - Split</b>: SYSTEM_SPLIT_UC + {@code "<scenario>\n\nSplit:"} (frasi atomiche numerate);</li>
 *   <li><b>Step 2 - Measure (Micro-Loop, v4.0)</b>: l'output dello Step 1 viene parsato in Java in una lista di
 *       frasi e per OGNI frase si fa una chiamata LLM indipendente che estrae il data movement (E/X/R/W) di
 *       quella sola frase; i micro-risultati sono uniti in un unico FunctionalProcess. L'LLM non misura mai uno
 *       use case intero in un colpo solo (vedi {@link CosmicMicroLoop}).</li>
 * </ol>
 * <h3>Altre responsabilita' (v4.0)</h3>
 * <ul>
 *   <li>correzione manuale dei data movement con ricalcolo istantaneo ({@link #applyManualEdit});</li>
 *   <li>"Rimisura da VP": Visual Paradigm e' la Source of Truth ({@link #remeasureFromVp});</li>
 *   <li>persistenza del modello COSMIC nel file .vpp e riconciliazione all'apertura ({@link ProjectModelStore});</li>
 *   <li>configurazione dell'LLM da {@link CosmicSettings} (niente piu' parametri -Dcosmic.llm.*).</li>
 * </ul>
 * L'IDENTITA' dello use case (ID, nome, sottosistema, attori, include) viene dal pre-parsing del documento
 * ({@link UseCaseChunker}) e non transita mai dall'LLM: non puo' piu' essere persa o rimappata su "UC1/FP1".
 * Ogni chiamata contiene un solo use case (in media ~1.300 caratteri): niente piu' "Context Window Exceeded".
 *
 * <h3>Threading</h3>
 * Il thread di lavoro ({@code cosmic-ai-pipeline}) fa solo I/O (parsing, HTTP verso LM Studio). Tutto cio' che
 * tocca Visual Paradigm o Swing (modello, diagrammi, stereotipi, UI) gira sull'Event Dispatch Thread, UN use
 * case alla volta ({@code invokeAndWait}, che da' anche back-pressure): dopo ogni use case misurato il suo
 * diagramma viene aggiornato e i CFP ricalcolati, e tra due use case l'EDT e' libero di ridisegnare. Nessun
 * blocco da centinaia di elementi: l'IDE non si congela e il diagramma cresce sotto gli occhi dell'utente.
 */
public final class CosmicAiService {

    private static final CosmicAiService INSTANCE = new CosmicAiService();
    public static CosmicAiService getInstance() { return INSTANCE; }

    // ---- configurazione LLM: endpoint, modello, API key e timeout vengono da CosmicSettings (tab "Impostazioni") ----
    private static final int LLM_MAX_ATTEMPTS = 3;
    private static final long LLM_RETRY_BASE_MS = 5_000L;   // come lo script del professore: 5s, 10s, ...
    /** Rete di sicurezza: uno scenario piu' lungo viene diviso (stesso use case). Albergate: max ~2.700. */
    private static final int MAX_CHUNK_CHARS = 6000;
    /** Dopo N use case consecutivi falliti (LM Studio spento?) l'analisi si interrompe con un messaggio chiaro. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final int PROJECT_WATCHER_INTERVAL_MS = 1500;
    private static final int DIAGRAM_SCOPE_WATCHER_INTERVAL_MS = 800;
    private static final int CHAT_HISTORY_MESSAGES = 16;
    /** Dopo l'ultima modifica al modello si aspetta questo tempo prima di scriverlo nel progetto (accorpa i salvataggi). */
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

    /** > 0 mentre il plugin stesso crea/modifica elementi VP: i listener di sync ignorano i propri eventi. */
    private final AtomicInteger suppressSync = new AtomicInteger(0);
    private boolean recomputing;
    private int manualUseCaseCounter;

    // ---- v4.0: persistenza nel .vpp ----
    /** Il progetto VP a cui appartiene {@link #currentModel}: il modello non viene mai scritto in un altro progetto. */
    private IProject modelProject;
    private Timer persistTimer;
    private boolean persistDirty;
    private String lastPersistError;

    private IProject watchedProject;
    private final CosmicModelChangeListener modelChangeListener = new CosmicModelChangeListener();
    private Timer projectWatcherTimer;
    private Timer diagramScopeWatcherTimer;
    private String lastActiveDiagramId;

    // ---- stato dell'analisi corrente (scritto/letto SOLO sull'EDT, tranne i riferimenti volatile) ----
    private volatile CosmicJsonModel currentModel;
    private volatile CosmicReport lastReport;
    private volatile List<UseCaseChunk> lastChunks = Collections.emptyList();
    /** Ultimo progetto per cui e' stato loggato il percorso del sidecar (per non ripetere la riga a ogni salvataggio). */
    private IProject lastPersistLogged;
    private UseCaseDiagramGenerator activeGenerator;
    private Set<String> knownUseCaseIds = new HashSet<>();

    private volatile CosmicAnalysisListener activeUiListener;   // dialog attualmente mostrato
    private volatile CosmicAnalysisListener runListener;        // dialog che ha avviato l'analisi

    private final List<String[]> chatHistory = new ArrayList<>(); // {ruolo, testo}

    private CosmicAiService() {
        modelChangeListener.setOnModelAdded(this::handleModelElementAdded);
        modelChangeListener.setOnModelRemoved(this::handleModelElementRemoved);
        modelChangeListener.setOnElementPropertyChanged(this::handleElementPropertyChanged);
        modelChangeListener.setOnIncludeChanged(this::handleIncludeChanged);
        modelChangeListener.setOnExtendChanged(this::handleExtendChanged);
        modelChangeListener.setOnProjectAfterOpened(this::handleProjectAfterOpened);
        modelChangeListener.setOnProjectPreSave(this::handleProjectPreSave);
        modelChangeListener.setOnProjectSaved(this::handleProjectSaved);
    }

    // ======================================================================
    // Ciclo di vita
    // ======================================================================

    public void start() {
        ensureExecutors();
        persistTimer = new Timer(PERSIST_DEBOUNCE_MS, e -> flushPersistence());
        persistTimer.setRepeats(false);
        startProjectWatcher();
        startDiagramScopeWatcher();
    }

    public void stop() {
        cancelAnalysis();
        runOnEdt(this::flushPersistence);       // ultimo salvataggio del modello nel progetto
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

    /** Interrompe l'analisi in corso: gli use case gia' elaborati e disegnati restano (e sono esportabili). */
    public void cancelAnalysis() {
        if (!analysisRunning.get()) return;
        cancelRequested.set(true);
        Future<?> f = runningTask;
        if (f != null) f.cancel(true);
    }

    // ======================================================================
    // Watcher di progetto e di diagramma
    // ======================================================================

    private void startProjectWatcher() {
        projectWatcherTimer = new Timer(PROJECT_WATCHER_INTERVAL_MS, e -> {
            IProject currentProject = ApplicationManager.instance().getProjectManager().getProject();
            if (currentProject != watchedProject) {
                detachModelChangeListener();
                if (currentProject != null) {
                    attachModelChangeListener(currentProject);
                    loadPersistedModel(currentProject);        // v4.0: ripopola l'albero dal .vpp
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

    // ======================================================================
    // API pubblica: analisi
    // ======================================================================

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
        Thread.interrupted(); // se il thread era stato interrotto dall'annullamento, non bloccare le notifiche
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

    /** Eccezione interna di annullamento (non e' un errore da mostrare). */
    private static final class AnalysisCancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
        AnalysisCancelled() { super("annullata", null, false, false); }
    }

    private void checkCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) throw new AnalysisCancelled();
    }

    // ----------------------------------------------------------------------
    // Il loop principale (thread di lavoro)
    // ----------------------------------------------------------------------

    private void runPipeline(String text, String projectName, CosmicAnalysisListener ui) throws Exception {
        final long t0 = System.currentTimeMillis();

        // --- Smart chunking: un chunk per use case, con ID/nome originali ---
        UseCaseChunker.ChunkingResult chunking = UseCaseChunker.chunk(text);
        final List<UseCaseChunk> chunks = chunking.chunks;
        if (chunks.isEmpty()) throw new IOException("Nessun use case individuato nel documento.");

        final int total = chunks.size();
        postLog(ui, "Individuati " + total + " use case (strategia: " + chunking.strategy + ").");
        for (String w : chunking.warnings) postLog(ui, "ATTENZIONE: " + w);

        // --- Sessione: modello vuoto + diagrammi (EDT) ---
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
                        // l'LLM risponde ma mai nel formato atteso: conta come use case fallito (le frasi restano, da correggere a mano)
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
                            + "e' mal configurato (LM Studio avviato? modello caricato? context length sufficiente?).";
                    break;
                }
            }
        } catch (AnalysisCancelled | InterruptedException c) {
            Thread.interrupted();
            aborted = true;
            abortReason = "interrotta dall'utente";
        }

        // --- Chiusura (non bloccante: se il thread e' stato interrotto invokeAndWait fallirebbe) ---
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

    // ----------------------------------------------------------------------
    // Misura di un singolo use case: Step 1 (Split) + Step 2 (Measure)
    // ----------------------------------------------------------------------

    private static final class Measured {
        final List<FunctionalProcess> functionalProcesses = new ArrayList<>();
        String error;
        /** true se NESSUNA frase e' stata misurata (formato di risposta mai rispettato). */
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

    /**
     * Step 1 (Split) + Step 2 (Micro-Loop) su uno scenario testuale gia' diviso in parti. Usata sia dall'analisi del
     * documento sia da "Rimisura da VP".
     */
    private void measureScenario(String ucId, String ucName, List<String> parts, String label, int done, int total, String eta,
                                 CosmicAnalysisListener ui, Measured out) throws IOException, InterruptedException {
        List<FunctionalProcess> all = new ArrayList<>();
        int sentences = 0;
        int failed = 0;
        for (int p = 0; p < parts.size(); p++) {
            checkCancelled();
            String suffix = parts.size() > 1 ? " (parte " + (p + 1) + "/" + parts.size() + ")" : "";
            String part = trimNewlines(parts.get(p));

            // STEP 1 - Split (SYSTEM_SPLIT_UC + USER_SPLIT)
            postProgress(ui, done, total, label + " - Step 1/2: split" + suffix + eta);
            String splitRaw = callLlmWithRetry(CosmicPrompts.SYSTEM_SPLIT_UC, CosmicPrompts.userSplit(part));
            String splitText = cleanSplitOutput(splitRaw, part);
            if (splitText == part) {
                postLog(ui, "[" + ucId + "] output dello Split vuoto: uso lo scenario originale per lo Step 2.");
            }

            // STEP 2 - Micro-Loop: una chiamata LLM per frase
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

    /** Pulisce l'output dello Split (fence markdown, eventuale "Split:" iniziale); se vuoto restituisce {@code original} (stesso riferimento). */
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

    // ----------------------------------------------------------------------
    // Sessione di analisi (EDT)
    // ----------------------------------------------------------------------

    private void beginSession(String projectName, List<UseCaseChunk> chunks, CosmicAnalysisListener ui) {
        CosmicJsonModel model = new CosmicJsonModel();
        model.projectName = projectName;
        currentModel = model;
        modelProject = ApplicationManager.instance().getProjectManager().getProject();
        lastChunks = new ArrayList<>(chunks);
        knownUseCaseIds = new HashSet<>();
        for (UseCaseChunk c : chunks) knownUseCaseIds.add(c.id);
        lastReport = new CosmicCalculator().compute(model);
        manualUseCaseCounter = 0;
        synchronized (chatHistory) { chatHistory.clear(); }

        suppressSync.incrementAndGet();
        try {
            activeGenerator = new UseCaseDiagramGenerator();
            activeGenerator.setLogSink(ui::onLog);
            activeGenerator.begin(projectName);
        } finally {
            suppressSync.decrementAndGet();
        }
        ui.onLog("Pipeline a due step (Split + Measure) su " + chunks.size() + " use case, uno alla volta. "
                + "Il diagramma si costruisce man mano.");
    }

    /** Misura -> modello -> diagramma -> CFP -> UI, per UN use case (EDT). */
    private void commitUseCase(UseCaseChunk chunk, Measured measured, int processed, int total, CosmicAnalysisListener ui) {
        CosmicJsonModel model = currentModel;
        if (model == null) return;
        suppressSync.incrementAndGet();
        try {
            // v5.0: l'area funzionale viene dalla gerarchia del documento (Chunker), mai dall'LLM
            FunctionalArea area = model.findOrCreateArea(chunk.functionalAreaRef, chunk.functionalAreaName, chunk.subsystem);
            UseCase uc = buildUseCase(chunk, measured, area);
            // impronta di cio' che "Rimisura da VP" rileggera' dal campo Specification (vedi UseCaseDescription)
            uc.specFingerprint = UseCaseDescription.fingerprint(uc.name,
                    UseCaseDescription.specificationOf(UseCaseDescription.build(uc), uc.id));
            area.addUseCase(uc);

            CosmicCalculator calculator = new CosmicCalculator();
            lastReport = calculator.compute(model);
            calculator.applyViolationFlags(model, lastReport);
            UseCaseReport ucReport = findUseCaseReport(lastReport, uc.id);

            try {
                activeGenerator.appendUseCase(model, uc);                 // rendering incrementale
                VisualFeedbackApplier.applyToUseCase(uc, ucReport);       // <<CFP:N>> / <<Violation>>
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

    private UseCase buildUseCase(UseCaseChunk chunk, Measured measured, FunctionalArea area) {
        UseCase uc = new UseCase();
        uc.id = chunk.id;                       // identita' ORIGINALE dal documento, mai dall'LLM
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

        // Attori: quelli dichiarati nel documento; in assenza, il Functional User del primo Entry misurato.
        List<String> actorNames = new ArrayList<>(chunk.primaryActors);
        if (actorNames.isEmpty()) {
            String fu = firstEntryUser(uc.functionalProcesses.get(0));
            if (fu != null) actorNames.add(fu);
        }
        for (String name : actorNames) {
            Actor actor = findOrCreateActorByName(currentModel, area, name);   // attore PROPRIO dell'area
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

    /**
     * v5.0 - Gli attori sono per AREA: lo stesso nome in due aree funzionali da' due {@link Actor} distinti (ognuno
     * nel package della propria area su VP), con id univoco nel progetto.
     */
    private static Actor findOrCreateActorByName(CosmicJsonModel model, FunctionalArea area, String name) {
        Actor existing = area.findActorByName(name);
        if (existing != null) return existing;
        Actor actor = new Actor();
        actor.id = model.nextActorId();
        actor.name = name.trim();
        actor.description = "";
        // v5.1 - stesso nome in un'altra area: stesso IActor nel database (qui nasce solo il ruolo dell'area).
        actor.vpElementId = model.sharedActorVpId(name);
        area.addActor(actor);
        return actor;
    }

    private static UseCaseReport findUseCaseReport(CosmicReport report, String ucId) {
        return report == null ? null : report.findUseCase(ucId);
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
            if (model != null) for (UseCase uc : model.allUseCases()) if (uc.violation != null) violations++;
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

    // ======================================================================
    // Esportazione CSV
    // ======================================================================

    /**
     * Esporta il report finale. Il file scelto ha lo stesso formato di prima (Use Case ID; Use Case Name; FP ID; FP Name;
     * Entry; Exit; Read; Write; Total CFP; Warning) con in coda Triggering Event, Functional User, Subsystem e (v5.0) Functional Area.
     * Accanto viene scritto {@code <nome>_dettaglio.csv}: la tabella "Measurement" del professore (FU, Sub-process, DG,
     * OOI, DM, CFP per ogni frase) per confrontare con la Ground Truth anche a livello di data movement, e
     * {@code <nome>_aree.csv} (v5.0): totali CFP e numero di use case per area funzionale.
     */
    public void exportToCsv(File file, CosmicAnalysisListener listener) {
        ensureExecutors();
        runOnEdt(() -> {
            if (lastReport == null || currentModel == null || !currentModel.hasUseCases()) {
                listener.onAnalysisFailed(new IllegalStateException("Nessuna analisi da esportare."));
                return;
            }
            final String summary = buildSummaryCsv(currentModel, lastReport);
            final String detail = buildDetailCsv(currentModel);
            final String areas = buildAreaCsv(lastReport);
            final String baseName = file.getName().replaceFirst("(?i)\\.csv$", "");
            final File areaFile = new File(file.getParentFile(), baseName + "_aree.csv");
            final File detailFile = new File(file.getParentFile(), file.getName().replaceFirst("(?i)\\.csv$", "") + "_dettaglio.csv");
            auxExecutor.submit(() -> {
                try {
                    Files.write(file.toPath(), ("﻿" + summary).getBytes(StandardCharsets.UTF_8));
                    Files.write(detailFile.toPath(), ("﻿" + detail).getBytes(StandardCharsets.UTF_8));
                    Files.write(areaFile.toPath(), ("﻿" + areas).getBytes(StandardCharsets.UTF_8));
                    SwingUtilities.invokeLater(() -> listener.onLog("Report esportato in: " + file.getAbsolutePath()
                            + "  (dettaglio per frase: " + detailFile.getName() + "; totali per area: " + areaFile.getName() + ")"));
                } catch (IOException e) {
                    SwingUtilities.invokeLater(() -> listener.onAnalysisFailed(e));
                }
            });
        });
    }

    private static String buildSummaryCsv(CosmicJsonModel model, CosmicReport report) {
        Map<String, UseCase> byId = new LinkedHashMap<>();
        for (UseCase uc : model.allUseCases()) byId.put(uc.id, uc);
        StringBuilder sb = new StringBuilder();
        sb.append("Use Case ID;Use Case Name;FP ID;FP Name;Entry;Exit;Read;Write;Total CFP;Warning;Triggering Event;Functional User;Subsystem;Functional Area\n");
        for (AreaReport area : report.areas) {
            for (UseCaseReport uc : area.useCases) {
                UseCase dto = byId.get(uc.id);
                String subsystem = dto == null || dto.subsystem == null ? "" : dto.subsystem;
                if (uc.functionalProcesses.isEmpty()) {
                    row(sb, uc.id, uc.name, "", "", "0", "0", "0", "0", "0", "Nessun Functional Process misurato", "", "", subsystem, area.label);
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
                            fp.triggeringEvent == null ? "" : fp.triggeringEvent, fu, subsystem, area.label);
                }
            }
        }
        return sb.toString();
    }

    /** v5.0 - Totali CFP per area funzionale (+ riga TOTALE di progetto, somma dei totali di area). */
    private static String buildAreaCsv(CosmicReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Area ID;Area Name;Subsystem;Use Cases;Total CFP\n");
        for (AreaReport a : report.areas) {
            row(sb, a.id, a.name == null ? "" : a.name, a.subsystem == null ? "" : a.subsystem,
                    String.valueOf(a.useCases.size()), String.valueOf(a.totalCfp));
        }
        int ucs = 0;
        for (AreaReport a : report.areas) ucs += a.useCases.size();
        row(sb, "TOTALE", "", "", String.valueOf(ucs), String.valueOf(report.totalCfp));
        return sb.toString();
    }

    private static String buildDetailCsv(CosmicJsonModel model) {
        CosmicCalculator calculator = new CosmicCalculator();
        StringBuilder sb = new StringBuilder();
        sb.append("Use Case ID;Use Case Name;FP ID;Step;Sub-process;FU;DG;OOI;DM;CFP\n");
        for (UseCase uc : model.allUseCases()) {
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

    // ======================================================================
    // Validazione Modello vs Requisiti
    // ======================================================================

    /**
     * Confronta il modello CORRENTE (comprese le modifiche manuali dell'utente in VP) con i requisiti originali,
     * use case per use case. E' un controllo deterministico (nessuna chiamata all'LLM): il documento completo non
     * entra in una finestra di contesto, ma i chunk per use case si'. Il risultato e' consegnato sull'EDT.
     */
    /**
     * I requisiti originali non sono associati al modello (salvataggio precedente alla v4.1, progetto costruito a
     * mano o solo rimisurato da VP). La UI la intercetta e propone di ricollegare il documento, senza rifare la misura.
     */
    public static final class RequirementsMissingException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public RequirementsMissingException() {
            super("Al modello non sono associati i requisiti originali: seleziona il documento dei requisiti per ricollegarlo "
                    + "(non serve rifare la misura; il documento viene salvato nel sidecar per le prossime volte).");
        }
    }

    /**
     * Ricollega il documento dei requisiti a un modello gia' misurato: estrae il testo e lo divide in use case (stesso
     * parser dell'analisi, NESSUNA chiamata all'LLM, quindi pochi secondi), lo salva nel sidecar e, se richiesto,
     * esegue subito la validazione. Il risultato torna sull'EDT.
     */
    public void attachRequirementsDocument(File file, Consumer<String> onResult, Consumer<Throwable> onError) {
        ensureExecutors();
        auxExecutor.submit(() -> {
            try {
                String text = DocumentParserFactory.extractText(file);
                UseCaseChunker.ChunkingResult chunking = UseCaseChunker.chunk(text);
                if (chunking.chunks.isEmpty()) throw new IOException("Nessun use case individuato nel documento.");
                SwingUtilities.invokeLater(() -> {
                    if (currentModel == null) { onError.accept(new IllegalStateException("Nessuna analisi eseguita.")); return; }
                    lastChunks = new ArrayList<>(chunking.chunks);
                    persistDirty = true;
                    flushPersistence();
                    CosmicAnalysisListener ui = uiListener();
                    if (ui != null) ui.onLog("Requisiti ricollegati dal documento '" + file.getName() + "': "
                            + chunking.chunks.size() + " use case (strategia: " + chunking.strategy + ").");
                    validateModelAgainstRequirements(onResult, onError);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        });
    }

    public void validateModelAgainstRequirements(Consumer<String> onResult, Consumer<Throwable> onError) {
        runOnEdt(() -> {
            try {
                CosmicJsonModel model = currentModel;
                List<UseCaseChunk> chunks = lastChunks;
                if (model == null) {
                    onError.accept(new IllegalStateException("Nessuna analisi eseguita: carica un documento e premi Analizza."));
                    return;
                }
                if (chunks.isEmpty()) {
                    onError.accept(new RequirementsMissingException());
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
        for (UseCase uc : model.allUseCases()) modelById.put(uc.id, uc);
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
            for (Actor a : model.allActors()) {
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
        for (UseCase uc : model.allUseCases()) {
            if (!chunkIds.contains(uc.id)) {
                extra.add("[" + uc.id + "] " + uc.name + (uc.id.startsWith("UC-USER-") ? " (aggiunto a mano su VP)" : ""));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== VALIDAZIONE MODELLO vs REQUISITI ===\n");
        sb.append("Use case nei requisiti: ").append(chunks.size()).append(" | nel modello: ").append(model.useCaseCount());
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

    // ======================================================================
    // Chat con contesto del modello
    // ======================================================================

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
        if (m == null || r == null || !m.hasUseCases()) return "Nessuna analisi eseguita: il modello e' vuoto.";
        StringBuilder sb = new StringBuilder();
        sb.append("Progetto: ").append(m.projectName).append(" | use case: ").append(m.useCaseCount())
          .append(" | CFP totali: ").append(r.totalCfp).append("\n");
        Map<String, UseCaseReport> byId = new LinkedHashMap<>();
        for (UseCaseReport ur : r.allUseCases()) byId.put(ur.id, ur);
        for (UseCase uc : m.allUseCases()) {
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
        for (UseCase uc : m.allUseCases()) {
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

    // ======================================================================
    // Sincronizzazione real-time con le modifiche dell'utente su Visual Paradigm
    // ======================================================================

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

    /** Rename in tempo reale (PropertyChangeListener del {@link CosmicModelChangeListener}). */
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
                List<Actor> sharing = currentModel.findActorsByVpId(element.getId());   // v5.1: un IActor, una voce per area
                if (sharing.isEmpty() || newName.equals(sharing.get(0).name)) return;
                String old = sharing.get(0).name;
                for (Actor actor : sharing) actor.name = newName;
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
            // la freccia <<extend>> va dallo use case "estensione" (from) allo use case "base" (to)
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
        if (findUseCaseByVpElementId(vpUseCase) != null) return null;   // es. creato dal generatore: gia' tracciato
        UseCase dto = new UseCase();
        dto.id = nextManualUseCaseId(currentModel);
        dto.name = safeTrim(vpUseCase.getName()).isEmpty() ? dto.id : safeTrim(vpUseCase.getName());
        dto.vpElementId = vpUseCase.getId();
        String activeId = null;
        try {
            IDiagramUIModel active = ApplicationManager.instance().getDiagramManager().getActiveDiagram();
            activeId = active == null ? null : active.getId();
        } catch (Exception ignored) { }
        dto.diagramId = activeId;
        String parentId = null;
        try {
            IModelElement parent = vpUseCase.getParent();
            parentId = parent == null ? null : parent.getId();
        } catch (Exception ignored) { }
        areaForNewUseCase(currentModel, parentId, activeId).addUseCase(dto);
        return dto;
    }

    /**
     * v5.0 - In quale area finisce uno use case apparso su VP senza passare dal generatore: quella il cui PACKAGE lo
     * contiene; altrimenti quella del DIAGRAMMA attivo; altrimenti l'area di default.
     */
    private static FunctionalArea areaForNewUseCase(CosmicJsonModel model, String parentVpId, String activeDiagramId) {
        FunctionalArea a = model.findAreaByContainerId(parentVpId);   // genitore = ISystem (v5.1) o package (v5.0)
        if (a == null) a = model.findAreaByDiagramId(activeDiagramId);
        return a != null ? a : model.defaultArea();
    }

    private boolean removeUseCaseIfPresent(IUseCase vpUseCase) {
        UseCase dto = findUseCaseByVpElementId(vpUseCase);
        if (dto == null) return false;
        currentModel.removeUseCase(dto);      // toglie dall'area e ripulisce include/extend di ogni altra area
        return true;
    }

    /** Associazione Attore <-> Use Case disegnata/cancellata a mano: aggiorna gli attori dello use case. Idempotente. */
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
        Actor actor = findOrCreateActor(vpActor, currentModel.areaOf(dto));
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
        for (UseCase uc : currentModel.allUseCases()) if (id != null && id.equals(uc.vpElementId)) return uc;
        return null;
    }

    private UseCase findUseCaseByVpObject(Object o) {
        return (o instanceof IUseCase) ? findUseCaseByVpElementId((IUseCase) o) : null;
    }

    private Actor findActorByVpElementId(String vpId) {
        if (currentModel == null || vpId == null) return null;
        for (Actor a : currentModel.allActors()) if (vpId.equals(a.vpElementId)) return a;
        return null;
    }

    /** L'attore VP e' gia' tracciato (in qualsiasi area)? Altrimenti se ne crea uno nell'area dello use case. */
    private Actor findOrCreateActor(IActor vpActor, FunctionalArea area) {
        Actor byId = area == null ? findActorByVpElementId(vpActor.getId()) : area.findActorByVpId(vpActor.getId());
        if (byId != null) return byId;
        Actor actor = findOrCreateActorByName(currentModel, area, safeTrim(vpActor.getName()).isEmpty() ? "Actor" : vpActor.getName());
        if (actor.vpElementId == null) actor.vpElementId = vpActor.getId();
        return actor;
    }

    /** Ricalcola i CFP dopo una modifica dell'utente, aggiorna lo stereotipo dello use case coinvolto e la UI. */
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

    /** CFP "nello scope" del diagramma attivo (Epic 3) + totale di progetto, verso la UI. Solo EDT. */
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
        if (id != null) for (UseCase uc : model.allUseCases()) if (id.equals(uc.diagramId)) ids.add(uc.id);
        CosmicReport scoped = new CosmicCalculator().computeScoped(model, ids);
        lastActiveDiagramId = id;
        ui.onScopeChanged(name, scoped, total);
    }

    private static Object endOf(Object relationship, String getter) {
        return VpProjectReader.endOf(relationship, getter);
    }

    private static String safeTrim(String s) { return s == null ? "" : s.trim(); }

    // ======================================================================
    // v4.0 - Persistenza del modello COSMIC nel file .vpp
    // ======================================================================

    /** Segna il modello come modificato: verra' scritto nel progetto tra {@link #PERSIST_DEBOUNCE_MS} ms (EDT). */
    private void markDirty() {
        if (currentModel == null) return;
        persistDirty = true;
        if (persistTimer != null) persistTimer.restart();
    }

    /**
     * Scrive subito modello + requisiti originali nel sidecar .cosmic.json del progetto, se e' cambiato. Solo EDT. Non scrive mai se il progetto
     * attivo non e' quello a cui appartiene il modello (cambio progetto durante l'attesa del debounce).
     */
    private void flushPersistence() {
        if (!persistDirty) return;
        CosmicJsonModel model = currentModel;
        IProject project = modelProject;
        if (model == null || project == null) { persistDirty = false; return; }
        if (ApplicationManager.instance().getProjectManager().getProject() != project) return;
        suppressSync.incrementAndGet();
        try {
            // requisiti originali inclusi: servono a "Valida Modello vs Requisiti" anche dopo un riavvio di VP
            ProjectModelStore.save(project, CosmicModelSerializer.toJson(model, lastChunks));
            persistDirty = false;
            lastPersistError = null;
            if (!project.equals(lastPersistLogged)) {     // una sola riga per progetto: dove si trova il file
                lastPersistLogged = project;
                CosmicAnalysisListener ui = uiListener();
                String where = ProjectModelStore.locationOf(project);
                if (ui != null && where != null) ui.onLog("Modello COSMIC e requisiti salvati in: " + where);
            }
        } catch (ProjectModelStore.StoreException | RuntimeException e) {
            persistDirty = false;      // niente tempesta di tentativi: si riprova alla prossima modifica (o al salvataggio del .vpp)
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (!msg.equals(lastPersistError)) {          // un solo avviso per errore, non uno per ogni salvataggio
                lastPersistError = msg;
                CosmicAnalysisListener ui = uiListener();
                String text = (e instanceof ProjectModelStore.NotSavedException ? "Nota: " : "ATTENZIONE: modello COSMIC NON salvato: ") + msg;
                if (ui != null) ui.onLog(text); else System.out.println("[COSMIC AI] " + text);
            }
        } finally {
            suppressSync.decrementAndGet();
        }
    }

    /** Subito prima del salvataggio del .vpp: il file deve contenere il modello COSMIC aggiornato. */
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

    /**
     * Dopo il salvataggio del .vpp: ora il file esiste (e ha il suo percorso definitivo anche con "Salva con nome"),
     * quindi si riscrive il sidecar. E' quello che rende persistente un progetto salvato per la prima volta DOPO l'analisi.
     */
    private void handleProjectSaved(IProject project) {
        SwingUtilities.invokeLater(() -> {
            if (project != modelProject || currentModel == null) return;
            lastPersistLogged = null;          // il percorso puo' essere cambiato: si rilogga la destinazione
            persistDirty = true;
            flushPersistence();
        });
    }

    private void handleProjectAfterOpened(IProject project) {
        SwingUtilities.invokeLater(() -> loadPersistedModel(project));
    }

    /**
     * Ripopola il modello dal progetto (se contiene un JSON COSMIC), lo riconcilia con quello che c'e' davvero sul
     * canvas e lo pubblica sulla UI. Se il progetto non ha un modello salvato e prima era attivo quello di un altro
     * progetto, lo stato viene azzerato (mai mescolare due progetti). Solo EDT; idempotente.
     */
    private void loadPersistedModel(IProject project) {
        if (project == null || analysisRunning.get()) return;
        if (modelProject == project && currentModel != null) return;     // il modello in memoria e' gia' quello di questo progetto
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
                lastChunks = Collections.emptyList();
                knownUseCaseIds = new HashSet<>();
                activeGenerator = null;
                persistDirty = false;
                modelProject = project;
                if (hadModel && ui != null) ui.onModelCleared();
            }
            return;
        }

        CosmicJsonModel model;
        List<UseCaseChunk> restoredChunks;
        try {
            model = CosmicModelSerializer.fromJson(json);
            restoredChunks = CosmicModelSerializer.chunksFromJson(json);   // requisiti originali (vuoti nei file v1)
        } catch (RuntimeException e) {
            String text = "Modello COSMIC salvato nel progetto non leggibile (" + e.getMessage() + "): ignorato.";
            if (ui != null) ui.onLog(text); else System.out.println("[COSMIC AI] " + text);
            return;
        }

        List<String> notes = new ArrayList<>();
        boolean changed = syncModelWithVp(project, model, notes);

        currentModel = model;
        modelProject = project;
        lastChunks = restoredChunks;
        activeGenerator = null;
        knownUseCaseIds = new HashSet<>();
        for (UseCase uc : model.allUseCases()) knownUseCaseIds.add(uc.id);
        manualUseCaseCounter = 0;
        CosmicCalculator calculator = new CosmicCalculator();
        lastReport = calculator.compute(model);
        calculator.applyViolationFlags(model, lastReport);
        synchronized (chatHistory) { chatHistory.clear(); }
        if (changed) markDirty();

        if (ui != null) {
            ui.onLog("Modello COSMIC ripristinato dal progetto: " + model.useCaseCount() + " use case, "
                    + lastReport.totalCfp + " CFP totali, "
                    + (restoredChunks.isEmpty() ? "nessun requisito originale salvato (validazione: ricollega il documento)."
                                                : restoredChunks.size() + " requisiti originali per la validazione."));
            if (model.migratedFromFlatSchema) {
                ui.onLog("  NOTA: salvataggio di una versione precedente (use case senza aree funzionali): gli use case sono stati "
                        + "raggruppati per sottosistema. Riesegui l'analisi dal documento per ottenere le aree funzionali vere.");
            }
            for (String n : notes) ui.onLog("  riconciliazione: " + n);
            ui.onAnalysisCompleted(model, lastReport);
        }
        publishCurrentScope();
    }

    /** Chiamato dal dialog quando viene mostrato: se esiste gia' un modello (es. ripristinato dal .vpp) lo mostra subito. */
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

    // ----------------------------------------------------------------------
    // Riconciliazione modello <-> progetto VP (Source of Truth = VP)
    // ----------------------------------------------------------------------

    /**
     * Allinea {@code model} a quello che c'e' davvero sul canvas di VP:
     * <ul>
     *   <li>use case cancellati su VP (anche mentre il plugin era chiuso) -> rimossi dal modello, con le loro relazioni;</li>
     *   <li>use case rinominati -> nome aggiornato; use case nuovi su VP -> aggiunti, NON misurati (si misurano con "Rimisura da VP");</li>
     *   <li>relazioni &lt;&lt;include&gt;&gt;/&lt;&lt;extend&gt;&gt; e associazioni attore-use case -> ricostruite da VP.</li>
     * </ul>
     * Salvaguardie contro un'enumerazione fallita (che cancellerebbe dati veri): se nessuno degli use case salvati e'
     * presente su VP non si rimuove nulla; se VP non restituisce alcuna relazione di un tipo, quelle del modello si conservano.
     *
     * @return true se il modello e' cambiato
     */
    private boolean syncModelWithVp(IProject project, CosmicJsonModel model, List<String> notes) {
        boolean changed = false;
        VpProjectReader.Snapshot snap = VpProjectReader.read(project);
        Map<String, VpProjectReader.UseCaseSnap> vpById = new LinkedHashMap<>();
        for (VpProjectReader.UseCaseSnap u : snap.useCases) vpById.put(u.vpId, u);

        // 1) use case cancellati su VP
        List<UseCase> gone = new ArrayList<>();
        int tracked = 0;
        for (UseCase uc : model.allUseCases()) {
            if (uc.vpElementId == null) continue;
            tracked++;
            if (vpById.containsKey(uc.vpElementId)) continue;
            IModelElement el = project.getModelElementById(uc.vpElementId);     // doppio controllo, indipendente dall'enumerazione
            if (!(el instanceof IUseCase)) gone.add(uc);
        }
        if (tracked > 0 && gone.size() == tracked) {
            notes.add("nessuno degli use case salvati e' presente nel progetto (progetto non ancora caricato del tutto, o modello "
                    + "appartenente a un altro file): non rimuovo nulla.");
            return false;
        }
        for (UseCase uc : gone) {
            model.removeUseCase(uc);                 // dall'area + include/extend di ogni altra area
            changed = true;
        }
        if (!gone.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (UseCase uc : gone) ids.append(ids.length() == 0 ? "" : ", ").append(uc.id);
            notes.add(gone.size() + " use case non piu' presenti sul canvas, rimossi dal modello: " + ids);
        }

        // 2) rinomine e use case nuovi
        Map<String, UseCase> byVp = new LinkedHashMap<>();
        for (UseCase uc : model.allUseCases()) if (uc.vpElementId != null) byVp.put(uc.vpElementId, uc);
        int added = 0;
        for (VpProjectReader.UseCaseSnap u : snap.useCases) {
            UseCase uc = byVp.get(u.vpId);
            if (uc == null) {
                uc = new UseCase();
                uc.id = nextManualUseCaseId(model);
                uc.vpElementId = u.vpId;
                uc.name = u.name.isEmpty() ? uc.id : u.name;
                areaForNewUseCase(model, u.parentVpId, null).addUseCase(uc);
                byVp.put(u.vpId, uc);
                added++;
                changed = true;
            } else if (!u.name.isEmpty() && !u.name.equals(uc.name)) {
                applyRename(uc, u.name);
                changed = true;
            }
        }
        if (added > 0) notes.add(added + " use case presenti su VP ma non nel modello: aggiunti senza misura (usa 'Rimisura da VP').");

        // 3) relazioni
        changed |= rebuildRelations(model, snap, byVp, notes);

        // 4) attori eliminati da VP e non piu' referenziati
        Set<String> referenced = new HashSet<>();
        for (UseCase uc : model.allUseCases()) {
            if (uc.primaryActorId != null) referenced.add(uc.primaryActorId);
            referenced.addAll(uc.coActorIds);
        }
        Set<String> vpActorIds = new HashSet<>();
        for (VpProjectReader.ActorSnap a : snap.actors) vpActorIds.add(a.vpId);
        if (!vpActorIds.isEmpty()) {
            changed |= model.removeActorsIf(a -> a.vpElementId != null && !vpActorIds.contains(a.vpElementId) && !referenced.contains(a.id));
        }
        return changed;
    }

    private boolean rebuildRelations(CosmicJsonModel model, VpProjectReader.Snapshot snap, Map<String, UseCase> byVp, List<String> notes) {
        boolean changed = false;

        // <<include>>: from = use case base, to = use case incluso
        boolean modelHasIncludes = false;
        boolean modelHasExtends = false;
        for (UseCase uc : model.allUseCases()) {
            if (!uc.includesIds.isEmpty()) modelHasIncludes = true;
            if (!uc.extendsList.isEmpty()) modelHasExtends = true;
        }
        if (!snap.includes.isEmpty() || !modelHasIncludes) {
            for (UseCase uc : model.allUseCases()) {
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

        // <<extend>>: from = use case estensione, to = use case base
        if (!snap.extendsLinks.isEmpty() || !modelHasExtends) {
            for (UseCase uc : model.allUseCases()) {
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

        // associazioni attore - use case
        boolean modelHasActors = false;
        for (UseCase uc : model.allUseCases()) if (uc.primaryActorId != null) modelHasActors = true;
        if (!snap.associations.isEmpty() || !modelHasActors) {
            for (UseCase uc : model.allUseCases()) {
                if (uc.vpElementId == null) continue;
                List<String> actorIds = new ArrayList<>();
                for (VpProjectReader.LinkSnap l : snap.associations) {
                    if (!uc.vpElementId.equals(l.toVpId)) continue;
                    Actor a = resolveActor(model, snap, model.areaOf(uc), l.fromVpId);
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

    private static Actor resolveActor(CosmicJsonModel model, VpProjectReader.Snapshot snap, FunctionalArea area, String actorVpId) {
        Actor inArea = area == null ? model.findActorByVpId(actorVpId) : area.findActorByVpId(actorVpId);   // v5.1: l'IActor puo' essere condiviso tra aree
        if (inArea != null) return inArea;
        for (VpProjectReader.ActorSnap s : snap.actors) {
            if (!s.vpId.equals(actorVpId)) continue;
            Actor a = findOrCreateActorByName(model, area, s.name.isEmpty() ? "Actor" : s.name);
            if (a.vpElementId == null) a.vpElementId = actorVpId;
            return a;
        }
        return null;
    }

    private String nextManualUseCaseId(CosmicJsonModel model) {
        Set<String> ids = new HashSet<>();
        for (UseCase uc : model.allUseCases()) ids.add(uc.id);
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
        for (UseCase uc : model.allUseCases())
            for (FunctionalProcess fp : uc.functionalProcesses)
                for (SubProcess sp : fp.subProcesses) if (sp.unmeasured) n++;
        return n;
    }

    private static boolean hasAnySubProcess(UseCase uc) {
        for (FunctionalProcess fp : uc.functionalProcesses) if (!fp.subProcesses.isEmpty()) return true;
        return false;
    }

    // ======================================================================
    // v4.0 - Correzione manuale dei Data Movement (human-in-the-loop)
    // ======================================================================

    /** Campo di un sotto-processo modificabile dall'utente. */
    public enum ManualField { FUNCTIONAL_USER, DATA_MOVEMENT, DATA_GROUP, OBJECT_OF_INTEREST }

    /**
     * Applica la correzione di UNA cella della tabella dei data movement, ricalcola SUBITO i CFP (nessuna chiamata
     * all'LLM), aggiorna stereotipi {@code <<CFP:N>>}/{@code <<Violation>>} e descrizione dello use case su VP,
     * notifica la UI e programma il salvataggio nel .vpp. Solo EDT. Rifiutata mentre un'analisi e' in corso.
     *
     * @param value per {@link ManualField#DATA_MOVEMENT}: Entry/Exit/Read/Write/E/X/R/W oppure "No data movement"/vuoto
     * @return true se la modifica e' stata applicata (false: riga non trovata, valore non valido o nessun cambiamento)
     */
    public boolean applyManualEdit(String ucId, String fpId, int step, ManualField field, String value) {
        CosmicJsonModel model = currentModel;
        if (model == null || analysisRunning.get()) return false;
        UseCase uc = null;
        for (UseCase u : model.allUseCases()) if (u.id != null && u.id.equals(ucId)) uc = u;
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

    // ======================================================================
    // v4.0 - "Rimisura da VP": Visual Paradigm come Source of Truth
    // ======================================================================

    /** Uno use case letto da VP, con quello che serve per decidere se rimisurarlo. */
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

    /**
     * Rilegge dal canvas di VP nome e testo (campo Specification/Description) di OGNI use case, li invia alla pipeline
     * Split + Micro-Loop e ricalcola l'albero; poi riallinea relazioni &lt;&lt;include&gt;&gt;/&lt;&lt;extend&gt;&gt;, attori ed
     * eventuali use case cancellati con lo stato reale di VP.
     *
     * @param onlyChanged true: rimisura solo use case nuovi, con testo/nome modificato dall'ultima misura o con frasi non
     *                    misurate; gli altri conservano la misura (comprese le correzioni manuali). false: rimisura tutto
     *                    (le correzioni manuali vengono sovrascritte).
     */
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

        // --- Fase 1 (EDT): fotografia di VP e piano di lavoro ---
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
                    abortReason = MAX_CONSECUTIVE_FAILURES + " use case consecutivi non misurabili: l'LLM non risponde o e' mal configurato "
                            + "(controlla la tab Impostazioni e che il server sia avviato).";
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

    /** EDT: legge VP e prepara gli use case (nome aggiornato, nuovi use case creati nel modello). */
    private void planRemeasure(boolean onlyChanged, List<RemeasureItem> items, CosmicAnalysisListener ui) {
        IProject project = ApplicationManager.instance().getProjectManager().getProject();
        if (project == null) throw new IllegalStateException("Nessun progetto aperto in Visual Paradigm.");
        VpProjectReader.Snapshot snap = VpProjectReader.read(project);
        if (snap.useCases.isEmpty()) {
            throw new IllegalStateException("Il progetto non contiene use case (oppure l'Open API non ha permesso di enumerarli).");
        }
        CosmicJsonModel model = currentModel;
        if (model == null || modelProject != project) {
            model = new CosmicJsonModel();
            model.projectName = "Progetto Visual Paradigm";
            currentModel = model;
            modelProject = project;
            lastChunks = Collections.emptyList();
            knownUseCaseIds = new HashSet<>();
            manualUseCaseCounter = 0;
        }
        Map<String, UseCase> byVp = new LinkedHashMap<>();
        for (UseCase uc : model.allUseCases()) if (uc.vpElementId != null) byVp.put(uc.vpElementId, uc);

        for (VpProjectReader.UseCaseSnap u : snap.useCases) {
            UseCase uc = byVp.get(u.vpId);
            if (uc == null) {
                uc = new UseCase();
                uc.id = nextManualUseCaseId(model);
                uc.vpElementId = u.vpId;
                uc.name = u.name.isEmpty() ? uc.id : u.name;
                String activeId = null;
                try {
                    IDiagramUIModel active = ApplicationManager.instance().getDiagramManager().getActiveDiagram();
                    activeId = active == null ? null : active.getId();
                } catch (Exception ignored) { }
                uc.diagramId = activeId;
                areaForNewUseCase(model, u.parentVpId, activeId).addUseCase(uc);
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

    /** EDT: sostituisce la misura di UN use case, ricalcola, aggiorna VP e l'albero (upsert del nodo). */
    private void commitRemeasured(RemeasureItem item, Measured measured, int done, int total, CosmicAnalysisListener ui) {
        CosmicJsonModel model = currentModel;
        if (model == null) return;
        UseCase uc = null;
        for (UseCase u : model.allUseCases()) if (item.ucId.equals(u.id)) uc = u;
        if (uc == null) return;                       // cancellato su VP durante la misura
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
                    if (fu != null) uc.primaryActorId = findOrCreateActorByName(model, model.areaOf(uc), fu).id;
                }
            }

            CosmicCalculator calculator = new CosmicCalculator();
            lastReport = calculator.compute(model);
            calculator.applyViolationFlags(model, lastReport);
            UseCaseReport ucReport = findUseCaseReport(lastReport, uc.id);
            try {
                VisualFeedbackApplier.applyToUseCase(uc, ucReport);     // <<CFP:N>> / <<Violation>> + blocco COSMIC nella descrizione
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

    /** EDT: riallinea relazioni/attori/use case cancellati con lo stato ATTUALE di VP (l'utente puo' aver lavorato durante la misura). */
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
                for (UseCase uc : model.allUseCases()) {
                    try {
                        VisualFeedbackApplier.applyToUseCase(uc, findUseCaseReport(lastReport, uc.id));
                    } catch (Exception ignored) { /* use case senza elemento su VP */ }
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

    /** Divide su confini di riga uno scenario piu' lungo di {@code max} caratteri (stesso use case, piu' parti). */
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

    // ======================================================================
    // Chiamate all'LLM (LM Studio, API OpenAI-compatibile)
    // ======================================================================

    private static final class LlmHttpException extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        LlmHttpException(int status, String body) {
            super("HTTP " + status + (body == null || body.isEmpty() ? "" : ": " + abbreviate(body, 300)));
            this.status = status;
        }
    }

    /** Configurazione non valida (es. URL malformato): errore non ritentabile. */
    private static final class LlmConfigException extends IOException {
        private static final long serialVersionUID = 1L;
        LlmConfigException(String message, Throwable cause) { super(message, cause); }
    }

    private static String httpStatusHint(int status) {
        switch (status) {
            case 400: return " - probabile 'context length' troppo piccolo in LM Studio (servono almeno 16384 token) oppure nome del modello errato (tab Impostazioni).";
            case 401: case 403: return " - API key mancante o non valida (tab Impostazioni).";
            case 404: return " - endpoint non trovato: controlla l'URL nella tab Impostazioni (di norma termina con /v1/chat/completions).";
            default: return "";
        }
    }

    private static String abbreviate(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    /** Come lo script del professore: ritenta con backoff esponenziale (5s, 10s, ...), ma con un numero massimo di tentativi. */
    private String callLlmWithRetry(String systemPrompt, String userText) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= LLM_MAX_ATTEMPTS; attempt++) {
            checkCancelled();
            try {
                return extractAssistantContent(callLlm(systemPrompt, userText));
            } catch (LlmConfigException e) {
                throw e;    // URL non valido: riprovare non serve
            } catch (LlmHttpException e) {
                boolean retriable = e.status >= 500 || e.status == 408 || e.status == 429;
                if (!retriable) {
                    throw new IOException(e.getMessage() + httpStatusHint(e.status), e);
                }
                last = e;
            } catch (IOException e) {
                last = e;   // connessione rifiutata, timeout, risposta vuota...
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
        // Le impostazioni si rileggono a ogni richiesta: una modifica nella tab "Impostazioni" vale dalla chiamata successiva.
        CosmicSettings.Snapshot cfg = CosmicSettings.load();
        String requestBody = buildChatCompletionRequestJson(cfg.modelName, messages);
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder().uri(URI.create(cfg.endpointUrl));
        } catch (IllegalArgumentException e) {
            throw new LlmConfigException("URL dell'LLM non valido ('" + cfg.endpointUrl + "'): correggilo nella tab Impostazioni.", e);
        }
        builder.timeout(Duration.ofSeconds(cfg.timeoutSec))
               .header("Content-Type", "application/json")
               .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (cfg.hasApiKey()) {
            builder.header("Authorization", "Bearer " + cfg.apiKey.trim());     // mai scritta nei log
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
            throw new IOException("risposta vuota dall'LLM (finish_reason=" + finish + "): il ragionamento del modello potrebbe aver esaurito il contesto");
        }
        return text;
    }

    /** Alcune build di LM Studio lasciano nel testo i marcatori "harmony" di gpt-oss o i blocchi di ragionamento. */
    private static String stripReasoningArtifacts(String text) {
        String t = text;
        int ch = t.lastIndexOf("<|channel|>final<|message|>");
        if (ch >= 0) t = t.substring(ch + "<|channel|>final<|message|>".length());
        t = t.replaceAll("<\\|(?:return|end|start)\\|>", "");
        t = t.replaceAll("(?s)<think>.*?</think>", "");
        return t.trim();
    }

    private static String stripMarkdownFences(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstNewline = trimmed.indexOf('\n');
        String withoutOpeningFence = (firstNewline >= 0) ? trimmed.substring(firstNewline + 1) : trimmed;
        int closingFenceIndex = withoutOpeningFence.lastIndexOf("```");
        return (closingFenceIndex >= 0) ? withoutOpeningFence.substring(0, closingFenceIndex).trim() : withoutOpeningFence.trim();
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); // caratteri di controllo invisibili di Word
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ======================================================================
    // Utilita' di threading
    // ======================================================================

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeLater(r);
    }

    /** Esegue sull'EDT e attende: back-pressure naturale (il prossimo use case parte dopo il disegno del precedente). */
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
