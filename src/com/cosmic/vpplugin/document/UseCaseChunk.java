package com.cosmic.vpplugin.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un singolo Use Case isolato dal documento dei requisiti, con la sua IDENTITA' ORIGINALE.
 *
 * E' l'unita' di lavoro del loop del {@code CosmicAiService}: l'LLM riceve solo {@link #scenarioText()}
 * (MAIN SCENARIO + Alternative flow + Exceptions, come negli esempi del professore), mentre
 * {@link #id}, {@link #name}, {@link #subsystem}, gli attori e le relazioni di include restano in Java:
 * l'identita' non passa MAI dall'LLM, quindi non puo' essere persa o rimappata su "UC1".
 */
public final class UseCaseChunk {

    /** Riga "numerata" di uno scenario: "1<TAB>Actor<TAB>frase", "5.a1 ..." oppure "3. frase". */
    static final Pattern STEP_ROW = Pattern.compile("^\\s*(\\d+(?:\\.?[A-Za-z]\\d*)?)[.)]?[\\t ]+(.+)$");

    /** Intestazioni di sezione dello scenario (non sono frasi da misurare). */
    static final Pattern SECTION_HEADER = Pattern.compile(
            "(?i)^\\s*(?:main\\s+(?:success\\s+)?scenario|basic\\s+flow|main\\s+flow|normal\\s+(?:flow|scenario)"
                    + "|alternat(?:ive|e)\\s+(?:flows?|scenarios?)\\b.*|exceptions?\\b.*|extensions?\\b.*)\\s*:?\\s*$");

    /** 1-based, ordine di apparizione nel documento. */
    public final int index;
    /** ID originale, es. "UC-GES-01.1". */
    public final String id;
    /** Nome originale, es. "Search for rooms". */
    public final String name;
    /** Riferimento del titolo nel documento (es. "F-GES-01.1"), null se assente o uguale all'id. */
    public final String reference;
    /** Modulo / sottosistema (titolo di livello piu' alto che contiene lo use case), null se non disponibile. */
    public final String subsystem;
    /** Attori primari dichiarati nel documento (riga "Primary actor"), in ordine; vuota se non dichiarati. */
    public final List<String> primaryActors;
    /** ID degli use case citati con "Include UC-..." (non ancora validati contro l'elenco dei chunk). */
    public final List<String> includedIds;
    /** Righe del blocco da "MAIN SCENARIO" in poi: e' l'input dello Step 1 (Split). */
    public final List<String> scenarioLines;
    /** Frasi del MAIN SCENARIO (solo testo della frase), per la descrizione dello use case in VP. */
    public final List<String> mainSentences;
    /** Frasi di Alternative flow / Exceptions, ognuna con prefisso "[sezione]". */
    public final List<String> otherSentences;
    /** Numero di righe-frase dello scenario (atteso minimo di sotto-processi: lo Split puo' solo aumentarle). */
    public final int sentenceCount;

    UseCaseChunk(int index, String id, String name, String reference, String subsystem,
                 List<String> primaryActors, List<String> includedIds, List<String> scenarioLines) {
        this.index = index;
        this.id = id;
        this.name = name;
        this.reference = reference;
        this.subsystem = subsystem;
        this.primaryActors = Collections.unmodifiableList(new ArrayList<>(primaryActors));
        this.includedIds = Collections.unmodifiableList(new ArrayList<>(includedIds));
        this.scenarioLines = Collections.unmodifiableList(new ArrayList<>(scenarioLines));

        List<String> main = new ArrayList<>();
        List<String> other = new ArrayList<>();
        boolean inMain = true;
        String sectionLabel = "";
        int count = 0;
        for (String line : scenarioLines) {
            Matcher row = STEP_ROW.matcher(line);
            if (!row.matches() && SECTION_HEADER.matcher(line).matches()) {
                String h = line.trim().replaceAll("\\s+", " ");
                inMain = h.toLowerCase().matches("^(?:main|basic|normal).*");
                sectionLabel = h.length() > 70 ? h.substring(0, 70) + "..." : h;
                continue;
            }
            if (row.matches()) {
                count++;
                String sentence = lastCell(row.group(2));
                if (inMain) {
                    main.add(sentence);
                } else {
                    other.add("[" + sectionLabel + "] " + sentence);
                }
            }
        }
        this.mainSentences = Collections.unmodifiableList(main);
        this.otherSentences = Collections.unmodifiableList(other);
        this.sentenceCount = count;
    }

    private static String lastCell(String rest) {
        int tab = rest.lastIndexOf('\t');
        return (tab >= 0 ? rest.substring(tab + 1) : rest).trim();
    }

    /** Testo inviato allo Step 1 (Split): lo scenario completo, una riga per frase/intestazione. */
    public String scenarioText() {
        return String.join("\n", scenarioLines);
    }

    /**
     * Rete di sicurezza contro il "Context Window Exceeded": se lo scenario supera {@code maxChars}
     * viene diviso in parti, preferibilmente ai confini di sezione (Alternative flow / Exceptions) e
     * altrimenti riga per riga, RIPETENDO in testa a ogni parte l'intestazione di sezione corrente.
     * Tutte le parti appartengono allo stesso use case (stessa identita'): il service ne fonde le misure.
     * Per i documenti reali (Albergate: max ~2.700 caratteri per use case) restituisce un'unica parte.
     */
    public List<String> toParts(int maxChars) {
        String full = scenarioText();
        if (full.length() <= maxChars) {
            return Collections.singletonList(full);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String section = null;
        for (String line : scenarioLines) {
            boolean header = !STEP_ROW.matcher(line).matches() && SECTION_HEADER.matcher(line).matches();
            if (header) {
                section = line;
                if (cur.length() > maxChars * 0.6) {      // meglio spezzare qui che in mezzo a una sezione
                    parts.add(cur.toString().trim());
                    cur.setLength(0);
                }
            }
            for (String piece : wrapLine(line, maxChars / 2)) {
                if (cur.length() > 0 && cur.length() + piece.length() + 1 > maxChars) {
                    parts.add(cur.toString().trim());
                    cur.setLength(0);
                    if (section != null && !header) {
                        cur.append(section).append('\n');
                    }
                }
                cur.append(piece).append('\n');
            }
        }
        if (cur.toString().trim().length() > 0) {
            parts.add(cur.toString().trim());
        }
        return parts;
    }

    /** Spezza una riga enorme ai confini di frase; come ultima risorsa taglia a dimensione fissa. */
    private static List<String> wrapLine(String line, int max) {
        if (line.length() <= max) {
            return Collections.singletonList(line);
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : line.split("(?<=[.!?])\\s+")) {
            while (sentence.length() > max) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(sentence.substring(0, max));
                sentence = sentence.substring(max);
            }
            if (cur.length() > 0 && cur.length() + sentence.length() + 1 > max) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(sentence);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    @Override
    public String toString() {
        return "[" + id + "] " + name + (subsystem != null ? " (" + subsystem + ")" : "");
    }
}
