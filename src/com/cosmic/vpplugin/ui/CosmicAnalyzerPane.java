package com.cosmic.vpplugin.ui;

import com.cosmic.vpplugin.calculator.CosmicCalculator;
import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.generator.UseCaseDiagramGenerator;
import com.cosmic.vpplugin.mock.MockLlmResponse;
import com.cosmic.vpplugin.model.CosmicJsonMapper;
import com.cosmic.vpplugin.model.CosmicJsonModel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Pannello Swing autonomo del plugin COSMIC AI.
 *
 * A differenza della Fase 1 (dove il {@code DropTarget} era agganciato
 * direttamente al root frame di Visual Paradigm dentro {@code CosmicPlugin}),
 * qui il {@code DropTarget} e' di proprieta' esclusiva di QUESTO pannello:
 * non tocca in alcun modo la finestra principale di VP, quindi non puo' mai
 * interferire con un eventuale drop-handling nativo di VP sul suo root
 * frame.
 *
 * Il pannello viene mostrato tramite {@code ViewManager.showDialog(...)} con
 * un {@code IDialogHandler} configurato come NON modale (si veda
 * {@link CosmicAnalyzerDialogHandler}): questo e' il meccanismo di UI custom
 * realmente documentato dalla Open API di Visual Paradigm, il piu' vicino
 * disponibile a un "pannello agganciabile" persistente.
 */
public class CosmicAnalyzerPane extends JPanel implements DropTargetListener {

    private final JTextArea logArea = new JTextArea();
    private final JButton sendToOpenAiButton = new JButton("Invia ad OpenAI (Calcola COSMIC)");
    private final JLabel dropZoneLabel = new JLabel(
            "<html><div style='text-align:center;'>Trascina qui il file dei requisiti<br/>(.json / .txt)</div></html>",
            SwingConstants.CENTER);

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public CosmicAnalyzerPane() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(420, 380));

        add(buildDropZone(), BorderLayout.NORTH);
        add(buildLogArea(), BorderLayout.CENTER);
        add(buildActionBar(), BorderLayout.SOUTH);

        // Il DropTarget e' installato SOLO su questo pannello, non sul
        // root frame di Visual Paradigm.
        new DropTarget(dropZoneLabel, this);

        log("Pannello pronto. Trascina un file .json/.txt sulla Drop Zone per generare il diagramma.");
    }

    // ------------------------------------------------------------------
    // Costruzione UI
    // ------------------------------------------------------------------

    private Component buildDropZone() {
        dropZoneLabel.setPreferredSize(new Dimension(380, 110));
        dropZoneLabel.setOpaque(true);
        dropZoneLabel.setBackground(new Color(245, 247, 250));
        dropZoneLabel.setForeground(new Color(90, 90, 90));
        dropZoneLabel.setFont(dropZoneLabel.getFont().deriveFont(Font.PLAIN, 13f));
        dropZoneLabel.setBorder(new DashedBorder());
        return dropZoneLabel;
    }

    private Component buildLogArea() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Log"));
        return scroll;
    }

    private Component buildActionBar() {
        JPanel bar = new JPanel(new BorderLayout());
        // Mockato: la chiamata HTTP reale non e' ancora disponibile
        // (endpoint universitario non pronto). Il pulsante resta disabilitato
        // finche' quella integrazione non viene collegata.
        sendToOpenAiButton.setEnabled(false);
        sendToOpenAiButton.setToolTipText("Non ancora disponibile: in attesa dell'endpoint LLM universitario.");
        bar.add(sendToOpenAiButton, BorderLayout.CENTER);
        return bar;
    }

    private void log(String message) {
        String line = "[" + timeFormat.format(new Date()) + "] " + message + "\n";
        if (SwingUtilities.isEventDispatchThread()) {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        } else {
            SwingUtilities.invokeLater(() -> {
                logArea.append(line);
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }
    }

    // ------------------------------------------------------------------
    // Drag & Drop (spostato qui dalla Fase 1)
    // ------------------------------------------------------------------

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
                log("File ignorato: estensione non supportata (" + dropped.getName() + ")");
                return;
            }

            log("File ricevuto: " + dropped.getName());
            String jsonText = readFileOrFallback(dropped);
            processInBackground(jsonText);

        } catch (Exception ex) {
            dtde.dropComplete(false);
            log("ERRORE durante il drop: " + ex.getMessage());
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
                log("File vuoto: uso il mock interno di esempio.");
                return MockLlmResponse.JSON;
            }
            return content;
        } catch (Exception e) {
            log("Impossibile leggere il file, uso il mock interno: " + e.getMessage());
            return MockLlmResponse.JSON;
        }
    }

    private void processInBackground(String jsonText) {
        SwingUtilities.invokeLater(() -> {
            try {
                log("Parsing JSON e generazione diagramma in corso...");
                CosmicJsonModel model = CosmicJsonMapper.map(jsonText);
                new UseCaseDiagramGenerator().generate(model);
                log("Diagramma generato: " + model.useCases.size() + " Use Case, "
                        + model.actors.size() + " Attori.");

                // Fase 3: subito dopo il disegno UML, calcola i COSMIC Function
                // Point sullo stesso modello gia' mappato (nessun nuovo parsing).
                log("Calcolo COSMIC Function Points (CFP) in corso...");
                CosmicReport report = new CosmicCalculator().compute(model);
                log(report.toText());
                log("Misurazione completata: " + report.totalCfp + " CFP totali.");
            } catch (Exception e) {
                log("ERRORE nella generazione del diagramma o nel calcolo COSMIC: " + e.getMessage());
            }
        });
    }

    /**
     * Piccolo border tratteggiato "fatto in casa": il JDK standard non offre
     * un {@code BorderFactory.createDashedBorder(...)}, quindi lo si
     * implementa con un {@code BasicStroke} con pattern di dash, per dare
     * alla Drop Zone l'aspetto convenzionale di un'area di drop.
     */
    private static final class DashedBorder implements Border {
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(new Color(150, 150, 150));
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0, new float[] {6, 4}, 0));
            g2.drawRoundRect(x + 2, y + 2, width - 5, height - 5, 10, 10);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(8, 8, 8, 8);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }
}
