package com.cosmic.vpplugin.document;

import java.io.File;
import java.io.IOException;

/**
 * Epic 1 - Document Processing Avanzato.
 *
 * Contratto comune per tutti gli estrattori di testo da documento di
 * requisiti (txt, json, docx, pdf, ...). Il testo estratto e' poi lo stesso
 * "rawRequirementsText" che gia' oggi {@code CosmicAiService.analyzeRequirements}
 * invia all'LLM: nessuna altra parte della pipeline deve sapere da quale
 * formato di file il testo proviene.
 *
 * Implementazioni:
 *  - {@link PlainTextDocumentParser}: .txt/.json, comportamento identico a
 *    quello preesistente (Files.readAllBytes + trim).
 *  - {@link DocxDocumentParser}: .docx, estrazione senza dipendenze esterne
 *    (il formato .docx e' uno zip di XML: si legge word/document.xml e si
 *    ripulisce il markup a mano, stesso spirito "zero dipendenze" di
 *    MiniJsonParser).
 *  - {@link PdfDocumentParser}: .pdf, tramite caricamento dinamico e
 *    isolato di Apache PDFBox (si veda il Javadoc della classe per il
 *    motivo per cui non e' un dependency Maven/Gradle del progetto).
 */
public interface DocumentParser {

    /** true se questo parser sa gestire il file indicato (tipicamente in base all'estensione). */
    boolean supports(File file);

    /**
     * Estrae il testo "grezzo" del documento, pronto per essere inviato
     * come user prompt all'LLM. Non deve mai restituire null: se il
     * documento e' vuoto/non estraibile, deve lanciare IOException con un
     * messaggio comprensibile per l'utente finale del dialog.
     */
    String extractText(File file) throws IOException;
}
