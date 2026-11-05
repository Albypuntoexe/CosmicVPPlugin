package com.cosmic.vpplugin.document;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Estrazione STRUTTURATA del testo da un file .docx, senza dipendenze esterne (solo JDK: zip + StAX).
 *
 * v3.0 - RISCRITTURA COMPLETA. La versione precedente cercava i tag con {@code tag.startsWith("<w:t")}:
 * quel prefisso corrisponde anche a {@code <w:tbl>}, {@code <w:tr>}, {@code <w:tc>}, {@code <w:tcPr>},
 * {@code <w:tblPr>}, {@code <w:tblGrid>}... e per ognuno di essi tutto il markup fino al successivo
 * {@code </w:t>} veniva copiato nel testo come se fosse contenuto. Su ALBERGATE-UC.docx (67 use case, quasi
 * tutti in tabelle Word) il risultato era ~3,2 milioni di caratteri di XML grezzo (oltre 85.000 occorrenze di
 * "&lt;w:") al posto di ~91.000 caratteri di testo vero: e' questa la vera origine dei "1,4 milioni di token"
 * e delle risposte dell'LLM mappate su "UC1/FP1".
 *
 * Questo parser invece legge l'XML in streaming (word/document.xml pesa ~4,6 MB) e produce un testo che
 * PRESERVA LA STRUTTURA, in un formato che {@link UseCaseChunker} sa interpretare e che coincide con quello
 * degli esempi 7-14 del prompt di Split del professore:
 *
 *  - paragrafi con stile Titolo/Heading N  ->  riga "#" (N volte) + testo   (gerarchia modulo / gruppo / use case)
 *  - righe di tabella                      ->  una riga di testo, celle separate da TAB
 *                                              ("1\tHotel manager\tThe hotel manager selects ...")
 *  - elenchi puntati/numerati              ->  "- testo"
 *  - indice (stili Sommario/TOC)           ->  ignorato
 *  - testo cancellato (revisioni), campi, immagini, intestazioni/pie' di pagina -> ignorati
 *
 * Sicurezza: DTD ed entita' esterne sono disabilitati (XXE).
 */
public final class DocxDocumentParser implements DocumentParser {

    private static final String DOCUMENT_XML_ENTRY = "word/document.xml";
    private static final String STYLES_XML_ENTRY = "word/styles.xml";
    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MC_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    private static final Pattern HEADING_NAME =
            Pattern.compile("(?i)^(?:heading|titolo|überschrift|titre|título)\\s*([1-9])$");
    private static final Pattern TOC_NAME =
            Pattern.compile("(?i)^(?:toc|sommario|table of contents|indice)\\s*\\d*$");

    @Override
    public boolean supports(File file) {
        return file.getName().toLowerCase().endsWith(".docx");
    }

