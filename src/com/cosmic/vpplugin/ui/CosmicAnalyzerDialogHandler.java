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

/**
 * UI ufficiale di COSMIC AI: implementa {@link IDialogHandler}, l'unica via
 * documentata da Visual Paradigm per mostrare una finestra Swing custom in
 * modo garantito funzionante sia in installazione standalone sia sotto IDE
 * Integration. Il ciclo di vita della finestra (apertura, focus, chiusura,
 * modalita') e' gestito NATIVAMENTE da Visual Paradigm.
 *
 * AGGIORNAMENTI v2.0 (Ultimate):
 *
 * Epic 1 (Document Processing Avanzato): il file chooser ora accetta anche
 * .pdf e .docx, oltre a .txt/.json. Non leggiamo piu' il file "a mano" in
 * questa classe: memorizziamo il {@link File} selezionato e deleghiamo
 * SEMPRE a {@code CosmicAiService.analyzeDocument(File, listener)}, che
 * internamente sceglie l'estrattore giusto (si veda il package
 * {@code com.cosmic.vpplugin.document}). Questo elimina la duplicazione
 * che c'era prima tra "leggi il file qui" e "manda il testo al service".
 *
 * Epic 2 (Scomposizione e Raggruppamento): la tab "Analisi Use Case" ora
 * mostra, oltre al log testuale (che resta per il dettaglio grezzo), un
 * {@link JTree} gerarchico Use Case -> Functional Process -> Data
 * Movement costruito da {@link CosmicTreeModelBuilder}: e' il modo con cui
 * l'utente "vede" a colpo d'occhio che un Use Case genera N Functional
 * Process distinti (o viceversa), come richiesto esplicitamente dal
 * professore.
 *
 * Epic 3 (Multi-Diagramma e Context-Awareness): una nuova label
 * ({@code scopeLabel}) mostra i CFP del diagramma attualmente attivo in VP
 * separatamente dal Totale Progetto ({@code cfpLabel}, invariato), tramite
 * il nuovo metodo {@link #onScopeChanged}.
 */
public final class CosmicAnalyzerDialogHandler implements IDialogHandler, CosmicAnalysisListener {

    private static volatile boolean OPEN = false;

    /** Punto di ingresso unico, usato dalla scorciatoia da tastiera globale. */
    public static void openOrNotify() {
        if (OPEN) {
            ApplicationManager.instance().getViewManager()
                    .showMessage("[COSMIC AI] Il pannello e' gia' aperto.");
            return;
        }
        ApplicationManager.instance().getViewManager().showDialog(new CosmicAnalyzerDialogHandler());
    }

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    private IDialog dialog;

    /** Epic 1: non teniamo piu' il testo gia' letto, ma il File: l'estrazione avviene nel Service. */
    private File pendingRequirementsFile;

    private final JTextArea logArea = new JTextArea();
    private final JButton chooseFileButton = new JButton("Seleziona documento requisiti (.txt / .json / .docx / .pdf)...");
    private final JButton analyzeButton = new JButton("Analizza (invia al modello COSMIC AI)");
    private final JLabel statusLabel = new JLabel("Nessun file caricato.");
    private final JLabel cfpLabel = new JLabel(" "); // Totale Progetto (invariato)
    private final JLabel scopeLabel = new JLabel(" "); // Epic 3: CFP del diagramma attivo
    private final JProgressBar progressBar = new JProgressBar();

    // Epic 2: struttura gerarchica COSMIC
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Nessuna analisi eseguita");
    private final JTree cosmicTree = new JTree(treeRoot);

    // Tab "Assistente"
    private final JTextArea chatArea = new JTextArea();
    private final JTextField chatInput = new JTextField();
    private final JButton chatSendButton = new JButton("Invia");
    private final JButton validateButton = new JButton("Valida Modello vs Requisiti");

    // ------------------------------------------------------------------
    // IDialogHandler
    // ------------------------------------------------------------------

