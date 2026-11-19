package com.cosmic.vpplugin.ui;

import com.cosmic.vpplugin.calculator.CosmicCalculator;
import com.cosmic.vpplugin.calculator.CosmicCalculator.AreaReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;
import com.cosmic.vpplugin.service.CosmicAiService;
import com.cosmic.vpplugin.service.CosmicAiService.ManualField;
import com.cosmic.vpplugin.service.CosmicAnalysisListener;
import com.cosmic.vpplugin.settings.CosmicSettings;
import com.cosmic.vpplugin.ui.CosmicTreeModelBuilder.NodeData;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.view.IDialog;
import com.vp.plugin.view.IDialogHandler;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *
 * Novita' v4.0:
 * <ul>
 *   <li>tab "Struttura COSMIC": selezionando uno use case o un functional process compare una tabella i cui
 *       Functional User / Data Movement / Data Group / Object of Interest sono MODIFICABILI; ogni modifica passa da
 *       {@link CosmicAiService#applyManualEdit} che ricalcola subito i CFP e aggiorna gli stereotipi UML, senza LLM;</li>
 *   <li>pulsante "Rimisura Progetto da VP": rilegge nome e Specification di ogni use case dal canvas e rilancia la
 *       pipeline; l'albero viene aggiornato use case per use case (upsert) conservando espansione e selezione;</li>
 *   <li>tab "Impostazioni": endpoint, modello, API key e timeout dell'LLM ({@link CosmicSettings}, Preferences).</li>
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

    // --- v4.0: editing manuale dei data movement
    private static final String[] DM_CHOICES = {"(nessuno)", "Entry", "Exit", "Read", "Write"};
    private final SubProcessTableModel editTableModel = new SubProcessTableModel();
    private final JTable editTable = new JTable(editTableModel);
    private final JLabel editTitleLabel = new JLabel("Seleziona uno use case o un functional process per correggere i suoi data movement.");
    private final JLabel editStatusLabel = new JLabel(" ");
    private final Map<String, UseCase> ucById = new LinkedHashMap<>();
    private boolean incrementalRun;

    // --- v4.0: rimisura da VP (stesso comando, due punti di accesso)
    private final JButton remeasureButton = new JButton("Rimisura Progetto da VP");
    private final JButton remeasureButton2 = new JButton("Rimisura Progetto da VP");

    // --- v4.0: impostazioni LLM
    private final JTextField endpointField = new JTextField();
    private final JTextField modelField = new JTextField();
    private final JPasswordField apiKeyField = new JPasswordField();
    private final JCheckBox showKeyBox = new JCheckBox("Mostra");
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(
            CosmicSettings.DEFAULT_TIMEOUT_SEC, CosmicSettings.MIN_TIMEOUT_SEC, CosmicSettings.MAX_TIMEOUT_SEC, 30));
    private final JLabel settingsStatusLabel = new JLabel(" ");

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
        tabs.addTab("Impostazioni", buildSettingsTab());
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
        loadSettingsIntoForm();
        if (service.isAnalysisRunning()) {   // pannello riaperto durante un'analisi
            setBusy(true);
            progressBar.setIndeterminate(true);
        } else {
            service.publishCurrentModelTo(this);   // v4.0: mostra subito il modello gia' in memoria / ripristinato dal .vpp
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
        buttons.add(remeasureButton);
        buttons.add(cancelButton);
        buttons.add(exportCsvButton);
        remeasureButton.setToolTipText("Rilegge nome e Specification di ogni use case dal canvas di Visual Paradigm e li rimisura");
        remeasureButton.addActionListener(e -> onRemeasureClicked());

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

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        remeasureButton2.setToolTipText(remeasureButton.getToolTipText());
        remeasureButton2.addActionListener(e -> onRemeasureClicked());
        toolbar.add(remeasureButton2);
        JLabel hint = new JLabel("Seleziona un nodo: la tabella sotto permette di correggere i data movement (CFP ricalcolati subito).");
        hint.setForeground(new Color(90, 90, 90));
        toolbar.add(hint);
        root.add(toolbar, BorderLayout.NORTH);

        cosmicTree.setRootVisible(true);
        cosmicTree.setShowsRootHandles(true);
        cosmicTree.addTreeSelectionListener(e -> showEditorForSelection());

        JPanel editor = new JPanel(new BorderLayout(4, 4));
        editTitleLabel.setFont(editTitleLabel.getFont().deriveFont(Font.BOLD, 12f));
        editor.add(editTitleLabel, BorderLayout.NORTH);
        configureEditTable();
        editor.add(new JScrollPane(editTable), BorderLayout.CENTER);
        editStatusLabel.setFont(editStatusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        editStatusLabel.setForeground(new Color(70, 70, 70));
        editor.add(editStatusLabel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(cosmicTree), editor);
        split.setResizeWeight(0.55);
        split.setContinuousLayout(true);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    private void configureEditTable() {
        editTable.setRowHeight(22);
        editTable.setFillsViewportHeight(true);
        editTable.setSurrendersFocusOnKeystroke(true);
        editTable.getTableHeader().setReorderingAllowed(false);
        int[] widths = {90, 30, 330, 120, 110, 130, 130, 40, 100};
        for (int i = 0; i < widths.length; i++) {
            editTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        editTable.setDefaultRenderer(Object.class, new SubProcessRenderer());
        JComboBox<String> dmCombo = new JComboBox<>(DM_CHOICES);
        editTable.getColumnModel().getColumn(SubProcessTableModel.COL_DM).setCellEditor(new DefaultCellEditor(dmCombo));
    }

    private Component buildSettingsTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        addRow(form, c, 0, "Endpoint URL:", endpointField, null);
        addRow(form, c, 1, "Model Name:", modelField, null);
        showKeyBox.addActionListener(e -> apiKeyField.setEchoChar(showKeyBox.isSelected() ? (char) 0 : '\u2022'));
        addRow(form, c, 2, "API Key (opzionale):", apiKeyField, showKeyBox);
        addRow(form, c, 3, "Timeout richiesta (sec):", timeoutSpinner, null);

        JButton saveButton = new JButton("Salva");
        saveButton.addActionListener(e -> onSaveSettingsClicked());
        JButton resetButton = new JButton("Ripristina predefiniti");
        resetButton.addActionListener(e -> onResetSettingsClicked());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(saveButton);
        buttons.add(resetButton);
        c.gridx = 1; c.gridy = 4; c.gridwidth = 2; c.fill = GridBagConstraints.NONE;
        form.add(buttons, c);
        c.gridy = 5;
        form.add(settingsStatusLabel, c);

        JTextArea note = new JTextArea(
                "Le impostazioni valgono dalla prossima richiesta all'LLM (non serve riavviare Visual Paradigm).\n"
              + "L'endpoint deve essere compatibile con l'API OpenAI /v1/chat/completions (LM Studio, Ollama, OpenAI, ...).\n"
              + "Se l'API Key e' compilata viene inviata come header \"Authorization: Bearer <key>\"; se e' vuota non si invia alcuna autenticazione.\n"
              + "Attenzione: la API Key e' salvata IN CHIARO nelle preferenze utente del sistema operativo (java.util.prefs).");
        note.setEditable(false);
        note.setOpaque(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setForeground(new Color(80, 80, 80));

        root.add(form, BorderLayout.NORTH);
        root.add(note, BorderLayout.CENTER);
        return root;
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label, Component field, Component extra) {
        c.gridy = row; c.gridwidth = 1;
        c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        if (field instanceof JSpinner) {
            ((JSpinner) field).setPreferredSize(new Dimension(100, 24));
            c.fill = GridBagConstraints.NONE;
        }
        form.add(field, c);
        c.gridx = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        if (extra != null) form.add(extra, c);
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

    /** v4.0 - "Rimisura Progetto da VP": chiede conferma e la modalita', poi delega al Service. */
    private void onRemeasureClicked() {
        if (busy) return;
        Object[] options = {"Solo nuovi/modificati", "Tutti gli use case", "Annulla"};
        String msg = "Verranno letti dal canvas di Visual Paradigm il NOME e il testo del campo Specification di ogni use case,\n"
                + "e ogni frase sara' rimisurata con una chiamata all'LLM (puo' richiedere molto tempo).\n\n"
                + "\u2022 Solo nuovi/modificati: rimisura gli use case nuovi o il cui testo e' cambiato; gli altri\n"
                + "   conservano misura e correzioni manuali.\n"
                + "\u2022 Tutti gli use case: rimisura tutto (le correzioni manuali vengono SOVRASCRITTE).\n\n"
                + "Relazioni <<include>>/<<extend>>, attori e use case cancellati vengono riallineati con VP.";
        int choice = JOptionPane.showOptionDialog(ApplicationManager.instance().getViewManager().getRootFrame(), msg,
                "Rimisura Progetto da VP", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice != 0 && choice != 1) return;
        CosmicAiService.getInstance().remeasureFromVp(choice == 0, this);
    }

    // ------------------------------------------------------------------ impostazioni LLM

    private void loadSettingsIntoForm() {
        CosmicSettings.Snapshot cfg = CosmicSettings.load();
        endpointField.setText(cfg.endpointUrl);
        modelField.setText(cfg.modelName);
        apiKeyField.setText(cfg.apiKey);
        timeoutSpinner.setValue(cfg.timeoutSec);
    }

    private void onSaveSettingsClicked() {
        try {
            String saved = CosmicSettings.save(endpointField.getText(), modelField.getText(),
                    new String(apiKeyField.getPassword()), ((Number) timeoutSpinner.getValue()).intValue());
            endpointField.setText(saved);
            modelField.setText(modelField.getText().trim());
            settingsStatusLabel.setForeground(new Color(0, 120, 0));
            settingsStatusLabel.setText("Impostazioni salvate (" + timeFormat.format(new Date()) + "). Valgono dalla prossima richiesta.");
        } catch (IllegalArgumentException ex) {
            settingsStatusLabel.setForeground(new Color(180, 0, 0));
            settingsStatusLabel.setText(ex.getMessage());
        }
    }

    private void onResetSettingsClicked() {
        CosmicSettings.resetToDefaults();
        loadSettingsIntoForm();
        settingsStatusLabel.setForeground(new Color(0, 120, 0));
        settingsStatusLabel.setText("Impostazioni ripristinate ai valori predefiniti.");
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
                err -> {
                    if (err instanceof CosmicAiService.RequirementsMissingException) {
                        offerReattachRequirements(err.getMessage());   // modello senza requisiti salvati: si ricollega il documento
                    } else {
                        appendChat("Errore", String.valueOf(err.getMessage()));
                        setChatBusy(false);
                    }
                });
    }

    /**
     * Il modello (es. salvato con una versione precedente, o rimisurato da VP) non ha requisiti associati: invece di
     * un errore senza uscita si chiede il documento. Si usa quello gia' caricato nella tab Analisi, se c'e', altrimenti
     * si apre il selettore file. Nessuna nuova misura: solo estrazione del testo + salvataggio nel sidecar.
     */
    private void offerReattachRequirements(String reason) {
        File doc = pendingRequirementsFile;
        if (doc == null) {
            JFileChooser chooser = ApplicationManager.instance().getViewManager().createJFileChooser();
            chooser.setDialogTitle("Seleziona il documento dei requisiti da ricollegare al modello");
            chooser.setFileFilter(new FileNameExtensionFilter("Requisiti (*.docx, *.pdf, *.txt)", "docx", "pdf", "txt"));
            if (chooser.showOpenDialog(ApplicationManager.instance().getViewManager().getRootFrame()) != JFileChooser.APPROVE_OPTION) {
                appendChat("Sistema", reason);
                setChatBusy(false);
                return;
            }
            doc = chooser.getSelectedFile();
        }
        appendChat("Sistema", "Ricollego i requisiti dal documento '" + doc.getName() + "' (nessuna nuova misura)...");
        CosmicAiService.getInstance().attachRequirementsDocument(doc,
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
    public void onRunStarted(boolean incremental) {
        incrementalRun = incremental;
    }

    @Override
    public void onBusyStateChanged(boolean isBusy) {
        setBusy(isBusy);
        if (isBusy) {
            progressBar.setIndeterminate(true);
            if (incrementalRun) {
                // rimisura da VP: l'albero resta visibile e viene aggiornato use case per use case
                cfpLabel.setText("TOTALE PROGETTO: rimisura in corso...");
                progressBar.setString("Lettura del progetto da VP...");
                progressLabel.setText("Lettura di use case e specification da Visual Paradigm...");
            } else {
                // nuova analisi del documento: azzera albero, tabella, totale e barra
                treeProjectName = pendingRequirementsFile == null ? "" : stripExtension(pendingRequirementsFile.getName());
                clearTree(CosmicTreeModelBuilder.rootLabel(treeProjectName, 0));
                cfpLabel.setText("TOTALE PROGETTO: 0 CFP (analisi in corso...)");
                progressBar.setString("Preparazione...");
                progressLabel.setText("Estrazione e suddivisione del documento...");
            }
        } else {
            progressBar.setIndeterminate(false);
        }
    }

    private void clearTree(String rootLabel) {
        treeRoot.removeAllChildren();
        treeRoot.setUserObject(rootLabel);
        treeModel.reload();
        ucById.clear();
        editTableModel.setRows(new ArrayList<>(), false);
        editTitleLabel.setText("Seleziona uno use case o un functional process per correggere i suoi data movement.");
        editStatusLabel.setText(" ");
    }

    private void setBusy(boolean isBusy) {
        busy = isBusy;
        analyzeButton.setEnabled(!isBusy && pendingRequirementsFile != null);
        chooseFileButton.setEnabled(!isBusy);
        remeasureButton.setEnabled(!isBusy);
        remeasureButton2.setEnabled(!isBusy);
        cancelButton.setEnabled(isBusy);
        if (isBusy) exportCsvButton.setEnabled(false);
        editTableModel.setEditable(!isBusy);        // niente correzioni manuali mentre la pipeline scrive sul modello
    }

    @Override
    public void onProgress(int current, int total, String stepDescription) {
        progressBar.setIndeterminate(false);
        progressBar.setMaximum(Math.max(1, total));
        progressBar.setValue(Math.min(current, total));
        progressBar.setString(current + " / " + total + " use case");
        progressLabel.setText(stepDescription);
    }

    /**
     * Rendering incrementale: UPSERT del nodo dello use case (sostituisce quello esistente, mantenendo posizione ed
     * espansione, oppure ne aggiunge uno) e aggiorna il totale, senza ricostruire l'intero albero.
     */
    @Override
    public void onUseCaseProcessed(UseCase useCase, UseCaseReport useCaseReport, CosmicReport projectReport,
                                   int processed, int total) {
        ucById.put(useCase.id, useCase);
        DefaultMutableTreeNode node = CosmicTreeModelBuilder.buildUseCaseNode(useCase, useCaseReport);
        // v5.0: lo use case vive dentro il nodo della sua area funzionale (creato al primo use case dell'area)
        AreaReport areaReport = projectReport.findArea(useCase.areaId);
        DefaultMutableTreeNode areaNode = CosmicTreeModelBuilder.findAreaNode(treeRoot, useCase.areaId);
        if (areaNode == null && areaReport != null) {
            areaNode = CosmicTreeModelBuilder.buildAreaNode(areaReport);
            treeModel.insertNodeInto(areaNode, treeRoot, treeRoot.getChildCount());
        }
        DefaultMutableTreeNode parent = areaNode != null ? areaNode : treeRoot;
        DefaultMutableTreeNode old = CosmicTreeModelBuilder.findUseCaseNode(treeRoot, useCase.id);
        if (old == null) {
            treeModel.insertNodeInto(node, parent, parent.getChildCount());
        } else {
            Set<String> expanded = expandedKeys();
            Object selected = selectedKey();
            DefaultMutableTreeNode oldParent = (DefaultMutableTreeNode) old.getParent();
            int index = oldParent.getIndex(old);
            treeModel.removeNodeFromParent(old);
            treeModel.insertNodeInto(node, oldParent, index);
            restoreTreeState(expanded, selected, false);
        }
        if (areaNode != null && areaReport != null) {                    // totale CFP dell'area aggiornato
            areaNode.setUserObject(((DefaultMutableTreeNode) CosmicTreeModelBuilder.buildAreaNode(areaReport)).getUserObject());
            treeModel.nodeChanged(areaNode);
        }
        treeRoot.setUserObject(CosmicTreeModelBuilder.rootLabel(projectReport.projectName, projectReport.totalCfp));
        treeModel.nodeChanged(treeRoot);
        if (treeRoot.getChildCount() == 1) cosmicTree.expandRow(0);
        cfpLabel.setText("TOTALE PROGETTO \"" + projectReport.projectName + "\": " + projectReport.totalCfp
                + " CFP  (" + processed + "/" + total + " use case)");
        exportCsvButton.setEnabled(false);
    }

    /**
     * Fine analisi (anche parziale/interrotta), ripristino dal .vpp, correzione manuale e sincronizzazioni con VP:
     * ricostruisce l'albero conservando nodi espansi e selezione, e rinfresca la tabella di editing.
     */
    @Override
    public void onAnalysisCompleted(CosmicJsonModel model, CosmicReport report) {
        cfpLabel.setText("TOTALE PROGETTO \"" + report.projectName + "\": " + report.totalCfp + " CFP");
        Set<String> expanded = expandedKeys();
        Object selected = selectedKey();
        boolean firstBuild = treeRoot.getChildCount() == 0 || !(treeRoot.getUserObject() instanceof NodeData);

        ucById.clear();
        for (UseCase uc : model.allUseCases()) ucById.put(uc.id, uc);

        DefaultMutableTreeNode newRoot = CosmicTreeModelBuilder.buildTree(model, report);
        treeRoot.removeAllChildren();
        treeRoot.setUserObject(newRoot.getUserObject());
        while (newRoot.getChildCount() > 0) {
            treeRoot.add((DefaultMutableTreeNode) newRoot.getChildAt(0));
        }
        treeModel.reload();
        restoreTreeState(expanded, selected, firstBuild);   // al primo disegno un solo livello aperto: 67 UC espansi sarebbero illeggibili
        // la tabella viene ricostruita dopo che l'eventuale cell editor in corso ha terminato il commit
        SwingUtilities.invokeLater(this::showEditorForSelection);

        if (!busy) {
            exportCsvButton.setEnabled(model.hasUseCases());
            progressBar.setIndeterminate(false);
            if (progressBar.getMaximum() > 0 && progressBar.getValue() >= progressBar.getMaximum()) {
                progressLabel.setText("Analisi terminata.");
            }
        }
    }

    /** Il progetto attivo non ha un modello COSMIC salvato: svuota albero e tabella. */
    @Override
    public void onModelCleared() {
        clearTree("Nessuna analisi eseguita");
        cfpLabel.setText(" ");
        exportCsvButton.setEnabled(false);
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

    // ------------------------------------------------------------------ stato dell'albero e tabella di editing

    /** Chiave stabile di un nodo (sopravvive alla ricostruzione dell'albero): kind|ucId|fpId. */
    private static String keyOf(DefaultMutableTreeNode n) {
        NodeData d = CosmicTreeModelBuilder.dataOf(n);
        if (d == null) return n.isRoot() ? "ROOT" : null;
        if (d.kind == NodeData.Kind.ROOT) return "ROOT";
        if (d.kind == NodeData.Kind.AREA) return "AREA|" + d.areaId;
        if (d.kind == NodeData.Kind.USE_CASE) return "UC|" + d.ucId;
        if (d.kind == NodeData.Kind.FUNCTIONAL_PROCESS) return "FP|" + d.ucId + "|" + d.fpId;
        return null;   // sotto-processi e righe informative: non servono come chiave di ripristino
    }

    private Set<String> expandedKeys() {
        Set<String> keys = new HashSet<>();
        Enumeration<TreePath> paths = cosmicTree.getExpandedDescendants(new TreePath(treeRoot));
        while (paths != null && paths.hasMoreElements()) {
            Object last = paths.nextElement().getLastPathComponent();
            if (last instanceof DefaultMutableTreeNode) {
                String k = keyOf((DefaultMutableTreeNode) last);
                if (k != null) keys.add(k);
            }
        }
        return keys;
    }

    /** Chiave del nodo selezionato (se e' un sotto-processo/info, quella del suo FP o use case padre). */
    private Object selectedKey() {
        TreePath sel = cosmicTree.getSelectionPath();
        if (sel == null) return null;
        for (Object o = sel.getLastPathComponent(); o instanceof DefaultMutableTreeNode; o = ((DefaultMutableTreeNode) o).getParent()) {
            String k = keyOf((DefaultMutableTreeNode) o);
            if (k != null) return k;
        }
        return null;
    }

    private void restoreTreeState(Set<String> expanded, Object selectedKey, boolean defaultExpandRoot) {
        if (defaultExpandRoot && expanded.isEmpty()) {
            cosmicTree.expandRow(0);
        } else {
            expanded.add("ROOT");
            expandMatching(treeRoot, expanded);
        }
        if (selectedKey != null) {
            DefaultMutableTreeNode target = findByKey(treeRoot, selectedKey.toString());
            if (target != null) {
                TreePath path = new TreePath(target.getPath());
                cosmicTree.setSelectionPath(path);
            }
        }
    }

    private void expandMatching(DefaultMutableTreeNode node, Set<String> keys) {
        String k = keyOf(node);
        if (k != null && keys.contains(k) && !node.isLeaf()) {
            cosmicTree.expandPath(new TreePath(node.getPath()));
        }
        for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
            expandMatching((DefaultMutableTreeNode) e.nextElement(), keys);
        }
    }

    private DefaultMutableTreeNode findByKey(DefaultMutableTreeNode node, String key) {
        if (key.equals(keyOf(node))) return node;
        for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
            DefaultMutableTreeNode hit = findByKey((DefaultMutableTreeNode) e.nextElement(), key);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Riempie la tabella con i sotto-processi dello use case / FP selezionato nell'albero. */
    private void showEditorForSelection() {
        if (editTable.isEditing()) editTable.getCellEditor().cancelCellEditing();
        TreePath sel = cosmicTree.getSelectionPath();
        NodeData d = sel == null ? null : CosmicTreeModelBuilder.dataOf(sel.getLastPathComponent());
        UseCase uc = (d == null || d.ucId == null) ? null : ucById.get(d.ucId);
        if (uc == null) {
            editTableModel.setRows(new ArrayList<>(), false);
            editTitleLabel.setText("Seleziona uno use case o un functional process per correggere i suoi data movement.");
            return;
        }
        List<Row> rows = new ArrayList<>();
        CosmicCalculator calculator = new CosmicCalculator();
        for (FunctionalProcess fp : uc.functionalProcesses) {
            if (d.fpId != null && !d.fpId.equals(fp.fpId)) continue;
            int[] cfp = calculator.cfpPerSubProcess(fp);
            for (int i = 0; i < fp.subProcesses.size(); i++) {
                rows.add(new Row(uc, fp, fp.subProcesses.get(i), i < cfp.length ? cfp[i] : 0));
            }
        }
        editTableModel.setRows(rows, !busy);
        editTitleLabel.setText("[" + uc.id + "] " + uc.name + (d.fpId == null ? "" : " - " + d.fpId)
                + "  (" + rows.size() + " sotto-processi; celle modificabili: Functional User, Data Movement, Data Group, Object of Interest)");
    }

    /** Riga della tabella di editing: riferimenti agli oggetti VIVI del modello + CFP calcolato per quel sotto-processo. */
    private static final class Row {
        final UseCase uc;
        final FunctionalProcess fp;
        final SubProcess sp;
        final int cfp;

        Row(UseCase uc, FunctionalProcess fp, SubProcess sp, int cfp) {
            this.uc = uc;
            this.fp = fp;
            this.sp = sp;
            this.cfp = cfp;
        }
    }

    private static String dmToLabel(String type) {
        if (type == null) return DM_CHOICES[0];
        switch (type) {
            case "E": return "Entry";
            case "X": return "Exit";
            case "R": return "Read";
            case "W": return "Write";
            default:  return type;
        }
    }

    private final class SubProcessTableModel extends AbstractTableModel {
        static final int COL_FP = 0, COL_STEP = 1, COL_DESC = 2, COL_USER = 3, COL_DM = 4, COL_DG = 5, COL_OOI = 6, COL_CFP = 7, COL_STATE = 8;
        private final String[] names = {"FP", "#", "Frase", "Functional User", "Data Movement", "Data Group", "Object of Interest", "CFP", "Stato"};
        private List<Row> rows = new ArrayList<>();
        private boolean editable;

        void setRows(List<Row> newRows, boolean editable) {
            this.rows = newRows;
            this.editable = editable;
            fireTableDataChanged();
        }

        void setEditable(boolean editable) {
            if (this.editable != editable) {
                this.editable = editable && !rows.isEmpty();
                fireTableDataChanged();
            }
        }

        Row rowAt(int i) { return rows.get(i); }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return names.length; }
        @Override public String getColumnName(int c) { return names[c]; }

        @Override
        public boolean isCellEditable(int r, int c) {
            return editable && (c == COL_USER || c == COL_DM || c == COL_DG || c == COL_OOI);
        }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            switch (c) {
                case COL_FP:    return row.fp.fpId;
                case COL_STEP:  return row.sp.step;
                case COL_DESC:  return row.sp.description;
                case COL_USER:  return row.sp.functionalUser == null ? "" : row.sp.functionalUser;
                case COL_DM:    return dmToLabel(row.sp.dataMovementType);
                case COL_DG:    return row.sp.dataGroup == null ? "" : row.sp.dataGroup;
                case COL_OOI:   return row.sp.objectOfInterest == null ? "" : row.sp.objectOfInterest;
                case COL_CFP:   return row.cfp;
                case COL_STATE: return row.sp.unmeasured ? "NON MISURATA" : (row.sp.manuallyEdited ? "modificata" : "");
                default:        return "";
            }
        }

        /** Delega al Service: ricalcolo CFP, stereotipi su VP, salvataggio nel .vpp e ripubblicazione avvengono li'. */
        @Override
        public void setValueAt(Object value, int r, int c) {
            if (!editable || r < 0 || r >= rows.size()) return;
            Row row = rows.get(r);
            ManualField field;
            switch (c) {
                case COL_USER: field = ManualField.FUNCTIONAL_USER; break;
                case COL_DM:   field = ManualField.DATA_MOVEMENT; break;
                case COL_DG:   field = ManualField.DATA_GROUP; break;
                case COL_OOI:  field = ManualField.OBJECT_OF_INTEREST; break;
                default: return;
            }
            String text = value == null ? "" : value.toString();
            if (field == ManualField.DATA_MOVEMENT && text.equals(DM_CHOICES[0])) text = "No data movement";
            boolean applied = CosmicAiService.getInstance().applyManualEdit(row.uc.id, row.fp.fpId, row.sp.step, field, text);
            editStatusLabel.setText(applied
                    ? "Modifica applicata a [" + row.uc.id + "] passo " + row.sp.step + ": CFP ricalcolati."
                    : "Nessuna modifica applicata (valore invariato o non valido, oppure analisi in corso).");
        }
    }

    /** Evidenzia le righe non misurate (arancio) e quelle corrette a mano (azzurro). */
    private final class SubProcessRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected && row < editTableModel.getRowCount()) {
                SubProcess sp = editTableModel.rowAt(row).sp;
                if (sp.unmeasured) c.setBackground(new Color(255, 232, 190));
                else if (sp.manuallyEdited) c.setBackground(new Color(222, 236, 255));
                else c.setBackground(table.getBackground());
            }
            setToolTipText(column == SubProcessTableModel.COL_DESC && value != null ? value.toString() : null);
            return c;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
