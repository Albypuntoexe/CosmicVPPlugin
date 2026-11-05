package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.calculator.CosmicCalculator;
import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.FunctionalProcessReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.document.DocumentParserFactory;
import com.cosmic.vpplugin.document.UseCaseChunk;
import com.cosmic.vpplugin.document.UseCaseChunker;
import com.cosmic.vpplugin.generator.UseCaseDiagramGenerator;
import com.cosmic.vpplugin.generator.VisualFeedbackApplier;
import com.cosmic.vpplugin.json.MiniJsonParser;
import com.cosmic.vpplugin.listener.CosmicModelChangeListener;
import com.cosmic.vpplugin.model.CosmicJsonMapper;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestratore del plugin COSMIC AI (v3.0).
 *
 * <h3>Pipeline (replica dello script del professore, functions.py / prompt.py)</h3>
 * Per OGNI use case del documento, in loop e uno alla volta:
 * <ol>
 *   <li><b>Step 1 - Split</b>: SYSTEM_SPLIT_UC + {@code "<scenario>\n\nSplit:"} (frasi atomiche numerate);</li>
 *   <li><b>Step 2 - Measure</b>: SYSTEM_COSMIC_MEASURE + {@code FP "<nome>":\n<output step 1>\n\nFP: "<nome>" measurement:}
 *       (Data Movement E/X/R/W). Unica differenza rispetto a Python: l'LLM risponde in JSON
 *       ({@link CosmicPrompts#JSON_OUTPUT_ADDENDUM}) invece che in testo, cosi' Java lo legge con
 *       {@link CosmicJsonMapper}; se il modello risponde comunque nel formato testuale CosMet, viene riconosciuto
 *       dal parser di riserva.</li>
 * </ol>
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

    // ---- configurazione (sovrascrivibile con -Dcosmic.llm.url=... ecc. nel file .vmoptions di VP) ----
    private static final String LLM_ENDPOINT_URL = System.getProperty("cosmic.llm.url", "http://127.0.0.1:1234/v1/chat/completions");
    private static final String LLM_MODEL = System.getProperty("cosmic.llm.model", "openai/gpt-oss-20b");
    /** Un modello locale da 20B con prompt few-shot da ~3k token + ragionamento puo' impiegare minuti per chiamata. */
    private static final Duration LLM_REQUEST_TIMEOUT = Duration.ofSeconds(Long.getLong("cosmic.llm.timeoutSec", 900L));
    private static final int LLM_MAX_ATTEMPTS = 3;
    private static final long LLM_RETRY_BASE_MS = 5_000L;   // come lo script del professore: 5s, 10s, ...
    /** Rete di sicurezza: uno scenario piu' lungo viene diviso (stesso use case). Albergate: max ~2.700. */
    private static final int MAX_CHUNK_CHARS = 6000;
    /** Dopo N use case consecutivi falliti (LM Studio spento?) l'analisi si interrompe con un messaggio chiaro. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final int PROJECT_WATCHER_INTERVAL_MS = 1500;
    private static final int DIAGRAM_SCOPE_WATCHER_INTERVAL_MS = 800;
    private static final int CHAT_HISTORY_MESSAGES = 16;

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

    private IProject watchedProject;
    private final CosmicModelChangeListener modelChangeListener = new CosmicModelChangeListener();
    private Timer projectWatcherTimer;
    private Timer diagramScopeWatcherTimer;
    private String lastActiveDiagramId;

    // ---- stato dell'analisi corrente (scritto/letto SOLO sull'EDT, tranne i riferimenti volatile) ----
    private volatile CosmicJsonModel currentModel;
    private volatile CosmicReport lastReport;
    private volatile List<UseCaseChunk> lastChunks = Collections.emptyList();
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
    }

    // ======================================================================
    // Ciclo di vita
    // ======================================================================

    public void start() {
        ensureExecutors();
        startProjectWatcher();
        startDiagramScopeWatcher();
    }

    public void stop() {
        cancelAnalysis();
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
                if (currentProject != null) { attachModelChangeListener(currentProject); }
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
        if (!beginRun(listener)) return;
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
        if (!beginRun(listener)) return;
        runningTask = pipelineExecutor.submit(() -> {
            try {
                runPipeline(rawRequirementsText, projectName, listener);
            } catch (Throwable ex) {
                failRun(listener, ex);
            }
        });
    }

    private boolean beginRun(CosmicAnalysisListener listener) {
        if (!analysisRunning.compareAndSet(false, true)) {
            listener.onAnalysisFailed(new IllegalStateException("Un'analisi e' gia' in corso."));
            return false;
        }
        ensureExecutors();
        cancelRequested.set(false);
        runListener = listener;
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
                    consecutiveFailures = 0;
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
    }

    private void measureChunk(UseCaseChunk chunk, String label, int done, int total, String eta,
                              CosmicAnalysisListener ui, Measured out) throws IOException, InterruptedException {
        List<String> parts = chunk.toParts(MAX_CHUNK_CHARS);
        if (parts.size() > 1) {
            postLog(ui, "[" + chunk.id + "] scenario lungo: diviso in " + parts.size() + " parti (stesso use case).");
        }
        List<FunctionalProcess> all = new ArrayList<>();
        for (int p = 0; p < parts.size(); p++) {
            checkCancelled();
            String suffix = parts.size() > 1 ? " (parte " + (p + 1) + "/" + parts.size() + ")" : "";
            String part = trimNewlines(parts.get(p));

            // STEP 1 - Split (SYSTEM_SPLIT_UC + USER_SPLIT)
            postProgress(ui, done, total, label + " - Step 1/2: split" + suffix + eta);
            String splitRaw = callLlmWithRetry(CosmicPrompts.SYSTEM_SPLIT_UC, CosmicPrompts.userSplit(part));
            String splitText = cleanSplitOutput(splitRaw, part);
            if (splitText == part) {
                postLog(ui, "[" + chunk.id + "] output dello Split vuoto: uso lo scenario originale per lo Step 2.");
            }

            // STEP 2 - Measure (SYSTEM_COSMIC_MEASURE + USER_COSMIC_MEASURE, risposta in JSON)
            postProgress(ui, done, total, label + " - Step 2/2: measure" + suffix + eta);
            all.addAll(measureSplit(chunk, splitText, ui));
        }
        if (parts.size() > 1 && all.size() > 1) {
            all = Collections.singletonList(mergeFunctionalProcesses(all));
        }
        out.functionalProcesses.addAll(all);
    }

    private List<FunctionalProcess> measureSplit(UseCaseChunk chunk, String splitText, CosmicAnalysisListener ui)
            throws IOException, InterruptedException {
        String system = CosmicPrompts.systemCosmicMeasureJson();
        String user = CosmicPrompts.userMeasure(chunk.name, splitText);
        // A temperature 0 un secondo tentativo con lo STESSO prompt darebbe la stessa risposta: il secondo
        // tentativo usa quindi un promemoria di formato piu' severo.
        for (int attempt = 0; attempt < 2; attempt++) {
            checkCancelled();
            String reminder = attempt == 0 ? CosmicPrompts.USER_JSON_SUFFIX : CosmicPrompts.USER_JSON_SUFFIX_STRICT;
            String raw = callLlmWithRetry(system, user + reminder);
            List<FunctionalProcess> fps = parseMeasurement(raw);
            if (!fps.isEmpty()) return fps;
            postLog(ui, "[" + chunk.id + "] risposta dello Step 2 non interpretabile"
                    + (attempt == 0 ? ": nuovo tentativo con promemoria di formato." : "."));
        }
        throw new IOException("risposta dell'LLM non interpretabile (ne' JSON ne' formato testuale CosMet)");
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
    // Interpretazione della risposta dello Step 2
    // ----------------------------------------------------------------------

    /** JSON (formato richiesto) oppure, in subordine, il report testuale CosMet del professore. */
    List<FunctionalProcess> parseMeasurement(String raw) {
        String content = stripMarkdownFences(raw).trim();
        List<FunctionalProcess> fps = parseJsonMeasurement(content);
        if (fps.isEmpty()) fps = parseTextMeasurement(content);
        List<FunctionalProcess> valid = new ArrayList<>();
        for (FunctionalProcess fp : fps) {
            normalizeFunctionalProcess(fp);
            if (!fp.subProcesses.isEmpty()) valid.add(fp);
        }
        return valid;
    }

    private List<FunctionalProcess> parseJsonMeasurement(String content) {
        for (String candidate : jsonObjectCandidates(content)) {
            String wrapped;
            if (candidate.contains("\"useCases\"")) wrapped = candidate;
            else if (candidate.contains("\"functionalProcesses\"")) wrapped = "{\"useCases\":[" + candidate + "]}";
            else if (candidate.contains("\"subProcesses\"")) wrapped = "{\"useCases\":[{\"functionalProcesses\":[" + candidate + "]}]}";
            else continue;
            for (String text : new String[]{wrapped, wrapped.replaceAll(",\\s*([}\\]])", "$1")}) {   // 2o giro: virgole finali
                try {
                    CosmicJsonModel m = CosmicJsonMapper.map(text);
                    List<FunctionalProcess> fps = new ArrayList<>();
                    for (UseCase uc : m.useCases) fps.addAll(uc.functionalProcesses);
                    if (!fps.isEmpty()) return fps;
                } catch (RuntimeException ignored) { /* prova la variante successiva */ }
            }
        }
        return new ArrayList<>();
    }

    /** Oggetti JSON bilanciati presenti nel testo (rispetta le stringhe), dal piu' esterno al piu' interno. */
    private static List<String> jsonObjectCandidates(String s) {
        List<String> out = new ArrayList<>();
        for (int start = s.indexOf('{'); start >= 0 && out.size() < 5; start = s.indexOf('{', start + 1)) {
            int depth = 0;
            boolean inString = false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inString) {
                    if (c == '\\') i++;
                    else if (c == '"') inString = false;
                } else if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}' && --depth == 0) {
                    String obj = s.substring(start, i + 1);
                    if (obj.contains("subProcesses") || obj.contains("functionalProcesses") || obj.contains("useCases")) out.add(obj);
                    break;
                }
            }
        }
        return out;
    }

    // --- porting di parseGPTResponse() di functions.py: il report testuale CosMet ---

    private static final Pattern DM_LINE = Pattern.compile(
            "(?i)^[-*\\s]*(?:FU\\s*\\((.*?)\\)\\s*[-–—]\\s*)?DG\\s*\\((.*?)\\)\\s*[-–—]\\s*OOI\\s*\\((.*?)\\)\\s*[-–—]\\s*(Entry|Exit|Read|Write|Delete|E|X|R|W)\\b.*$");
    private static final Pattern NO_DM_LINE = Pattern.compile("(?i)^[-*\\s]*No data movement.*$");
    private static final Pattern SECTION_LINE = Pattern.compile(
            "(?i)^(?:main scenario|basic flow|alternat(?:ive|e) (?:flows?|scenarios?).*|exceptions?)\\s*:?$");

    private List<FunctionalProcess> parseTextMeasurement(String text) {
        FunctionalProcess fp = new FunctionalProcess();
        fp.triggeringEvent = "";
        SubProcess last = null;
        int movementLines = 0;   // senza nemmeno una riga DG/OOI/DM la risposta non e' un report CosMet (es. testo libero)
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.equals("###")) continue;
            String lower = line.toLowerCase();
            if (lower.startsWith("triggering event:")) { fp.triggeringEvent = line.substring(line.indexOf(':') + 1).trim(); continue; }
            if (lower.startsWith("functional user:") || lower.startsWith("sub-processes:")) continue;
            if (lower.startsWith("fp") && lower.endsWith("measurement:")) continue;
            Matcher dm = DM_LINE.matcher(line);
            if (dm.matches()) {
                movementLines++;
                if (last != null) {
                    last.functionalUser = dm.group(1);
                    last.dataGroup = dm.group(2);
                    last.objectOfInterest = dm.group(3);
                    last.dataMovementType = dm.group(4);
                }
                continue;
            }
            if (NO_DM_LINE.matcher(line).matches() || SECTION_LINE.matcher(line).matches()) continue;
            last = new SubProcess();
            last.description = sentenceOf(line);
            fp.subProcesses.add(last);
        }
        List<FunctionalProcess> out = new ArrayList<>();
        if (!fp.subProcesses.isEmpty() && movementLines > 0) out.add(fp);
        return out;
    }

    private static String sentenceOf(String line) {
        int tab = line.lastIndexOf('\t');
        if (tab >= 0) return line.substring(tab + 1).trim();
        return line.replaceFirst("^\\d+(?:\\.?[A-Za-z]\\d*)?[.)]?\\s+", "").trim();
    }

    private void normalizeFunctionalProcess(FunctionalProcess fp) {
        if (fp.triggeringEvent == null) fp.triggeringEvent = "";
        for (SubProcess sp : fp.subProcesses) {
            sp.description = sp.description == null ? "" : sp.description.trim();
            sp.dataMovementType = normalizeMovement(sp.dataMovementType);
            sp.dataGroup = nullIfBlank(sp.dataGroup);
            sp.objectOfInterest = nullIfBlank(sp.objectOfInterest);
            sp.functionalUser = nullIfBlank(sp.functionalUser);
        }
        renumber(fp);
    }

    private static String nullIfBlank(String s) {
        if (s == null) return null;
        String t = s.trim();
        return (t.isEmpty() || t.equalsIgnoreCase("null") || t.equalsIgnoreCase("n/a") || t.equals("-")) ? null : t;
    }

    /** Entry/Exit/Read/Write (o sigle) -> "E"/"X"/"R"/"W"; "nessun movimento" -> null; altro resta com'e' (il calcolatore lo segnala). */
    private static String normalizeMovement(String dm) {
        String t = nullIfBlank(dm);
        if (t == null) return null;
        String l = t.toLowerCase().replaceAll("[^a-z ]", " ").trim();
        if (l.startsWith("no data") || l.equals("none") || l.equals("no")) return null;
        if (l.equals("e") || l.startsWith("entry")) return "E";
        if (l.equals("x") || l.startsWith("exit")) return "X";
        if (l.equals("r") || l.startsWith("read")) return "R";
        if (l.equals("w") || l.equals("d") || l.startsWith("write") || l.startsWith("delete")) return "W";
        return t.toUpperCase();
    }

    // ----------------------------------------------------------------------
    // Sessione di analisi (EDT)
    // ----------------------------------------------------------------------

    private void beginSession(String projectName, List<UseCaseChunk> chunks, CosmicAnalysisListener ui) {
        CosmicJsonModel model = new CosmicJsonModel();
        model.projectName = projectName;
        currentModel = model;
        lastChunks = new ArrayList<>(chunks);
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

    /** Misura -> modello -> diagramma -> CFP -> UI, per UN use case (EDT). */
    private void commitUseCase(UseCaseChunk chunk, Measured measured, int processed, int total, CosmicAnalysisListener ui) {
        CosmicJsonModel model = currentModel;
        if (model == null) return;
        suppressSync.incrementAndGet();
        try {
            UseCase uc = buildUseCase(chunk, measured, model);
            model.useCases.add(uc);

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
            ui.onUseCaseProcessed(uc, ucReport, lastReport, processed, total);
            ui.onProgress(processed, total, "UC " + processed + "/" + total + " completato: [" + uc.id + "] " + uc.name
                    + " -> " + (ucReport == null ? 0 : ucReport.totalCfp) + " CFP");
        } finally {
            suppressSync.decrementAndGet();
        }
    }

    private UseCase buildUseCase(UseCaseChunk chunk, Measured measured, CosmicJsonModel model) {
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
     * Entry; Exit; Read; Write; Total CFP; Warning) con in coda Triggering Event, Functional User e Subsystem.
     * Accanto viene scritto {@code <nome>_dettaglio.csv}: la tabella "Measurement" del professore (FU, Sub-process, DG,
     * OOI, DM, CFP per ogni frase) per confrontare con la Ground Truth anche a livello di data movement.
     */
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
                    Files.write(file.toPath(), ("﻿" + summary).getBytes(StandardCharsets.UTF_8));
                    Files.write(detailFile.toPath(), ("﻿" + detail).getBytes(StandardCharsets.UTF_8));
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

    // ======================================================================
    // Validazione Modello vs Requisiti
    // ======================================================================

    /**
     * Confronta il modello CORRENTE (comprese le modifiche manuali dell'utente in VP) con i requisiti originali,
     * use case per use case. E' un controllo deterministico (nessuna chiamata all'LLM): il documento completo non
     * entra in una finestra di contesto, ma i chunk per use case si'. Il risultato e' consegnato sull'EDT.
     */
    public void validateModelAgainstRequirements(Consumer<String> onResult, Consumer<Throwable> onError) {
        runOnEdt(() -> {
            try {
                CosmicJsonModel model = currentModel;
                List<UseCaseChunk> chunks = lastChunks;
                if (model == null || chunks.isEmpty()) {
                    onError.accept(new IllegalStateException("Nessuna analisi eseguita: carica un documento e premi Analizza."));
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
        dto.id = "UC-USER-" + (++manualUseCaseCounter);
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
        if (id != null) for (UseCase uc : model.useCases) if (id.equals(uc.diagramId)) ids.add(uc.id);
        CosmicReport scoped = new CosmicCalculator().computeScoped(model, ids);
        lastActiveDiagramId = id;
        ui.onScopeChanged(name, scoped, total);
    }

    /** Legge un estremo (getFrom/getTo) di una relazione VP senza dipendere dalla firma esatta dell'interfaccia. */
    private static Object endOf(Object relationship, String getter) {
        if (relationship == null) return null;
        try {
            Method m = findPublicMethod(relationship.getClass(), getter);
            return m == null ? null : m.invoke(relationship);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            return null;
        }
    }

    private static Method findPublicMethod(Class<?> start, String name) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Class<?> k = queue.poll();
            if (k == null || !seen.add(k)) continue;
            if (Modifier.isPublic(k.getModifiers())) {
                try {
                    Method m = k.getMethod(name);
                    return m;
                } catch (NoSuchMethodException ignored) { }
            }
            if (k.getSuperclass() != null) queue.add(k.getSuperclass());
            queue.addAll(Arrays.asList(k.getInterfaces()));
        }
        return null;
    }

    private static String safeTrim(String s) { return s == null ? "" : s.trim(); }

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
            } catch (LlmHttpException e) {
                boolean retriable = e.status >= 500 || e.status == 408 || e.status == 429;
                if (!retriable) {
                    throw new IOException(e.getMessage() + (e.status == 400
                            ? " - probabile 'context length' troppo piccolo in LM Studio (servono almeno 16384 token)." : ""), e);
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
        String requestBody = buildChatCompletionRequestJson(messages);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LLM_ENDPOINT_URL))
                .timeout(LLM_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new LlmHttpException(response.statusCode(), response.body());
        return response.body();
    }

    private String buildChatCompletionRequestJson(List<String[]> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(escapeJson(LLM_MODEL)).append("\",\"temperature\":0,\"stream\":false,\"messages\":[");
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
