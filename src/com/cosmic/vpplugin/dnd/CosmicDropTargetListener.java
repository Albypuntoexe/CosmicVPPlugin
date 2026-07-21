package com.cosmic.vpplugin.dnd;

import com.cosmic.vpplugin.generator.UseCaseDiagramGenerator;
import com.cosmic.vpplugin.mock.MockLlmResponse;
import com.cosmic.vpplugin.model.CosmicJsonMapper;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.vp.plugin.ApplicationManager;

import javax.swing.SwingUtilities;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Gestisce il Drag & Drop di un file (.json o .txt) sulla finestra principale
 * di Visual Paradigm. Al drop, legge il contenuto del file (che in questa
 * fase mock rappresenta "la risposta che avrebbe dato l'LLM"), lo parsa e
 * genera il diagramma.
 *
 * Se il file trascinato non e' leggibile o e' vuoto, viene usato
 * {@link MockLlmResponse#JSON} come fallback, cosi' la demo funziona anche
 * trascinando un file placeholder qualsiasi.
 */
public class CosmicDropTargetListener implements DropTargetListener {

    @Override
    public void dragEnter(DropTargetDragEvent dtde) {
        if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            dtde.acceptDrag(DnDConstants.ACTION_COPY);
        } else {
            dtde.rejectDrag();
        }
    }

    @Override
    public void dragOver(DropTargetDragEvent dtde) {
        // nessuna azione necessaria
    }

    @Override
    public void dropActionChanged(DropTargetDragEvent dtde) {
        // nessuna azione necessaria
    }

    @Override
    public void dragExit(DropTargetEvent dte) {
        // nessuna azione necessaria
    }

    @Override
    public void drop(DropTargetDropEvent dtde) {
        try {
            if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                dtde.rejectDrop();
                return;
            }

            dtde.acceptDrop(DnDConstants.ACTION_COPY);
            Transferable transferable = dtde.getTransferable();

            @SuppressWarnings("unchecked")
            List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);

            dtde.dropComplete(true);

            if (files == null || files.isEmpty()) {
                return;
            }

            File dropped = files.get(0);
            if (!accepts(dropped)) {
                showMessage("[COSMIC AI] File ignorato: estensione non supportata (" + dropped.getName() + ")");
                return;
            }

            String jsonText = readFileOrFallback(dropped);
            processInBackground(jsonText);

        } catch (Exception ex) {
            dtde.dropComplete(false);
            showMessage("[COSMIC AI] Errore durante il drop: " + ex.getMessage());
        }
    }

    private boolean accepts(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".json") || name.endsWith(".txt");
    }

    private String readFileOrFallback(File file) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                showMessage("[COSMIC AI] File vuoto: uso il mock interno di esempio.");
                return MockLlmResponse.JSON;
            }
            return content;
        } catch (Exception e) {
            showMessage("[COSMIC AI] Impossibile leggere il file, uso il mock interno: " + e.getMessage());
            return MockLlmResponse.JSON;
        }
    }

    /**
     * Il parsing JSON e la generazione del diagramma toccano il modello e la
     * UI di Visual Paradigm: per sicurezza vengono eseguiti sull'Event
     * Dispatch Thread (come richiesto da qualunque manipolazione Swing/UI).
     */
    private void processInBackground(String jsonText) {
        SwingUtilities.invokeLater(() -> {
            try {
                CosmicJsonModel model = CosmicJsonMapper.map(jsonText);
                new UseCaseDiagramGenerator().generate(model);
                showMessage("[COSMIC AI] Diagramma generato con successo: "
                        + model.useCases.size() + " Use Case, "
                        + model.actors.size() + " Attori.");
            } catch (Exception e) {
                showMessage("[COSMIC AI] Errore nella generazione del diagramma: " + e.getMessage());
            }
        });
    }

    private void showMessage(String msg) {
        ApplicationManager.instance().getViewManager().showMessage(msg);
    }
}
