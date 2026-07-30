package com.cosmic.vpplugin.document;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Estrazione testo da file .docx SENZA alcuna dipendenza esterna (niente
 * Apache POI). Motivazione: un .docx e', per specifica OOXML, semplicemente
 * un archivio ZIP che contiene (tra gli altri) il file
 * {@code word/document.xml}, un XML in cui il testo "visibile" e' racchiuso
 * in tag {@code <w:t>...</w:t>}. Per un semplice documento di requisiti
 * (titoli, paragrafi, elenchi) e' sufficiente:
 *   1) apri lo zip con {@link java.util.zip.ZipFile} (nel JDK, nessun jar);
 *   2) leggi il contenuto di word/document.xml;
 *   3) estrai il testo dentro ogni run <w:t>, ricostruendo gli "a capo" in
 *      corrispondenza dei tag di fine paragrafo </w:p>.
 *
 * Questo approccio e' deliberatamente meno completo di POI (non gestisce
 * tabelle complesse, note, campi dinamici, ecc.) ma e' più che sufficiente
 * per documenti di requisiti in prosa/scenario, ed evita del tutto il
 * problema di "quali librerie sono presenti nel classpath di Visual
 * Paradigm" gia' discusso nel commento di {@code MiniJsonParser}: stesso
 * principio, stessa scelta architetturale, applicata qui a un formato
 * diverso.
 *
 * Se in futuro servisse un'estrazione più fedele (tabelle, box di testo,
 * intestazioni/piè di pagina), la strategia consigliata resta quella usata
 * per il PDF in {@link PdfDocumentParser}: caricamento dinamico e isolato
 * di Apache POI da un jar esterno, non un import statico nel codice del
 * plugin.
 */
public final class DocxDocumentParser implements DocumentParser {

    private static final String DOCUMENT_XML_ENTRY = "word/document.xml";

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
            String xml = readEntryAsString(zip, entry);
            String text = stripDocxMarkup(xml);
            if (text.isBlank()) {
                throw new IOException("Nessun testo estraibile da '" + file.getName() + "'.");
            }
            return text;
        }
    }

    private String readEntryAsString(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Ripulizia "a mano" del markup OOXML: estrae solo il contenuto dei
     * tag {@code <w:t>}, e inserisce un a-capo alla chiusura di ogni
     * paragrafo ({@code </w:p>}) o di ogni interruzione di riga esplicita
     * ({@code <w:br/>}), per preservare la struttura a step/scenario del
     * documento originale (importante per il Sentence Splitter lato LLM,
     * si veda il paper CosMet, Sez. 3.2.1).
     */
    private String stripDocxMarkup(String xml) {
        StringBuilder text = new StringBuilder();
        int i = 0;
        int len = xml.length();
        while (i < len) {
            int tagStart = xml.indexOf('<', i);
            if (tagStart < 0) {
                break;
            }
            int tagEnd = xml.indexOf('>', tagStart);
            if (tagEnd < 0) {
                break;
            }
            String tag = xml.substring(tagStart, tagEnd + 1);

            if (tag.startsWith("<w:t") && !tag.startsWith("<w:t/>") && !tag.endsWith("/>")) {
                int contentEnd = xml.indexOf("</w:t>", tagEnd + 1);
                if (contentEnd >= 0) {
                    String raw = xml.substring(tagEnd + 1, contentEnd);
                    text.append(unescapeXmlEntities(raw));
                    i = contentEnd + "</w:t>".length();
                    continue;
                }
            } else if (tag.startsWith("</w:p>")) {
                text.append("\n");
            } else if (tag.startsWith("<w:br") || tag.startsWith("<w:tab")) {
                text.append(tag.startsWith("<w:tab") ? "\t" : "\n");
            }
            i = tagEnd + 1;
        }
        // Normalizza le righe vuote multiple lasciate dai paragrafi senza testo.
        return text.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String unescapeXmlEntities(String s) {
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }
}
