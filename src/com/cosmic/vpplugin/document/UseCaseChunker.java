package com.cosmic.vpplugin.document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smart Chunking: trasforma il testo estratto da un documento di requisiti in una lista di
 * {@link UseCaseChunk}, uno per Use Case, ciascuno con il suo ID/nome ORIGINALE.
 *
 * Sostituisce il vecchio taglio "brutale" ogni 5000 caratteri (che spezzava gli use case a meta' e
 * faceva perdere gli ID). Strategia a cascata, dalla piu' precisa alla piu' generica:
 *
 *  1. TITOLI + TABELLE (output di {@link DocxDocumentParser}): ogni titolo ('#', '##', '###') il cui
 *     contenuto include "MAIN SCENARIO" e' uno use case. ID e nome dalla riga "ID UC-XXX-NN&lt;TAB&gt;Nome"
 *     della tabella (altrimenti dal titolo "F-XXX-NN: Nome"); attori dalla riga "Primary actor";
 *     sottosistema = titolo di livello piu' alto che lo contiene.
 *  2. REGEX SUI TITOLI (testo piatto, es. PDF/TXT): righe come "ID UC-..", "UC-GES-01.1: Nome",
 *     "Use Case 3: Nome", "F-GES-01.1: Nome" aprono un blocco; il blocco e' uno use case se contiene
 *     "MAIN SCENARIO".
 *  3. SPLIT SU "MAIN SCENARIO": ogni intestazione di scenario apre un use case; nome = riga precedente.
 *  4. ULTIMA RISORSA, SEGNALATA: blocchi per dimensione (ID "BLOCK-001"): si usa solo se il documento
 *     non contiene alcuna struttura riconoscibile, e viene riportato nei warning.
 *
 * Gli ID duplicati vengono resi univoci ("UC-1", "UC-1~2"), cosi' nessuno use case ne sovrascrive un altro.
 *
 * <h3>v5.0 - Gerarchia del documento: sottosistema -> area funzionale -> use case</h3>
 * Un documento ben strutturato (ALBERGATE) ha tre livelli di titolo:
 * <pre>
 *   # PRE - Booking                              sottosistema   (UseCaseChunk.subsystem)
 *   ## F-PRE-04: Commissioned rooms management   AREA FUNZIONALE (UseCaseChunk.functionalArea*)
 *   ### F-PRE-04.5: Add commissioned room        use case
 * </pre>
 * L'area e' il raggruppamento GENITORE dello use case. Regole (strategia 1, titoli + tabelle):
 * <ul>
 *   <li>se i titoli degli use case hanno almeno DUE livelli di raggruppamento sopra di loro, il piu' alto e' il
 *       sottosistema e il successivo e' l'area;</li>
 *   <li>uno use case che e' esso stesso al livello dell'area (es. {@code F-SOG-03: Telephone bill} sotto il solo
 *       sottosistema) e' l'unico use case della propria area; lo stesso vale per un titolo che e' use case E
 *       contenitore di altri ({@code F-PRE-00: Book a room} con i suoi F-PRE-01/02): l'area e' quel titolo;</li>
 *   <li>se esiste UN SOLO livello di raggruppamento, quello e' l'area e non c'e' sottosistema;</li>
 *   <li>nessun raggruppamento (testo piatto, strategie 2-4): area null; il modello usa l'area di default.</li>
 * </ul>
 * Un titolo d'area puo' avere piu' codici ("F-PRE-07, 05, 06: Reservation Management") e una numerazione
 * letterale davanti ("2.4 F-PRE-04: ..."): entrambi sono gestiti da {@link #parseAreaTitle}.
 */
public final class UseCaseChunker {

    private UseCaseChunker() { }

    // ------------------------------------------------------------------ regex
    private static final String UC_ID = "UC[-_]?[A-Za-z0-9]+(?:[-_.][A-Za-z0-9]+)*";
    private static final String REF_ID = "F[-_][A-Za-z0-9]+(?:[-_.][A-Za-z0-9]+)*";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern SCENARIO_HEADER = Pattern.compile(
            "(?i)^\\s*(?:main\\s+(?:success\\s+)?scenario|basic\\s+flow|main\\s+flow|normal\\s+(?:flow|scenario))\\b[\\s:.]*$");
    /** "ID UC-GES-01.1<TAB>Search for rooms" oppure "ID<TAB>UC-GES-01.1<TAB>Search for rooms". */
    private static final Pattern ID_ROW = Pattern.compile("(?i)^\\s*ID\\b[\\s:|]*(" + UC_ID + ")(?:\\t+(.+?))?\\s*$");
    private static final Pattern PRIMARY_ACTOR = Pattern.compile("(?i)^\\s*(?:primary\\s+actors?|attore(?:\\s+principale)?|attori)\\b[\\s:|]+([^\\t]+).*$");
    private static final Pattern TITLE_WITH_REF = Pattern.compile("^\\s*(" + REF_ID + "|" + UC_ID + ")\\s*[:\\-–—]\\s*(.+?)\\s*$");
    private static final Pattern TITLE_UC_NUMBER = Pattern.compile(
            "(?i)^\\s*use\\s*case\\s*#?\\s*(\\d+[A-Za-z]?(?:\\.\\d+)*)\\s*[:\\-–—.)]\\s*(.+?)\\s*$");
    private static final Pattern TITLE_UC_ID = Pattern.compile(
            "(?i)^\\s*(?:use\\s*case\\s+)?(" + UC_ID + ")(?:\\s*[:\\-–—.)]\\s+|\\s+)(.+?)\\s*$");
    private static final Pattern INCLUDE_REF = Pattern.compile("(?i)\\binclude[sd]?\\s+(?:the\\s+)?(?:use\\s+case\\s+)?(" + UC_ID + ")");
    /** Titolo d'area: uno o piu' codici ("F-PRE-07, 05, 06") + separatore + nome. */
    private static final Pattern AREA_TITLE = Pattern.compile(
            "^\\s*(" + REF_ID + "(?:\\s*,\\s*[A-Za-z0-9]+(?:[-_.][A-Za-z0-9]+)*)*)\\s*[:\\-\\u2013\\u2014]\\s*(.+?)\\s*$");
    /** Numerazione letterale davanti al titolo ("2.4 F-PRE-04: ..."): solo la forma a piu' livelli, o un numero seguito da un codice F-. */
    private static final Pattern HEADING_NUMBERING = Pattern.compile("^(?:\\d+(?:\\.\\d+)+[.)]?\\s+|\\d+[.)]?\\s+(?=F[-_]))");
    private static final Pattern TOC_LINE = Pattern.compile(".*(?:\\.{3,}|\\t)\\s*\\d+\\s*$");

    private static final int SIZE_FALLBACK_CHARS = 3500;

    // ------------------------------------------------------------------ API

    public static final class ChunkingResult {
        public final List<UseCaseChunk> chunks;
        /** Strategia usata: "heading+table", "title-regex", "scenario-header" o "size-fallback". */
        public final String strategy;
        public final List<String> warnings;

        ChunkingResult(List<UseCaseChunk> chunks, String strategy, List<String> warnings) {
            this.chunks = Collections.unmodifiableList(chunks);
            this.strategy = strategy;
            this.warnings = Collections.unmodifiableList(warnings);
        }
    }

    public static ChunkingResult chunk(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String t = rtrim(raw);
            if (!t.trim().isEmpty()) {
                lines.add(t);
            }
        }
        List<String> warnings = new ArrayList<>();

        List<RawBlock> blocks = byHeadings(lines);
        String strategy = "heading+table";
        if (blocks.isEmpty()) {
            blocks = byTitleRegex(lines);
            strategy = "title-regex";
        }
        if (blocks.isEmpty()) {
            blocks = byScenarioHeader(lines);
            strategy = "scenario-header";
            if (!blocks.isEmpty()) {
                warnings.add("Titoli degli use case non riconosciuti: nome ricavato dalla riga che precede 'MAIN SCENARIO' "
                        + "e ID generati (UC-001, UC-002, ...).");
            }
        }
        if (blocks.isEmpty()) {
            blocks = bySize(lines);
            strategy = "size-fallback";
            warnings.add("ATTENZIONE: nessuna struttura di use case riconoscibile (ne' titoli ne' 'MAIN SCENARIO'). "
                    + "Il testo e' stato suddiviso per dimensione (BLOCK-001, ...): gli ID originali non sono recuperabili.");
        }
        List<UseCaseChunk> chunks = finalizeBlocks(blocks, strategy, warnings);
        int withoutArea = 0;
        for (UseCaseChunk c : chunks) {
            if (c.functionalArea == null) {
                withoutArea++;
            }
        }
        if (withoutArea == chunks.size()) {
            warnings.add("Nessuna area funzionale riconosciuta (servono titoli a piu' livelli: sottosistema > area > use case): "
                    + "tutti gli use case finiscono nell'area '" + com.cosmic.vpplugin.model.CosmicJsonModel.DEFAULT_AREA_NAME + "'.");
        } else if (withoutArea > 0) {
            warnings.add(withoutArea + " use case senza area funzionale riconosciuta: finiscono nell'area '"
                    + com.cosmic.vpplugin.model.CosmicJsonModel.DEFAULT_AREA_NAME + "'.");
        }
        return new ChunkingResult(chunks, strategy, warnings);
    }

    // ------------------------------------------------------------------ blocchi grezzi

    private static final class RawBlock {
        String headingTitle;          // titolo del blocco (strategia 1) o riga-titolo (strategia 2)
        String idFromTitle;           // id ricavato dal titolo ("UC-...", "F-..."), puo' essere null
        String nameFromTitle;         // nome ricavato dal titolo
        String subsystem;
        int level;                    // livello del titolo (1..6); 0 se il blocco non viene da un titolo
        String[] chain;               // titoli antenati per livello (indice = livello), compreso il titolo stesso
        String areaRef;               // v5.0: codice dell'area funzionale ("F-PRE-04")
        String areaName;              // v5.0: nome dell'area funzionale ("Commissioned rooms management")
        final List<String> lines = new ArrayList<>();
        int scenarioStart = -1;       // indice in lines della riga "MAIN SCENARIO"; -1 = nessuno
    }

    // 1) titoli (#) + tabelle -------------------------------------------------

    private static List<RawBlock> byHeadings(List<String> lines) {
        boolean any = false;
        for (String l : lines) {
            if (HEADING.matcher(l).matches()) {
                any = true;
                break;
            }
        }
        if (!any) {
            return Collections.emptyList();
        }
        String[] stack = new String[7];
        List<RawBlock> all = new ArrayList<>();
        RawBlock cur = null;
        for (String line : lines) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                int level = m.group(1).length();
                String title = clean(m.group(2));
                stack[level] = title;
                for (int k = level + 1; k < stack.length; k++) {
                    stack[k] = null;
                }
                cur = new RawBlock();
                cur.headingTitle = title;
                cur.level = level;
                cur.chain = stack.clone();
                for (int k = 1; k < level; k++) {          // antenato di livello piu' alto = sottosistema
                    if (stack[k] != null) {
                        cur.subsystem = stack[k];
                        break;
                    }
                }
                parseTitle(title, cur);
                all.add(cur);
            } else if (cur != null) {
                cur.lines.add(line);
            }
        }
        List<RawBlock> ucs = new ArrayList<>();
        for (RawBlock b : all) {
            b.scenarioStart = indexOfScenario(b.lines);
            if (b.scenarioStart >= 0) {
                ucs.add(b);
            }
        }
        assignFunctionalAreas(ucs);
        return ucs;
    }

    /**
     * v5.0 - Ricava l'area funzionale di ogni use case dalla gerarchia dei titoli (vedi javadoc di classe). La
     * decisione "c'e' un livello di sottosistema?" e' presa sull'INTERO documento, non use case per use case: in un
     * documento a due livelli (gruppo > use case) il gruppo e' l'area, mentre in ALBERGATE (sottosistema > area >
     * use case) il primo livello e' il sottosistema anche per gli use case senza figli (F-SOG-03).
     */
    private static void assignFunctionalAreas(List<RawBlock> ucs) {
        Set<Integer> groupLevels = new TreeSet<>();
        for (RawBlock b : ucs) {
            for (int k = 1; k < b.level; k++) {
                if (b.chain[k] != null) {
                    groupLevels.add(k);
                }
            }
        }
        boolean hasSubsystemLevel = groupLevels.size() >= 2;
        for (RawBlock b : ucs) {
            int first = 0;                                   // livello del primo antenato (0 = nessuno)
            for (int k = 1; k < b.level; k++) {
                if (b.chain[k] != null) {
                    first = k;
                    break;
                }
            }
            if (first == 0) {
                continue;                                    // use case al livello piu' alto: nessun raggruppamento
            }
            String areaTitle;
            if (!hasSubsystemLevel) {
                areaTitle = b.chain[first];                  // un solo livello di raggruppamento: e' l'area
                b.subsystem = null;
            } else {
                int next = first + 1;                        // primo titolo dopo il sottosistema (al piu' il titolo stesso)
                while (next < b.level && b.chain[next] == null) {
                    next++;
                }
                areaTitle = b.chain[next];
            }
            String[] parsed = parseAreaTitle(areaTitle);
            b.areaRef = parsed[0];
            b.areaName = parsed[1];
        }
    }

    /**
     * "F-PRE-04: Commissioned rooms management" -> {"F-PRE-04", "Commissioned rooms management"};
     * "F-PRE-07, 05, 06: Reservation Management" -> {"F-PRE-07, 05, 06", "Reservation Management"};
     * "2.4 F-PRE-04: ..." -> numerazione letterale scartata; senza codice -> {null, titolo intero}.
     */
    static String[] parseAreaTitle(String title) {
        if (title == null) {
            return new String[]{null, null};
        }
        String t = clean(HEADING_NUMBERING.matcher(clean(title)).replaceFirst(""));
        Matcher m = AREA_TITLE.matcher(t);
        if (m.matches()) {
            return new String[]{m.group(1).replaceAll("\\s*,\\s*", ", "), clean(m.group(2))};
        }
        return new String[]{null, t.isEmpty() ? null : t};
    }

    // 2) regex sui titoli (testo piatto) ----------------------------------------

    private static List<RawBlock> byTitleRegex(List<String> lines) {
        List<Integer> starts = new ArrayList<>();
        List<RawBlock> heads = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (TOC_LINE.matcher(line).matches() || UseCaseChunk.STEP_ROW.matcher(line).matches()) {
                continue;
            }
            RawBlock b = new RawBlock();
            Matcher idRow = ID_ROW.matcher(line);
            int start = i;
            if (idRow.matches()) {
                b.idFromTitle = idRow.group(1);
                b.nameFromTitle = idRow.group(2) == null ? null : clean(idRow.group(2));
                b.headingTitle = line.trim();
                if (b.nameFromTitle == null && i > 0 && isPlainTitleCandidate(lines.get(i - 1))) {
                    b.nameFromTitle = clean(lines.get(i - 1));
                    start = i - 1;
                }
            } else {
                Matcher a = TITLE_UC_NUMBER.matcher(line);
                Matcher c = TITLE_UC_ID.matcher(line);
                Matcher d = TITLE_WITH_REF.matcher(line);
                if (a.matches()) {
                    b.idFromTitle = "UC" + a.group(1);
                    b.nameFromTitle = clean(a.group(2));
                } else if (c.matches()) {
                    b.idFromTitle = c.group(1);
                    b.nameFromTitle = clean(c.group(2));
                } else if (d.matches()) {
                    b.idFromTitle = d.group(1);
                    b.nameFromTitle = clean(d.group(2));
                } else {
                    continue;
                }
                b.headingTitle = line.trim();
            }
            starts.add(start);
            heads.add(b);
        }
        List<RawBlock> ucs = new ArrayList<>();
        for (int h = 0; h < heads.size(); h++) {
            int from = starts.get(h);
            int to = (h + 1 < heads.size()) ? starts.get(h + 1) : lines.size();
            RawBlock b = heads.get(h);
            b.lines.addAll(lines.subList(from, to));
            b.scenarioStart = indexOfScenario(b.lines);
            if (b.scenarioStart >= 0) {
                ucs.add(b);
            }
        }
        return ucs;
    }

    // 3) split su "MAIN SCENARIO" -----------------------------------------------

    private static List<RawBlock> byScenarioHeader(List<String> lines) {
        List<Integer> hdr = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (SCENARIO_HEADER.matcher(lines.get(i)).matches()) {
                hdr.add(i);
            }
        }
        List<RawBlock> out = new ArrayList<>();
        int prevEnd = 0;
        for (int h = 0; h < hdr.size(); h++) {
            int hi = hdr.get(h);
            // nome = riga piu' vicina sopra l'intestazione (entro 8 righe, non una riga-frase/intestazione)
            int nameIdx = -1;
            for (int k = hi - 1; k >= Math.max(prevEnd, hi - 8); k--) {
                if (isPlainTitleCandidate(lines.get(k))) {
                    nameIdx = k;
                    break;
                }
            }
            int blockStart = (nameIdx >= 0) ? nameIdx : hi;
            int blockEnd = lines.size();
            if (h + 1 < hdr.size()) {
                int nh = hdr.get(h + 1);
                blockEnd = nh;
                for (int k = nh - 1; k > hi; k--) {            // lascia la riga-titolo al blocco successivo
                    if (isPlainTitleCandidate(lines.get(k))) {
                        blockEnd = k;
                        break;
                    }
                }
            }
            RawBlock b = new RawBlock();
            b.headingTitle = nameIdx >= 0 ? clean(lines.get(nameIdx)) : null;
            b.nameFromTitle = b.headingTitle;
            b.lines.addAll(lines.subList(Math.max(prevEnd, Math.min(blockStart, hi)), blockEnd));
            b.scenarioStart = indexOfScenario(b.lines);
            out.add(b);
            prevEnd = blockEnd;
        }
        return out;
    }

    // 4) ultima risorsa: blocchi per dimensione ------------------------------------

    private static List<RawBlock> bySize(List<String> lines) {
        List<RawBlock> out = new ArrayList<>();
        RawBlock cur = new RawBlock();
        int size = 0;
        for (String line : lines) {
            if (size > 0 && size + line.length() > SIZE_FALLBACK_CHARS) {
                out.add(cur);
                cur = new RawBlock();
                size = 0;
            }
            cur.lines.add(line);
            size += line.length() + 1;
        }
        if (!cur.lines.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    // ------------------------------------------------------------------ da blocco grezzo a chunk

    private static List<UseCaseChunk> finalizeBlocks(List<RawBlock> blocks, String strategy, List<String> warnings) {
        List<UseCaseChunk> chunks = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();

        // pre-passo: ID duplicati nelle tabelle (refuso del documento sorgente)
        java.util.Map<String, Integer> rowIdCount = new java.util.HashMap<>();
        for (RawBlock b : blocks) {
            String rid = rowIdOf(b);
            if (rid != null) {
                rowIdCount.merge(rid.toLowerCase(), 1, Integer::sum);
            }
        }
        int n = 0;
        for (RawBlock b : blocks) {
            n++;
            String idFromRow = null;
            String nameFromRow = null;
            String actorRaw = null;
            int headLimit = b.scenarioStart >= 0 ? b.scenarioStart : b.lines.size();
            for (int i = 0; i < headLimit; i++) {
                String l = b.lines.get(i);
                Matcher idm = ID_ROW.matcher(l);
                if (idRowNotYet(idFromRow) && idm.matches()) {
                    idFromRow = idm.group(1);
                    nameFromRow = idm.group(2) == null ? null : clean(idm.group(2));
                    continue;
                }
                Matcher am = PRIMARY_ACTOR.matcher(l);
                if (actorRaw == null && am.matches()) {
                    actorRaw = am.group(1);
                }
            }

            String id;
            String reference = null;
            if (idFromRow != null) {
                id = idFromRow;
                if (b.idFromTitle != null && !b.idFromTitle.equalsIgnoreCase(idFromRow)) {
                    reference = b.idFromTitle;
                    // Stesso ID dichiarato da piu' use case: se il titolo ("F-PRE-00") ne indica uno diverso
                    // ("UC-PRE-00") e' l'ID del titolo quello coerente; l'altro use case tiene l'ID della tabella,
                    // cosi' i riferimenti "Include UC-PRE-01 (Search ...)" restano corretti.
                    String converted = b.idFromTitle.matches("(?i)^F[-_].*") ? "UC" + b.idFromTitle.substring(1) : null;
                    if (converted != null && !converted.equalsIgnoreCase(idFromRow)
                            && rowIdCount.getOrDefault(idFromRow.toLowerCase(), 0) > 1) {
                        warnings.add("ID duplicato nel documento: '" + idFromRow + "' e' dichiarato da piu' use case; per '"
                                + firstNonBlank(nameFromRow, b.nameFromTitle) + "' (titolo " + b.idFromTitle + ") si usa '" + converted + "'.");
                        reference = idFromRow;
                        id = converted;
                    }
                }
            } else if (b.idFromTitle != null) {
                id = b.idFromTitle;
            } else if ("size-fallback".equals(strategy)) {
                id = String.format("BLOCK-%03d", n);
            } else {
                id = String.format("UC-%03d", n);
            }

            String name = firstNonBlank(nameFromRow, b.nameFromTitle, b.headingTitle);
            if (name == null) {
                name = "size-fallback".equals(strategy) ? "Blocco " + n + " (nessun use case riconosciuto)" : "Use Case " + n;
            }

            // unicita' degli id
            String unique = id;
            int dup = 2;
            while (!usedIds.add(unique)) {
                unique = id + "~" + dup++;
            }

            List<String> scenario = (b.scenarioStart >= 0)
                    ? new ArrayList<>(b.lines.subList(b.scenarioStart, b.lines.size()))
                    : new ArrayList<>(b.lines);

            LinkedHashSet<String> includes = new LinkedHashSet<>();
            for (String l : scenario) {
                Matcher im = INCLUDE_REF.matcher(l);
                while (im.find()) {
                    includes.add(im.group(1));
                }
            }
            includes.remove(unique);
            includes.remove(id);

            chunks.add(new UseCaseChunk(chunks.size() + 1, unique, name, reference, b.subsystem,
                    b.areaRef, b.areaName,
                    parseActors(actorRaw), new ArrayList<>(includes), scenario));
        }
        return chunks;
    }

    private static boolean idRowNotYet(String idFromRow) {
        return idFromRow == null;
    }

    /** ID dichiarato nella riga "ID UC-..." della tabella del blocco (null se assente). */
    private static String rowIdOf(RawBlock b) {
        int limit = b.scenarioStart >= 0 ? b.scenarioStart : b.lines.size();
        for (int i = 0; i < limit; i++) {
            Matcher m = ID_ROW.matcher(b.lines.get(i));
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ utilita'

    /** "F-GES-01.1: Search for rooms" -> id=F-GES-01.1, nome="Search for rooms"; altrimenti nome = intero titolo. */
    private static void parseTitle(String title, RawBlock b) {
        Matcher m = TITLE_WITH_REF.matcher(title);
        if (m.matches()) {
            b.idFromTitle = m.group(1);
            b.nameFromTitle = clean(m.group(2));
        } else {
            b.nameFromTitle = title;
        }
    }

    private static int indexOfScenario(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (SCENARIO_HEADER.matcher(lines.get(i)).matches()) {
                return i;
            }
        }
        return -1;
    }

    /** Riga adatta a fare da titolo: niente riga-frase numerata, niente tabella a piu' celle, niente intestazione di sezione. */
    private static boolean isPlainTitleCandidate(String line) {
        String t = line.trim();
        return !t.isEmpty() && t.length() <= 120 && t.indexOf('\t') < 0
                && !UseCaseChunk.STEP_ROW.matcher(t).matches()
                && !SCENARIO_HEADER.matcher(t).matches()
                && !UseCaseChunk.SECTION_HEADER.matcher(t).matches()
                && !TOC_LINE.matcher(t).matches();
    }

    /**
     * "Hotel manager, Operator" -> [Hotel manager, Operator];
     * "Operator (Hotel operator or Agency operator)" -> [Operator]; "N/A" -> [].
     */
    static List<String> parseActors(String raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        String s = raw.replaceAll("\\([^)]*\\)", " ");
        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String part : s.split("[,;]")) {
            String name = clean(part);
            if (name.isEmpty() || Arrays.asList("n/a", "na", "none", "-", "tbd").contains(name.toLowerCase())) {
                continue;
            }
            if (seenKeys.add(name.toLowerCase())) {
                out.add(name);
            }
        }
        return out;
    }

    private static String clean(String s) {
        return s == null ? null : s.replaceAll("[\\s\\u00a0]+", " ").trim().replaceAll("[.:]+$", "").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
