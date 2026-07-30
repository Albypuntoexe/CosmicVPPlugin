package com.cosmic.vpplugin.document;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Estrazione per file di testo semplice (.txt) o JSON di requisiti gia'
 * strutturato (.json). E' la stessa logica che prima viveva direttamente in
 * {@code CosmicAnalyzerDialogHandler.readFileOrNull}: qui e' stata estratta
 * cosi' da essere selezionabile dalla {@link DocumentParserFactory} insieme
 * ai nuovi formati (Epic 1) senza duplicare codice nella UI.
 */
public final class PlainTextDocumentParser implements DocumentParser {

    @Override
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".json");
    }

    @Override
    public String extractText(File file) throws IOException {
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            throw new IOException("Il file '" + file.getName() + "' e' vuoto.");
        }
        return content;
    }
}
