package com.cosmic.vpplugin.document;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Punto di ingresso unico per l'estrazione testo (Epic 1). La UI
 * ({@code CosmicAnalyzerDialogHandler}) e il Service non devono conoscere
 * quale {@link DocumentParser} concreto gestisce un dato file: chiedono
 * semplicemente {@link #extractText(File)}.
 *
 * Aggiungere un nuovo formato in futuro (es. .rtf, .html) significa
 * scrivere una nuova implementazione di {@link DocumentParser} e
 * aggiungerla alla lista qui sotto: nessun'altra classe va toccata.
 */
public final class DocumentParserFactory {

    private static final List<DocumentParser> PARSERS = List.of(
            new PlainTextDocumentParser(),
            new DocxDocumentParser(),
            new PdfDocumentParser()
    );

    private DocumentParserFactory() { }

    public static boolean isSupported(File file) {
        return PARSERS.stream().anyMatch(p -> p.supports(file));
    }

    public static String extractText(File file) throws IOException {
        for (DocumentParser parser : PARSERS) {
            if (parser.supports(file)) {
                return parser.extractText(file);
            }
        }
        throw new IOException("Formato file non supportato: '" + file.getName()
                + "'. Formati accettati: .txt, .json, .docx, .pdf.");
    }
}