    @Override
    public Component getComponent() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Analisi Use Case", buildAnalyzerTab());
        tabs.addTab("Struttura COSMIC", buildTreeTab());
        tabs.addTab("Assistente (beta)", buildChatTab());
        return tabs;
    }

    @Override
    public void prepare(IDialog dialog) {
        this.dialog = dialog;
        dialog.setTitle("COSMIC AI Analyzer");
        dialog.setModal(false);
        dialog.setResizable(true);
        dialog.setSize(860, 680);
        OPEN = true;
    }

    @Override
    public void shown() {
        // Task 4: da questo momento il Service puo' notificarci i ricalcoli
        // scatenati da modifiche manuali al diagramma, ed Epic 3 i cambi
        // di diagramma attivo.
        CosmicAiService.getInstance().setActiveUiListener(this);
        log("Pannello pronto. Seleziona un documento dei requisiti, poi premi \"Analizza\".");
    }

    @Override
    public boolean canClosed() {
        OPEN = false;
        CosmicAiService.getInstance().clearActiveUiListener(this);
        return true;
    }

    // ------------------------------------------------------------------
    // Costruzione UI
    // ------------------------------------------------------------------

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
        analyzeButton.setEnabled(false);
        analyzeButton.addActionListener(e -> onAnalyzeClicked());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(analyzeButton);
        actionsRow.add(buttons, BorderLayout.WEST);
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(120, progressBar.getPreferredSize().height));
        actionsRow.add(progressBar, BorderLayout.EAST);
        north.add(actionsRow, BorderLayout.SOUTH);

        root.add(north, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));
        root.add(logScroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new java.awt.GridLayout(2, 1));
        cfpLabel.setFont(cfpLabel.getFont().deriveFont(Font.BOLD, 13f));
        scopeLabel.setFont(scopeLabel.getFont().deriveFont(Font.PLAIN, 12f));
        scopeLabel.setForeground(new java.awt.Color(70, 70, 70));
        south.add(cfpLabel);
        south.add(scopeLabel);
        root.add(south, BorderLayout.SOUTH);

        return root;
    }

    /** Epic 2: nuova tab con il JTree gerarchico Use Case -> Functional Process -> Data Movement. */
    private Component buildTreeTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel hint = new JLabel(
                "Ogni Use Case puo' generare piu' Functional Process (o viceversa): questa vista rende "
                        + "esplicito il raggruppamento, non e' un semplice elenco 1:1.");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        root.add(hint, BorderLayout.NORTH);

        cosmicTree.setRootVisible(true);
        cosmicTree.setShowsRootHandles(true);
        JScrollPane treeScroll = new JScrollPane(cosmicTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Use Case -> Functional Process -> Data Movement"));
        root.add(treeScroll, BorderLayout.CENTER);

        return root;
    }

    private Component buildChatTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(BorderFactory.createTitledBorder(
                "Suggeritore COSMIC - chiedi informazioni sull'ultima analisi o sul metodo COSMIC"));
        root.add(chatScroll, BorderLayout.CENTER);

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

    // ------------------------------------------------------------------
    // Selezione file (Epic 1): SOLO tramite il file chooser ufficiale di VP,
    // ora estesso a .docx/.pdf oltre a .txt/.json.
    // ------------------------------------------------------------------

    private void onChooseFileClicked() {
        JFileChooser chooser = ApplicationManager.instance().getViewManager().createJFileChooser();
        chooser.setDialogTitle("Seleziona il documento dei requisiti");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Requisiti (*.json, *.txt, *.docx, *.pdf)", "json", "txt", "docx", "pdf"));
        chooser.setMultiSelectionEnabled(false);

        Component parent = ApplicationManager.instance().getViewManager().getRootFrame();
        int result = chooser.showOpenDialog(parent);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return;
        }
        handleSelectedFile(selected);
    }

    /**
     * Epic 1: non leggiamo/estraiamo piu' nulla qui. Ci limitiamo a
     * validare che il file esista e abilitare il pulsante "Analizza":
     * l'estrazione (txt/json diretta, docx via zip+xml, pdf via PDFBox
     * dinamico) avviene dentro {@code CosmicAiService.analyzeDocument},
     * cosi' che eventuali errori di estrazione (es. PDFBox non installato)
     * arrivino allo stesso identico canale di log/errore dell'analisi LLM.
     */
    private void handleSelectedFile(File file) {
        if (!file.isFile() || file.length() == 0) {
            statusLabel.setText("Errore: il file non esiste o e' vuoto.");
            analyzeButton.setEnabled(false);
            pendingRequirementsFile = null;
            return;
        }
        pendingRequirementsFile = file;
        statusLabel.setText("Caricato: " + file.getName() + " (" + (file.length() / 1024) + " KB).");
        analyzeButton.setEnabled(true);
        log("File caricato: " + file.getName() + ". Premi \"Analizza\" per procedere.");
    }

    // ------------------------------------------------------------------
    // Azioni principali: delegano SEMPRE al Service (nessuna logica di
    // business qui dentro).
    // ------------------------------------------------------------------

    private void onAnalyzeClicked() {
        if (pendingRequirementsFile == null) {
            return;
        }
        CosmicAiService.getInstance().analyzeDocument(pendingRequirementsFile, this);
    }

    private void onSendChatClicked() {
        String text = chatInput.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        chatInput.setText("");
        chatArea.append("Tu: " + text + "\n");
        chatSendButton.setEnabled(false);
        CosmicAiService.getInstance().sendChatMessage(text,
                reply -> {
                    chatArea.append("COSMIC AI: " + reply + "\n\n");
                    chatSendButton.setEnabled(true);
                },
                error -> {
                    chatArea.append("[Errore] " + error.getMessage() + "\n\n");
                    chatSendButton.setEnabled(true);
                });
    }

    private void onValidateClicked() {
        validateButton.setEnabled(false);
        chatArea.append("--- Validazione modello vs requisiti in corso... ---\n");
        CosmicAiService.getInstance().validateModelAgainstRequirements(
                result -> {
                    chatArea.append("Esito validazione:\n" + result + "\n\n");
                    validateButton.setEnabled(true);
                },
                error -> {
                    chatArea.append("[Errore validazione] " + error.getMessage() + "\n\n");
                    validateButton.setEnabled(true);
                });
    }

    // ------------------------------------------------------------------
    // CosmicAnalysisListener: il Service richiama SEMPRE questi metodi
    // sull'EDT, quindi qui si puo' toccare Swing direttamente.
    // ------------------------------------------------------------------

    @Override
    public void onLog(String message) {
        log(message);
    }

    @Override
    public void onBusyStateChanged(boolean busy) {
        analyzeButton.setEnabled(!busy && pendingRequirementsFile != null);
        chooseFileButton.setEnabled(!busy);
        progressBar.setVisible(busy);
    }

    @Override
    public void onAnalysisCompleted(CosmicJsonModel model, CosmicReport report) {
        cfpLabel.setText("TOTALE PROGETTO \"" + report.projectName + "\": " + report.totalCfp + " CFP");

        // Epic 2: ricostruisce il JTree gerarchico ad ogni analisi/ricalcolo.
        DefaultMutableTreeNode newRoot = CosmicTreeModelBuilder.buildTree(model, report);
        cosmicTree.setModel(new DefaultTreeModel(newRoot));
        for (int i = 0; i < cosmicTree.getRowCount(); i++) {
            cosmicTree.expandRow(i);
        }
    }

    @Override
    public void onAnalysisFailed(Throwable error) {
        log("ERRORE: " + error.getMessage());
    }

    /** Epic 3: aggiorna la label separata del diagramma attualmente attivo in VP. */
    @Override
    public void onScopeChanged(String diagramName, CosmicReport scopedReport, CosmicReport projectTotalReport) {
        if (diagramName == null) {
            scopeLabel.setText("Nessun diagramma attivo a fuoco.");
        } else {
            scopeLabel.setText("Diagramma attivo \"" + diagramName + "\": " + scopedReport.totalCfp
                    + " CFP  (Totale Progetto: " + projectTotalReport.totalCfp + " CFP)");
        }
    }

    private void log(String message) {
        String line = "[" + timeFormat.format(new Date()) + "] " + message + "\n";
        logArea.append(line);
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
