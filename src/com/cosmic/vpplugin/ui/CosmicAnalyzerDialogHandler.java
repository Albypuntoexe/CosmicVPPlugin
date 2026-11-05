package com.cosmic.vpplugin.ui;

import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;
import com.cosmic.vpplugin.service.CosmicAiService;
import com.cosmic.vpplugin.service.CosmicAnalysisListener;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.view.IDialog;
import com.vp.plugin.view.IDialogHandler;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Pannello "COSMIC AI Analyzer" (v3.0).
 *
 * Contratto di threading: il {@link CosmicAiService} invoca TUTTI i metodi di {@link CosmicAnalysisListener}
 * sull'Event Dispatch Thread, quindi qui si toccano direttamente i componenti Swing.
 *
 * Novita' rispetto alla v2:
 * <ul>
 *   <li>progress bar DETERMINATA (use case completati / totali) con descrizione dello step corrente e stima del tempo;</li>
 *   <li>albero "Struttura COSMIC" e CFP totali aggiornati dopo OGNI use case (non a fine analisi);</li>
 *   <li>pulsante "Interrompi": gli use case gia' elaborati restano nel diagramma e nel report;</li>
 *   <li>chat e validazione modello-vs-requisiti realmente collegate al Service.</li>
 * </ul>
 */
public final class CosmicAnalyzerDialogHandler implements IDialogHandler, CosmicAnalysisListener {

    private static volatile boolean OPEN = false;

    public static void openOrNotify() {
        if (OPEN) {
            ApplicationManager.instance().getViewManager().showMessage("[COSMIC AI] Il pannello e' gia' aperto.");
            return;
        }
        ApplicationManager.instance().getViewManager().showDialog(new CosmicAnalyzerDialogHandler());
    }

    private static final int MAX_LOG_CHARS = 200_000;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private IDialog dialog;
    private File pendingRequirementsFile;
    private boolean busy;

    private final JTextArea logArea = new JTextArea();
    private final JButton chooseFileButton = new JButton("Seleziona documento (.docx / .pdf / .txt)...");
    private final JButton analyzeButton = new JButton("Analizza (invia all'LLM)");
    private final JButton cancelButton = new JButton("Interrompi");
    private final JButton exportCsvButton = new JButton("Esporta Report CSV");

    private final JLabel statusLabel = new JLabel("Nessun file caricato.");
    private final JLabel cfpLabel = new JLabel(" ");
    private final JLabel scopeLabel = new JLabel(" ");

    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel progressLabel = new JLabel(" ");

    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Nessuna analisi eseguita");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree cosmicTree = new JTree(treeModel);
    private String treeProjectName = "";

    private final JTextArea chatArea = new JTextArea();
    private final JTextField chatInput = new JTextField();
    private final JButton chatSendButton = new JButton("Invia");
    private final JButton validateButton = new JButton("Valida Modello vs Requisiti");

    // ------------------------------------------------------------------ IDialogHandler

