package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.json.MiniJsonParser;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v4.0 - MICRO-LOOP: il motore di misura dello Step 2 (Measure).
 *
 * <h3>Perche' esiste</h3>
 * Prima l'intero output dello Step 1 (lo use case diviso in decine di frasi) veniva dato all'LLM in UNA chiamata
 * che doveva restituire un grosso JSON. Un modello locale da 20B tende ad "impigrirsi": riassume, salta frasi,
 * lascia "No data movement" dove c'e' un movimento. Qui l'LLM non misura MAI uno use case intero:
 * <ol>
 *   <li>l'output testuale dello Step 1 viene parsato in Java in una lista di frasi ({@link #parseSentences});</li>
 *   <li>per OGNI frase si fa una chiamata LLM indipendente che deve rispondere solo su quella frase (lo scenario
 *       intero e' presente solo come contesto, con la riga target marcata);</li>
 *   <li>i micro-risultati vengono uniti in Java in un unico {@link FunctionalProcess} (una riga per frase, nello
 *       stesso ordine: il numero di sotto-processi e' per costruzione uguale al numero di frasi).</li>
 * </ol>
 *
 * <h3>Gestione degli errori</h3>
 * Per ogni frase: un tentativo normale + UN retry con un promemoria di formato piu' severo (a temperature 0 lo stesso
 * identico prompt darebbe la stessa risposta). Se anche il retry fallisce la frase viene registrata come
 * {@link SubProcess#unmeasured} (nessun data movement, evidenziata in UI per la correzione manuale) e si passa
 * alla successiva. Se N frasi CONSECUTIVE falliscono per errore di rete/HTTP (LM Studio spento?) la misura dello
 * use case viene interrotta con {@link IOException}, invece di aspettare i backoff di centinaia di frasi.
 *
 * <h3>Rete di sicurezza sullo Step 1</h3>
 * Lo Split puo' solo AUMENTARE il numero di frasi. Se restituisce meno righe di quante ne aveva lo scenario
 * originale ha troncato: si usano le righe originali (meno granulari, ma nessuna frase persa).
 *
 * La classe e' pura Java: nessuna dipendenza da Swing, dall'Open API di VP o dall'HTTP (il trasporto e' iniettato
 * con {@link LlmCall}), quindi e' testabile fuori da Visual Paradigm.
 */
public final class CosmicMicroLoop {

    /** Trasporto verso l'LLM (nel plugin: {@code CosmicAiService::callLlmWithRetry}). */
    @FunctionalInterface
    public interface LlmCall {
        String call(String systemPrompt, String userPrompt) throws IOException, InterruptedException;
    }

    /** Callback di avanzamento/annullamento. Tutte le implementazioni vengono chiamate sul thread di lavoro. */
    public interface Observer {
        /** Prima di misurare la frase {@code index} (1-based) di {@code total}. */
        void onSentence(int index, int total, String sentence);

        void onLog(String message);

        /** Deve lanciare un'eccezione unchecked se l'utente ha annullato l'analisi. */
        void checkCancelled();
    }

    /** Una frase atomica estratta dall'output dello Step 1. */
    public static final class Sentence {
        /** 1-based, ordine di apparizione. */
        public final int index;
        /** Testo della frase (senza numero, senza colonna attore, senza intestazioni). */
        public final String text;
        /** Colonna "attore" delle righe tabellari ("1\tDoctor\tfrase"), null se assente. */
        public final String actorHint;
        /** Ultima intestazione di sezione incontrata (MAIN SCENARIO, Alternative flow no. 1, Exceptions...), "" se nessuna. */
        public final String section;

        Sentence(int index, String text, String actorHint, String section) {
            this.index = index;
            this.text = text;
            this.actorHint = actorHint;
            this.section = section;
        }
    }

    /** Esito della misura di UN functional process. */
    public static final class Result {
        public final FunctionalProcess functionalProcess = new FunctionalProcess();
        public int sentences;
        /** Frasi per cui e' servito il retry (riuscito o meno). */
        public int retried;
        /** Frasi non misurate neanche dopo il retry. */
        public final List<String> failed = new ArrayList<>();
        /** true se lo Split ha troncato e si sono usate le righe originali. */
        public boolean splitFallback;
    }

    /** Micro-risultato di una frase. */
    static final class Movement {
        final String functionalUser;
        /** "E" | "X" | "R" | "W" | null (= nessun data movement). */
        final String type;
        final String dataGroup;
        final String objectOfInterest;

        Movement(String functionalUser, String type, String dataGroup, String objectOfInterest) {
            this.functionalUser = functionalUser;
            this.type = type;
            this.dataGroup = dataGroup;
            this.objectOfInterest = objectOfInterest;
        }
    }

    /** Frasi consecutive con errore di trasporto dopo le quali si abbandona lo use case. */
    static final int MAX_CONSECUTIVE_TRANSPORT_FAILURES = 3;
    /** Tentativi per frase: normale + retry con promemoria severo. */
    static final int ATTEMPTS_PER_SENTENCE = 2;

    private final LlmCall llm;
    private final Observer observer;
    private final String systemPrompt = CosmicPrompts.systemMicroMeasure();

    public CosmicMicroLoop(LlmCall llm, Observer observer) {
        this.llm = llm;
        this.observer = observer;
    }

    // ======================================================================
    // Loop principale
    // ======================================================================

    /**
     * Misura un functional process frase per frase.
     *
     * @param fpName       nome (es. nome dello use case), usato nei prompt
     * @param splitText    output testuale dello Step 1 (Split)
     * @param originalText scenario originale inviato allo Step 1: serve alla rete di sicurezza e come ripiego
     *                     se lo Split e' vuoto o non interpretabile
     * @throws IOException se l'LLM e' irraggiungibile ({@link #MAX_CONSECUTIVE_TRANSPORT_FAILURES} frasi di fila)
     *                     o se non si individua nessuna frase
     */
    public Result measure(String fpName, String splitText, String originalText) throws IOException, InterruptedException {
        Result result = new Result();

        List<Sentence> sentences = parseSentences(splitText);
        List<Sentence> original = parseSentences(originalText);
        if (sentences.isEmpty() || sentences.size() < original.size()) {
            if (!original.isEmpty()) {
                result.splitFallback = true;
                observer.onLog("Split non affidabile (" + sentences.size() + " frasi contro " + original.size()
                        + " righe originali): uso le righe originali.");
                sentences = original;
            }
        }
        if (sentences.isEmpty()) {
            throw new IOException("nessuna frase individuata nello scenario");
        }

        final int total = sentences.size();
        result.sentences = total;
        final String[] contextLines = renderContextLines(sentences);

        int consecutiveTransportFailures = 0;
        IOException lastTransportError = null;

        for (Sentence s : sentences) {
            observer.checkCancelled();
            observer.onSentence(s.index, total, s.text);

            String user = CosmicPrompts.userMeasureSentence(fpName, contextFor(contextLines, s.index), s.index, s.text);
            Movement movement = null;
            boolean transportFailure = false;

            for (int attempt = 1; attempt <= ATTEMPTS_PER_SENTENCE && movement == null; attempt++) {
                observer.checkCancelled();
                if (attempt > 1) result.retried++;
                String prompt = attempt == 1 ? user : user + CosmicPrompts.USER_MICRO_STRICT_SUFFIX;
                try {
                    String raw = llm.call(systemPrompt, prompt);
                    movement = parseMovement(raw);
                    transportFailure = false;
                    if (movement == null) {
                        observer.onLog("Frase " + s.index + "/" + total + ": risposta non interpretabile"
                                + (attempt < ATTEMPTS_PER_SENTENCE ? ", retry con promemoria di formato." : "."));
                    }
                } catch (IOException e) {
                    transportFailure = true;
                    lastTransportError = e;
                    observer.onLog("Frase " + s.index + "/" + total + ": errore LLM (" + e.getMessage() + ")"
                            + (attempt < ATTEMPTS_PER_SENTENCE ? ", retry." : "."));
                }
            }

            SubProcess sp = new SubProcess();
            sp.step = s.index;
            sp.description = s.text;
            if (movement != null) {
                consecutiveTransportFailures = 0;
                sp.dataMovementType = movement.type;
                sp.dataGroup = movement.dataGroup;
                sp.objectOfInterest = movement.objectOfInterest;
                sp.functionalUser = movement.functionalUser;
                if (sp.functionalUser == null && "E".equals(movement.type)) {
                    sp.functionalUser = nullIfBlank(s.actorHint);     // l'Entry arriva dall'attore della riga
                }
            } else {
                sp.unmeasured = true;
                result.failed.add(s.index + ". " + s.text);
                observer.onLog("Frase " + s.index + "/" + total + " NON misurata (registrata senza data movement): " + abbreviate(s.text, 90));
                if (transportFailure) {
                    if (++consecutiveTransportFailures >= MAX_CONSECUTIVE_TRANSPORT_FAILURES) {
                        throw new IOException(MAX_CONSECUTIVE_TRANSPORT_FAILURES + " frasi consecutive senza risposta dall'LLM: "
                                + (lastTransportError == null ? "" : lastTransportError.getMessage()), lastTransportError);
                    }
                } else {
                    consecutiveTransportFailures = 0;     // l'LLM risponde, solo non nel formato atteso
                }
            }
            result.functionalProcess.subProcesses.add(sp);
        }

        result.functionalProcess.triggeringEvent = triggeringEventOf(sentences.get(0));
        return result;
    }

    /** L'evento scatenante e' la prima frase dello scenario (convenzione della ground truth: foglio "Triggering event"). */
    static String triggeringEventOf(Sentence first) {
        String t = first.text.trim();
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    // ======================================================================
    // Contesto per il prompt
    // ======================================================================

    /** Una riga per frase (+ intestazioni di sezione), numerazione sequenziale non ambigua: "3. frase" o "3<TAB>Attore<TAB>frase". */
    private static String[] renderContextLines(List<Sentence> sentences) {
        List<String> lines = new ArrayList<>();
        String lastSection = "";
        for (Sentence s : sentences) {
            if (!s.section.equals(lastSection)) {
                if (!s.section.isEmpty()) lines.add(s.section);
                lastSection = s.section;
            }
            lines.add(s.actorHint != null ? s.index + "\t" + s.actorHint + "\t" + s.text : s.index + ". " + s.text);
        }
        return lines.toArray(new String[0]);
    }

    /** Il contesto e' sempre l'intero scenario; cambia solo la riga marcata con '>>> '. */
    private static String contextFor(String[] lines, int targetIndex) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            if (isNumberedLineFor(line, targetIndex)) sb.append(">>> ");
            sb.append(line);
        }
        return sb.toString();
    }

    private static boolean isNumberedLineFor(String line, int idx) {
        String prefix = Integer.toString(idx);
        if (!line.startsWith(prefix) || line.length() <= prefix.length()) return false;
        char c = line.charAt(prefix.length());
        return c == '.' || c == '\t';
    }

    // ======================================================================
    // Parsing dell'output dello Step 1
    // ======================================================================

    /** Riga numerata: "1. frase", "2.a1. frase", "3)", "1<TAB>Doctor<TAB>frase". */
    private static final Pattern STEP_ROW = Pattern.compile("^\\s*(\\d+(?:\\.?[A-Za-z]\\d*)?)[.)]?[\\t ]+(.+)$");
    private static final Pattern BULLET_ROW = Pattern.compile("^\\s*[-*•]\\s+(.+)$");
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(?i)^\\s*(?:main\\s+(?:success\\s+)?scenario|basic\\s+flow|main\\s+flow|normal\\s+(?:flow|scenario)"
                    + "|alternat(?:ive|e)\\s+(?:flows?|scenarios?)\\b.*|exceptions?\\b.*|extensions?\\b.*)\\s*:?\\s*$");

    /**
     * Trasforma il testo (output dello Split oppure scenario originale) in frasi atomiche. Tollera: fence markdown,
     * "Split:" iniziale, righe numerate (anche "2.a1."), righe tabellari con colonna attore, elenchi puntati, righe
     * di continuazione (frase spezzata su piu' righe), "###", intestazioni di sezione.
     */
    static List<Sentence> parseSentences(String text) {
        if (text == null) return Collections.emptyList();
        String cleaned = stripFences(text).replaceFirst("(?is)^\\s*split\\s*:\\s*", "");

        List<String[]> rows = new ArrayList<>();           // {sezione, attore|null, testo}
        String section = "";
        boolean sawNumbered = false;
        for (String raw : cleaned.split("\\R")) {
            String line = raw.replace(' ', ' ');
            if (line.trim().isEmpty() || line.trim().equals("###")) continue;
            if (line.trim().equalsIgnoreCase("split:")) continue;

            Matcher row = STEP_ROW.matcher(line);
            if (row.matches()) {
                sawNumbered = true;
                String rest = row.group(2).trim();
                String actor = null;
                String sentence = rest;
                int lastTab = rest.lastIndexOf('\t');
                if (lastTab >= 0) {
                    sentence = rest.substring(lastTab + 1).trim();
                    String actorCell = rest.substring(0, lastTab).trim();
                    actor = actorCell.isEmpty() ? null : actorCell;
                }
                if (!sentence.isEmpty()) rows.add(new String[]{section, actor, sentence});
                continue;
            }
            if (SECTION_HEADER.matcher(line).matches()) {
                section = line.trim().replaceAll("\\s+", " ");
                continue;
            }
            Matcher bullet = BULLET_ROW.matcher(line);
            if (bullet.matches()) {
                rows.add(new String[]{section, null, bullet.group(1).trim()});
                continue;
            }
            if (sawNumbered && !rows.isEmpty()) {
                // riga di continuazione di una frase andata a capo
                String[] last = rows.get(rows.size() - 1);
                last[2] = last[2] + " " + line.trim();
            } else {
                rows.add(new String[]{section, null, line.trim()});   // testo senza numerazione: una frase per riga
            }
        }

        List<Sentence> out = new ArrayList<>();
        int i = 1;
        for (String[] r : rows) out.add(new Sentence(i++, r[2], r[1], r[0]));
        return out;
    }

    // ======================================================================
    // Parsing della risposta (micro-JSON)
    // ======================================================================

    private static final Pattern DM_TEXT_LINE = Pattern.compile(
            "(?is).*?(?:FU\\s*\\((.*?)\\)\\s*[-–—]\\s*)?DG\\s*\\((.*?)\\)\\s*[-–—]\\s*OOI\\s*\\((.*?)\\)\\s*[-–—]\\s*(Entry|Exit|Read|Write|Delete)\\b.*");
    private static final Pattern NO_DM_TEXT = Pattern.compile("(?is).*\\bno data movement\\b.*");

    /**
     * Interpreta la risposta a una singola frase.
     *
     * @return il micro-risultato; {@code null} se la risposta non e' interpretabile (-> retry).
     *         Un risultato con {@code type == null} significa "nessun data movement" ESPLICITO: e' una risposta valida.
     */
    static Movement parseMovement(String raw) {
        if (raw == null) return null;
        String content = stripFences(raw).trim();
        if (content.isEmpty()) return null;

        // 1) JSON (formato richiesto)
        for (String candidate : balancedObjects(content)) {
            for (String text : new String[]{candidate, candidate.replaceAll(",\\s*([}\\]])", "$1")}) {
                try {
                    Map<String, Object> m = MiniJsonParser.parseObject(text);
                    Object dmRaw = firstPresent(m, "dataMovementType", "dataMovement", "movement", "dm", "type");
                    if (dmRaw == null && !containsAnyKey(m, "dataMovementType", "dataMovement", "movement", "dm", "type")) continue;
                    String type = canonicalMovement(dmRaw == null ? null : String.valueOf(dmRaw));
                    if (INVALID.equals(type)) return null;
                    return new Movement(
                            nullIfBlank(asText(firstPresent(m, "functionalUser", "fu"))),
                            type,
                            nullIfBlank(asText(firstPresent(m, "dataGroup", "dg"))),
                            nullIfBlank(asText(firstPresent(m, "objectOfInterest", "ooi"))));
                } catch (RuntimeException ignored) { /* prova la variante successiva */ }
            }
        }

        // 2) Ripiego: report testuale CosMet ("DG (..) - OOI (..) - Entry" / "No data movement")
        Matcher dm = DM_TEXT_LINE.matcher(content);
        if (dm.matches()) {
            String type = canonicalMovement(dm.group(4));
            if (INVALID.equals(type)) return null;
            return new Movement(nullIfBlank(dm.group(1)), type, nullIfBlank(dm.group(2)), nullIfBlank(dm.group(3)));
        }
        if (NO_DM_TEXT.matcher(content).matches()) {
            return new Movement(null, null, null, null);
        }
        return null;
    }

    /** Sentinella di "valore presente ma non valido" (confrontata con equals: nessun tipo valido ha questo testo). */
    static final String INVALID = "?INVALID?";

    /**
     * Normalizza il tipo di movimento in "E"/"X"/"R"/"W"/null. {@link #INVALID} se il valore e' ambiguo (due movimenti
     * diversi nello stesso campo, es. "Read, Exit") o non e' un tipo noto.
     */
    static String canonicalMovement(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase();
        if (lower.equals("null") || lower.equals("none") || lower.equals("no") || lower.equals("n/a") || lower.equals("-")
                || lower.startsWith("no data")) {
            return null;
        }
        if (t.length() == 1) {
            switch (Character.toUpperCase(t.charAt(0))) {
                case 'E': return "E";
                case 'X': return "X";
                case 'R': return "R";
                case 'W': case 'D': return "W";
                default: return INVALID;
            }
        }
        Set<String> found = new LinkedHashSet<>();
        for (String word : lower.replaceAll("[^a-z]+", " ").trim().split(" ")) {
            switch (word) {
                case "entry": found.add("E"); break;
                case "exit": found.add("X"); break;
                case "read": found.add("R"); break;
                case "write": case "delete": found.add("W"); break;
                default: break;
            }
        }
        return found.size() == 1 ? found.iterator().next() : INVALID;
    }

    private static Object firstPresent(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return v;
        }
        return null;
    }

    private static boolean containsAnyKey(Map<String, Object> m, String... keys) {
        for (String k : keys) if (m.containsKey(k)) return true;
        return false;
    }

    private static String asText(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Oggetti JSON bilanciati nel testo (rispetta le stringhe), dal piu' esterno al piu' interno, al massimo 5. */
    private static List<String> balancedObjects(String s) {
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
                    out.add(s.substring(start, i + 1));
                    break;
                }
            }
        }
        return out;
    }

    // ======================================================================
    // Utilita'
    // ======================================================================

    private static String stripFences(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstNewline = trimmed.indexOf('\n');
        String body = firstNewline >= 0 ? trimmed.substring(firstNewline + 1) : trimmed;
        int closing = body.lastIndexOf("```");
        return (closing >= 0 ? body.substring(0, closing) : body).trim();
    }

    private static String nullIfBlank(String s) {
        if (s == null) return null;
        String t = s.trim();
        return (t.isEmpty() || t.equalsIgnoreCase("null") || t.equalsIgnoreCase("n/a") || t.equals("-")) ? null : t;
    }

    private static String abbreviate(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