    @Override
    public String extractText(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry(DOCUMENT_XML_ENTRY);
            if (entry == null) {
                throw new IOException("Il file '" + file.getName()
                        + "' non contiene " + DOCUMENT_XML_ENTRY + ": non e' un .docx valido "
                        + "(o e' protetto/cifrato, non supportato).");
            }
            Map<String, StyleInfo> styles = readStyles(zip);
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = parseBody(in, styles);
            } catch (XMLStreamException e) {
                throw new IOException("XML del .docx non leggibile: " + e.getMessage(), e);
            }
            if (text.isBlank()) {
                throw new IOException("Nessun testo estraibile da '" + file.getName() + "'.");
            }
            return text;
        }
    }

    // ------------------------------------------------------------------
    // Stili (per riconoscere i titoli anche quando lo styleId non e' "Heading1")
    // ------------------------------------------------------------------

    private static final class StyleInfo {
        String name = "";
        String basedOn;
        int outlineLevel = -1; // 0-based come in OOXML, -1 = nessuno
    }

    private Map<String, StyleInfo> readStyles(ZipFile zip) {
        Map<String, StyleInfo> map = new HashMap<>();
        ZipEntry entry = zip.getEntry(STYLES_XML_ENTRY);
        if (entry == null) {
            return map;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader r = newFactory().createXMLStreamReader(in);
            String currentId = null;
            StyleInfo current = null;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT && W_NS.equals(r.getNamespaceURI())) {
                    String n = r.getLocalName();
                    if ("style".equals(n)) {
                        currentId = r.getAttributeValue(W_NS, "styleId");
                        current = new StyleInfo();
                    } else if (current != null && "name".equals(n)) {
                        String v = r.getAttributeValue(W_NS, "val");
                        current.name = v == null ? "" : v;
                    } else if (current != null && "basedOn".equals(n)) {
                        current.basedOn = r.getAttributeValue(W_NS, "val");
                    } else if (current != null && "outlineLvl".equals(n)) {
                        current.outlineLevel = parseIntSafe(r.getAttributeValue(W_NS, "val"), -1);
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && W_NS.equals(r.getNamespaceURI())
                        && "style".equals(r.getLocalName())) {
                    if (currentId != null && current != null) {
                        map.put(currentId, current);
                    }
                    currentId = null;
                    current = null;
                }
            }
        } catch (Exception ignored) {
            // senza styles.xml si ricade sul riconoscimento tramite styleId ("Titolo1", "Heading2", ...)
        }
        return map;
    }

    /** Livello di titolo (1..9) per uno stile, 0 se non e' un titolo. Segue la catena "basedOn". */
    private int headingLevelOfStyle(String styleId, Map<String, StyleInfo> styles) {
        String id = styleId;
        for (int hop = 0; id != null && hop < 6; hop++) {
            StyleInfo info = styles.get(id);
            Matcher byId = HEADING_NAME.matcher(id);
            if (byId.matches()) {
                return Integer.parseInt(byId.group(1));
            }
            if (info == null) {
                return 0;
            }
            Matcher byName = HEADING_NAME.matcher(info.name.trim());
            if (byName.matches()) {
                return Integer.parseInt(byName.group(1));
            }
            if (info.outlineLevel >= 0 && info.outlineLevel < 9) {
                return info.outlineLevel + 1;
            }
            id = info.basedOn;
        }
        return 0;
    }

    private boolean isTocStyle(String styleId, Map<String, StyleInfo> styles) {
        if (styleId == null) {
            return false;
        }
        if (TOC_NAME.matcher(styleId).matches()) {
            return true;
        }
        StyleInfo info = styles.get(styleId);
        return info != null && TOC_NAME.matcher(info.name.trim()).matches();
    }

    // ------------------------------------------------------------------
    // Corpo del documento
    // ------------------------------------------------------------------

    private static final class ParaCtx {
        final StringBuilder text = new StringBuilder();
        String styleId;
        int outlineLevel = -1;
        boolean numbered;
    }

    private static final class TableCtx {
        final TableCtx parent;
        List<List<String>> rows = new ArrayList<>();   // solo per chiarezza: le righe vengono emesse subito
        List<String> cells;                            // celle (testo) della riga corrente
        List<String> cellParagraphs;                   // paragrafi della cella corrente

        TableCtx(TableCtx parent) {
            this.parent = parent;
        }
    }

    private String parseBody(InputStream in, Map<String, StyleInfo> styles) throws XMLStreamException {
        XMLStreamReader r = newFactory().createXMLStreamReader(in);
        StringBuilder out = new StringBuilder(128 * 1024);
        Deque<ParaCtx> paragraphs = new ArrayDeque<>();
        TableCtx table = null;
        int fallbackDepth = 0; // contenuto duplicato di mc:Fallback (caselle di testo): da ignorare

        while (r.hasNext()) {
            int ev = r.next();

            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ns = r.getNamespaceURI();
                String n = r.getLocalName();
                if (MC_NS.equals(ns) && "Fallback".equals(n)) {
                    fallbackDepth++;
                    continue;
                }
                if (fallbackDepth > 0 || !W_NS.equals(ns)) {
                    continue;
                }
                switch (n) {
                    case "tbl":
                        table = new TableCtx(table);
                        break;
                    case "tr":
                        if (table != null) {
                            table.cells = new ArrayList<>();
                        }
                        break;
                    case "tc":
                        if (table != null) {
                            table.cellParagraphs = new ArrayList<>();
                        }
                        break;
                    case "p":
                        paragraphs.push(new ParaCtx());
                        break;
                    case "pStyle":
                        if (!paragraphs.isEmpty()) {
                            paragraphs.peek().styleId = r.getAttributeValue(W_NS, "val");
                        }
                        break;
                    case "outlineLvl":
                        if (!paragraphs.isEmpty()) {
                            paragraphs.peek().outlineLevel = parseIntSafe(r.getAttributeValue(W_NS, "val"), -1);
                        }
                        break;
                    case "numPr":
                        if (!paragraphs.isEmpty()) {
                            paragraphs.peek().numbered = true;
                        }
                        break;
                    case "t":
                        if (!paragraphs.isEmpty()) {
                            paragraphs.peek().text.append(r.getElementText());
                        }
                        break;
                    case "tab":
                    case "br":
                    case "cr":
                        // mai '\t' o '\n': i TAB sono il separatore di cella dell'output
                        if (!paragraphs.isEmpty()) {
                            paragraphs.peek().text.append(' ');
                        }
                        break;
                    case "noBreakHyphen":
                        if (!paragraphs.isEmpty()) {
                            paragraphs.peek().text.append('-');
                        }
                        break;
                    default:
                        break;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String ns = r.getNamespaceURI();
                String n = r.getLocalName();
                if (MC_NS.equals(ns) && "Fallback".equals(n)) {
                    fallbackDepth = Math.max(0, fallbackDepth - 1);
                    continue;
                }
                if (fallbackDepth > 0 || !W_NS.equals(ns)) {
                    continue;
                }
                switch (n) {
                    case "p": {
                        if (paragraphs.isEmpty()) {
                            break;
                        }
                        ParaCtx p = paragraphs.pop();
                        String text = p.text.toString().replaceAll("[\\s\\u00a0]+", " ").trim();
                        if (text.isEmpty() || isTocStyle(p.styleId, styles)) {
                            break;
                        }
                        if (table != null && table.cellParagraphs != null) {
                            table.cellParagraphs.add(text);
                        } else {
                            int level = headingLevelOfStyle(p.styleId, styles);
                            if (level == 0 && p.outlineLevel >= 0 && p.outlineLevel < 9) {
                                level = p.outlineLevel + 1;
                            }
                            if (level > 0) {
                                out.append("######".substring(0, Math.min(level, 6))).append(' ').append(text).append('\n');
                            } else {
                                out.append(p.numbered ? "- " : "").append(text).append('\n');
                            }
                        }
                        break;
                    }
                    case "tc":
                        if (table != null && table.cells != null && table.cellParagraphs != null) {
                            table.cells.add(String.join(" ", table.cellParagraphs));
                            table.cellParagraphs = null;
                        }
                        break;
                    case "tr":
                        if (table != null && table.cells != null) {
                            emitRow(table, out);
                            table.cells = null;
                        }
                        break;
                    case "tbl":
                        if (table != null) {
                            table = table.parent;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        r.close();
        return out.toString().replaceAll("\\n{2,}", "\n").trim();
    }

    /** Riga di tabella -> una riga di testo con le celle separate da TAB (celle vuote finali eliminate). */
    private void emitRow(TableCtx table, StringBuilder out) {
        List<String> cells = new ArrayList<>(table.cells);
        int last = cells.size() - 1;
        while (last >= 0 && cells.get(last).isBlank()) {
            last--;
        }
        if (last < 0) {
            return; // riga completamente vuota
        }
        List<String> trimmed = cells.subList(0, last + 1);
        if (table.parent == null) {
            out.append(String.join("\t", trimmed)).append('\n');
        } else if (table.parent.cellParagraphs != null) {
            // tabella annidata: appiattita nella cella della tabella genitrice
            table.parent.cellParagraphs.add(String.join(" | ", trimmed));
        }
    }

    private static XMLInputFactory newFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        trySet(f, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        trySet(f, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        trySet(f, XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        return f;
    }

    private static void trySet(XMLInputFactory f, String property, Object value) {
        try {
            f.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // proprieta' non supportata da questa implementazione StAX: si prosegue
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