    @Override
    public Component getComponent() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Analisi Use Case", buildAnalyzerTab());
        tabs.addTab("Struttura COSMIC", buildTreeTab());
        tabs.addTab("Assistente", buildChatTab());
        return tabs;
    }

    @Override
    public void prepare(IDialog dialog) {
        this.dialog = dialog;
        dialog.setTitle("COSMIC AI Analyzer");
        dialog.setModal(false);
        dialog.setResizable(true);
        dialog.setSize(900, 700);
        OPEN = true;
    }

    @Override
    public void shown() {
        CosmicAiService service = CosmicAiService.getInstance();
        service.setActiveUiListener(this);
        if (service.isAnalysisRunning()) {   // pannello riaperto durante un'analisi
            setBusy(true);
            progressBar.setIndeterminate(true);
        }
    }

    @Override
    public boolean canClosed() {
        OPEN = false;
        CosmicAiService.getInstance().clearActiveUiListener(this);
        return true;
    }

    // ------------------------------------------------------------------ costruzione UI

    private Component buildAnalyzerTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel north = new JPanel(new BorderLayout(6, 6));
        chooseFileButton.addActionListener(e -> onChooseFileClicked());
        statusLabel.setForeground(new Color(90, 90, 90));
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        fileRow.add(chooseFileButton, BorderLayout.WEST);
        fileRow.add(statusLabel, BorderLayout.CENTER);
        north.add(fileRow, BorderLayout.NORTH);

        analyzeButton.setEnabled(false);
        analyzeButton.addActionListener(e -> onAnalyzeClicked());
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> onCancelClicked());
        exportCsvButton.setEnabled(false);
        exportCsvButton.addActionListener(e -> onExportCsvClicked());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(analyzeButton);
        buttons.add(cancelButton);
        buttons.add(exportCsvButton);

        progressBar.setStringPainted(true);
        progressBar.setString(" ");
        progressBar.setPreferredSize(new Dimension(220, 20));
        progressLabel.setFont(progressLabel.getFont().deriveFont(Font.PLAIN, 11f));
        progressLabel.setForeground(new Color(70, 70, 70));

        JPanel progressPanel = new JPanel(new BorderLayout(0, 2));
        progressPanel.add(progressBar, BorderLayout.NORTH);
        progressPanel.add(progressLabel, BorderLayout.SOUTH);

        JPanel actionsRow = new JPanel(new BorderLayout(6, 4));
        actionsRow.add(buttons, BorderLayout.NORTH);
        actionsRow.add(progressPanel, BorderLayout.SOUTH);
        north.add(actionsRow, BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        root.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel south = new JPanel(new GridLayout(2, 1));
        cfpLabel.setFont(cfpLabel.getFont().deriveFont(Font.BOLD, 13f));
        scopeLabel.setFont(scopeLabel.getFont().deriveFont(Font.PLAIN, 12f));
        scopeLabel.setForeground(new Color(70, 70, 70));
        south.add(cfpLabel);
        south.add(scopeLabel);
        root.add(south, BorderLayout.SOUTH);
        return root;
    }

    private Component buildTreeTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        cosmicTree.setRootVisible(true);
        cosmicTree.setShowsRootHandles(true);
        root.add(new JScrollPane(cosmicTree), BorderLayout.CENTER);
        return root;
    }

    private Component buildChatTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        root.add(new JScrollPane(chatArea), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(6, 6));
        JPanel validateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        validateButton.addActionListener(e -> onValidateClicked());
        validateRow.add(validateButton);
        south.add(validateRow, BorderLayout.NORTH);

        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        chatInput.addActionListener(e -> onSendChatClicked());
        chatSendButton.addActionListener(e -> onSendChatClicked());
        inputRow.add(chatInput, BorderLayout.CENTER);
        inputRow.add(chatSendButton, BorderLayout.EAST);
        south.add(inputRow, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        return root;
    }

    // ------------------------------------------------------------------ azioni utente

    private void onChooseFileClicked() {
        JFileChooser chooser = ApplicationManager.instance().getViewManager().createJFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Requisiti (*.docx, *.pdf, *.txt)", "docx", "pdf", "txt"));
        if (chooser.showOpenDialog(ApplicationManager.instance().getViewManager().getRootFrame()) == JFileChooser.APPROVE_OPTION) {
            pendingRequirementsFile = chooser.getSelectedFile();
            statusLabel.setText("Caricato: " + pendingRequirementsFile.getName());
            analyzeButton.setEnabled(!busy);
        }
    }

    private void onAnalyzeClicked() {
        if (pendingRequirementsFile != null) {
            CosmicAiService.getInstance().analyzeDocument(pendingRequirementsFile, this);
        }
    }

    private void onCancelClicked() {
        cancelButton.setEnabled(false);
        progressLabel.setText("Interruzione in corso (attendo la fine della richiesta LLM corrente)...");
        CosmicAiService.getInstance().cancelAnalysis();
    }

    private void onExportCsvClicked() {
        JFileChooser chooser = ApplicationManager.instance().getViewManager().createJFileChooser();
        chooser.setDialogTitle("Salva Report CSV");
        String base = pendingRequirementsFile == null ? "cosmic-report" : stripExtension(pendingRequirementsFile.getName()) + "-cosmic";
        chooser.setSelectedFile(new File(base + ".csv"));
        if (chooser.showSaveDialog(ApplicationManager.instance().getViewManager().getRootFrame()) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".csv")) f = new File(f.getAbsolutePath() + ".csv");
            CosmicAiService.getInstance().exportToCsv(f, this);
        }
    }

    private void onSendChatClicked() {
        final String text = chatInput.getText().trim();
        if (text.isEmpty()) return;
        chatInput.setText("");
        appendChat("Tu", text);
        setChatBusy(true);
        CosmicAiService.getInstance().sendChatMessage(text,
                reply -> { appendChat("Assistente", reply); setChatBusy(false); },
                err -> { appendChat("Errore", String.valueOf(err.getMessage())); setChatBusy(false); });
    }

    private void onValidateClicked() {
        appendChat("Sistema", "Validazione del modello corrente rispetto ai requisiti...");
        setChatBusy(true);
        CosmicAiService.getInstance().validateModelAgainstRequirements(
                report -> { appendChat("Validazione", report); setChatBusy(false); },
                err -> { appendChat("Errore", String.valueOf(err.getMessage())); setChatBusy(false); });
    }

    private void setChatBusy(boolean b) {
        chatSendButton.setEnabled(!b);
        validateButton.setEnabled(!b);
    }

    private void appendChat(String who, String text) {
        chatArea.append("[" + timeFormat.format(new Date()) + "] " + who + ":\n" + text + "\n\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    // ------------------------------------------------------------------ CosmicAnalysisListener (tutti sull'EDT)

    @Override
    public void onLog(String message) {
        logArea.append("[" + timeFormat.format(new Date()) + "] " + message + "\n");
        int len = logArea.getDocument().getLength();
        if (len > MAX_LOG_CHARS) {                       // il log di 67 use case non deve crescere senza limite
            try { logArea.getDocument().remove(0, len - MAX_LOG_CHARS / 2); } catch (Exception ignored) { }
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    @Override
    public void onBusyStateChanged(boolean isBusy) {
        setBusy(isBusy);
        if (isBusy) {
            // nuova analisi: azzera albero, totale e barra
            treeProjectName = pendingRequirementsFile == null ? "" : stripExtension(pendingRequirementsFile.getName());
            treeRoot.removeAllChildren();
            treeRoot.setUserObject(CosmicTreeModelBuilder.rootLabel(treeProjectName, 0));
            treeModel.reload();
            cfpLabel.setText("TOTALE PROGETTO: 0 CFP (analisi in corso...)");
            progressBar.setIndeterminate(true);
            progressBar.setString("Preparazione...");
            progressLabel.setText("Estrazione e suddivisione del documento...");
        } else {
            progressBar.setIndeterminate(false);
        }
    }

    private void setBusy(boolean isBusy) {
        busy = isBusy;
        analyzeButton.setEnabled(!isBusy && pendingRequirementsFile != null);
        chooseFileButton.setEnabled(!isBusy);
        cancelButton.setEnabled(isBusy);
        if (isBusy) exportCsvButton.setEnabled(false);
    }

    @Override
    public void onProgress(int current, int total, String stepDescription) {
        progressBar.setIndeterminate(false);
        progressBar.setMaximum(Math.max(1, total));
        progressBar.setValue(Math.min(current, total));
        progressBar.setString(current + " / " + total + " use case");
        progressLabel.setText(stepDescription);
    }

    /** Rendering incrementale della UI: aggiunge UN nodo all'albero e aggiorna il totale, senza ricostruire nulla. */
    @Override
    public void onUseCaseProcessed(UseCase useCase, UseCaseReport useCaseReport, CosmicReport projectReport,
                                   int processed, int total) {
        DefaultMutableTreeNode node = CosmicTreeModelBuilder.buildUseCaseNode(useCase, useCaseReport);
        treeModel.insertNodeInto(node, treeRoot, treeRoot.getChildCount());
        treeRoot.setUserObject(CosmicTreeModelBuilder.rootLabel(projectReport.projectName, projectReport.totalCfp));
        treeModel.nodeChanged(treeRoot);
        if (treeRoot.getChildCount() == 1) cosmicTree.expandRow(0);
        cfpLabel.setText("TOTALE PROGETTO \"" + projectReport.projectName + "\": " + projectReport.totalCfp
                + " CFP  (" + processed + "/" + total + " use case)");
        exportCsvButton.setEnabled(false);
    }

    /** Fine analisi (anche parziale/interrotta) e sincronizzazioni successive: ricostruisce l'albero in modo coerente. */
    @Override
    public void onAnalysisCompleted(CosmicJsonModel model, CosmicReport report) {
        cfpLabel.setText("TOTALE PROGETTO \"" + report.projectName + "\": " + report.totalCfp + " CFP");
        DefaultMutableTreeNode newRoot = CosmicTreeModelBuilder.buildTree(model, report);
        treeRoot.removeAllChildren();
        treeRoot.setUserObject(newRoot.getUserObject());
        while (newRoot.getChildCount() > 0) {
            treeRoot.add((DefaultMutableTreeNode) newRoot.getChildAt(0));
        }
        treeModel.reload();
        cosmicTree.expandRow(0);                      // un solo livello aperto: 67 UC espansi sarebbero illeggibili
        if (!busy) {
            exportCsvButton.setEnabled(!model.useCases.isEmpty());
            progressBar.setIndeterminate(false);
            if (progressBar.getMaximum() > 0 && progressBar.getValue() >= progressBar.getMaximum()) {
                progressLabel.setText("Analisi terminata.");
            }
        }
    }

    @Override
    public void onModelSynchronized(CosmicJsonModel model, CosmicReport report, String reason) {
        onLog(reason);
        onAnalysisCompleted(model, report);
    }

    @Override
    public void onAnalysisFailed(Throwable error) {
        String msg = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        onLog("ERRORE: " + msg);
        progressBar.setIndeterminate(false);
        progressLabel.setText("Errore: " + msg);
    }

    @Override
    public void onScopeChanged(String diagramName, CosmicReport scopedReport, CosmicReport projectTotalReport) {
        if (diagramName == null) {
            scopeLabel.setText("Nessun diagramma attivo a fuoco.");
        } else {
            scopeLabel.setText("Diagramma attivo \"" + diagramName + "\": " + scopedReport.totalCfp
                    + " CFP (Totale: " + projectTotalReport.totalCfp + " CFP)");
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
