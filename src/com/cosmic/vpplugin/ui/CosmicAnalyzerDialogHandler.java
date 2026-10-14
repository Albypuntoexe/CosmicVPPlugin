package com.cosmic.vpplugin.ui;

import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;
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
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class CosmicAnalyzerDialogHandler implements IDialogHandler, CosmicAnalysisListener {

    private static volatile boolean OPEN = false;

    public static void openOrNotify() {
        if (OPEN) { ApplicationManager.instance().getViewManager().showMessage("[COSMIC AI] Il pannello e' gia' aperto."); return; }
        ApplicationManager.instance().getViewManager().showDialog(new CosmicAnalyzerDialogHandler());
    }

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private IDialog dialog;
    private File pendingRequirementsFile;

    private final JTextArea logArea = new JTextArea();
    private final JButton chooseFileButton = new JButton("Seleziona documento (.docx / .pdf)...");
    private final JButton analyzeButton = new JButton("Analizza (invia all'LLM)");

    // NUOVO PULSANTE CSV
    private final JButton exportCsvButton = new JButton("Esporta Report CSV");

    private final JLabel statusLabel = new JLabel("Nessun file caricato.");
    private final JLabel cfpLabel = new JLabel(" ");
    private final JLabel scopeLabel = new JLabel(" ");

    // PROGRESS BAR AGGIORNATA
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel progressLabel = new JLabel("");

    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Nessuna analisi eseguita");
    private final JTree cosmicTree = new JTree(treeRoot);

    private final JTextArea chatArea = new JTextArea();
    private final JTextField chatInput = new JTextField();
    private final JButton chatSendButton = new JButton("Invia");
    private final JButton validateButton = new JButton("Valida Modello vs Requisiti");

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
        this.dialog = dialog; dialog.setTitle("COSMIC AI Analyzer"); dialog.setModal(false); dialog.setResizable(true); dialog.setSize(860, 680); OPEN = true;
    }

    @Override
    public void shown() { CosmicAiService.getInstance().setActiveUiListener(this); }

    @Override
    public boolean canClosed() { OPEN = false; CosmicAiService.getInstance().clearActiveUiListener(this); return true; }

    private Component buildAnalyzerTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel north = new JPanel(new BorderLayout(6, 6));
        chooseFileButton.addActionListener(e -> onChooseFileClicked());
        statusLabel.setForeground(new java.awt.Color(90, 90, 90));
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        fileRow.add(chooseFileButton, BorderLayout.WEST);
        fileRow.add(statusLabel, BorderLayout.CENTER);
        north.add(fileRow, BorderLayout.NORTH);

        JPanel actionsRow = new JPanel(new BorderLayout(6, 0));
        analyzeButton.setEnabled(false); analyzeButton.addActionListener(e -> onAnalyzeClicked());
        exportCsvButton.setEnabled(false); exportCsvButton.addActionListener(e -> onExportCsvClicked());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(analyzeButton);
        buttons.add(exportCsvButton);
        actionsRow.add(buttons, BorderLayout.WEST);

        JPanel progressPanel = new JPanel(new BorderLayout(4,0));
        progressBar.setStringPainted(true); progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(150, 20));
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(progressLabel, BorderLayout.SOUTH);
        actionsRow.add(progressPanel, BorderLayout.EAST);

        north.add(actionsRow, BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);

        logArea.setEditable(false); logArea.setLineWrap(true); logArea.setWrapStyleWord(true); logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        root.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel south = new JPanel(new java.awt.GridLayout(2, 1));
        cfpLabel.setFont(cfpLabel.getFont().deriveFont(Font.BOLD, 13f));
        scopeLabel.setFont(scopeLabel.getFont().deriveFont(Font.PLAIN, 12f)); scopeLabel.setForeground(new java.awt.Color(70, 70, 70));
        south.add(cfpLabel); south.add(scopeLabel);
        root.add(south, BorderLayout.SOUTH);
        return root;
    }

    private Component buildTreeTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8)); root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        cosmicTree.setRootVisible(true); cosmicTree.setShowsRootHandles(true);
        root.add(new JScrollPane(cosmicTree), BorderLayout.CENTER); return root;
    }

    private Component buildChatTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8)); root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        chatArea.setEditable(false); chatArea.setLineWrap(true); chatArea.setWrapStyleWord(true);
        root.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(6, 6));
        JPanel validateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); validateButton.addActionListener(e -> onValidateClicked()); validateRow.add(validateButton); south.add(validateRow, BorderLayout.NORTH);
        JPanel inputRow = new JPanel(new BorderLayout(6, 0)); chatInput.addActionListener(e -> onSendChatClicked()); chatSendButton.addActionListener(e -> onSendChatClicked()); inputRow.add(chatInput, BorderLayout.CENTER); inputRow.add(chatSendButton, BorderLayout.EAST); south.add(inputRow, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH); return root;
    }

    private void onChooseFileClicked() {
        JFileChooser chooser = ApplicationManager.instance().getViewManager().createJFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Requisiti (*.docx, *.pdf)", "docx", "pdf"));
        if (chooser.showOpenDialog(ApplicationManager.instance().getViewManager().getRootFrame()) == JFileChooser.APPROVE_OPTION) {
            pendingRequirementsFile = chooser.getSelectedFile();
            statusLabel.setText("Caricato: " + pendingRequirementsFile.getName());
            analyzeButton.setEnabled(true);
        }
    }

    private void onAnalyzeClicked() { if (pendingRequirementsFile != null) CosmicAiService.getInstance().analyzeDocument(pendingRequirementsFile, this); }

    // NUOVA AZIONE ESPORTA CSV
    private void onExportCsvClicked() {
        JFileChooser chooser = ApplicationManager.instance().getViewManager().createJFileChooser();
        chooser.setDialogTitle("Salva Report CSV");
        if (chooser.showSaveDialog(ApplicationManager.instance().getViewManager().getRootFrame()) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".csv")) f = new File(f.getAbsolutePath() + ".csv");
            CosmicAiService.getInstance().exportToCsv(f, this);
        }
    }

    private void onSendChatClicked() { /* [Invariato] */ }
    private void onValidateClicked() { /* [Invariato] */ }

    @Override
    public void onLog(String message) { logArea.append("[" + timeFormat.format(new Date()) + "] " + message + "\n"); logArea.setCaretPosition(logArea.getDocument().getLength()); }

    @Override
    public void onBusyStateChanged(boolean busy) {
        analyzeButton.setEnabled(!busy && pendingRequirementsFile != null);
        chooseFileButton.setEnabled(!busy);
        progressBar.setVisible(busy);
        if (busy) {
            progressBar.setIndeterminate(true);
            progressBar.setString("Preparazione...");
            exportCsvButton.setEnabled(false);
        } else {
            progressLabel.setText("");
        }
    }

    @Override
    public void onProgress(int current, int total, String stepDescription) {
        progressBar.setIndeterminate(false);
        progressBar.setMaximum(total);
        progressBar.setValue(current);
        progressBar.setString(current + "/" + total);
        progressLabel.setText(stepDescription);
    }

    @Override
    public void onAnalysisCompleted(CosmicJsonModel model, CosmicReport report) {
        cfpLabel.setText("TOTALE PROGETTO \"" + report.projectName + "\": " + report.totalCfp + " CFP");
        DefaultMutableTreeNode newRoot = CosmicTreeModelBuilder.buildTree(model, report);
        cosmicTree.setModel(new DefaultTreeModel(newRoot));
        for (int i = 0; i < cosmicTree.getRowCount(); i++) cosmicTree.expandRow(i);
        exportCsvButton.setEnabled(true); // Abilita l'esportazione CSV
    }

    @Override
    public void onAnalysisFailed(Throwable error) { onLog("ERRORE: " + error.getMessage()); }

    @Override
    public void onScopeChanged(String diagramName, CosmicReport scopedReport, CosmicReport projectTotalReport) {
        if (diagramName == null) scopeLabel.setText("Nessun diagramma attivo a fuoco.");
        else scopeLabel.setText("Diagramma attivo \"" + diagramName + "\": " + scopedReport.totalCfp + " CFP (Totale: " + projectTotalReport.totalCfp + " CFP)");
    }
}