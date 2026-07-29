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
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * UI ufficiale di COSMIC AI: implementa {@link IDialogHandler}, l'unica via
 * documentata da Visual Paradigm per mostrare una finestra Swing custom in
 * modo garantito funzionante sia in installazione standalone sia sotto IDE
 * Integration (si veda "Showing custom dialog" nella Plug-in User's Guide
 * ufficiale). A differenza del precedente pannello iniettato nel Message
 * Pane, qui il ciclo di vita della finestra (apertura, focus, chiusura,
 * modalita') e' gestito NATIVAMENTE da Visual Paradigm: nessun framework di
 * docking di terze parti puo' intercettare gli eventi prima di noi.
 *
 * Scelte di design:
 *  - Dialog NON modale: l'utente deve poter continuare a lavorare sul
 *    diagramma mentre l'LLM risponde (la chiamata di rete puo' durare fino
 *    a LLM_REQUEST_TIMEOUT) e, in previsione del ricalcolo in tempo reale
 *    futuro, la finestra deve poter restare aperta accanto all'editor.
 *  - Nessun DropTarget/Drag&Drop: rimosso per intero. L'unica via di
 *    caricamento file e' il file chooser ufficiale
 *    {@code ViewManager.createJFileChooser()}, esplicitamente raccomandato
 *    dalla documentazione VP al posto di {@code new JFileChooser()} perche'
 *    un JFileChooser "nudo" puo' comportarsi in modo imprevedibile quando
 *    Visual Paradigm e' eseguito dentro un IDE host non-Swing (SDE).
 *  - Singleton "soft": {@link #openOrNotify()} evita di aprire due dialog
 *    sovrapposti se l'utente clicca due volte (da menu e da context menu).
 */
public final class CosmicAnalyzerDialogHandler implements IDialogHandler, CosmicAnalysisListener {

    private static volatile boolean OPEN = false;

    /** Punto di ingresso unico, usato da entrambi gli Action Controller. */
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
    private String pendingRequirementsText;

    private final JTextArea logArea = new JTextArea();
    private final JButton chooseFileButton = new JButton("Seleziona file requisiti (.txt / .json)...");
    private final JButton analyzeButton = new JButton("Analizza (invia al modello COSMIC AI)");
    private final JButton demoButton = new JButton("Demo senza rete (esempio CosMet)");
    private final JLabel statusLabel = new JLabel("Nessun file caricato.");
    private final JLabel cfpLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();

    // Tab "Assistente" (predisposizione futura, gia' funzionante)
    private final JTextArea chatArea = new JTextArea();
    private final JTextField chatInput = new JTextField();
    private final JButton chatSendButton = new JButton("Invia");

    // ------------------------------------------------------------------
    // IDialogHandler
    // ------------------------------------------------------------------

    @Override
    public Component getComponent() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Analisi Use Case", buildAnalyzerTab());
        tabs.addTab("Assistente (beta)", buildChatTab());
        return tabs;
    }

    @Override
    public void prepare(IDialog dialog) {
        this.dialog = dialog;
        dialog.setTitle("COSMIC AI Analyzer");
        dialog.setModal(false);
        dialog.setResizable(true);
        dialog.setSize(760, 620);
        OPEN = true;
    }

    @Override
    public void shown() {
        log("Pannello pronto. Seleziona un file dei requisiti oppure premi \"Demo\" per un esempio "
                + "pre-caricato, poi premi \"Analizza\".");
    }

    @Override
    public boolean canClosed() {
        OPEN = false;
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
        demoButton.addActionListener(e -> onDemoClicked());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        buttons.add(analyzeButton);
        buttons.add(demoButton);
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

        cfpLabel.setFont(cfpLabel.getFont().deriveFont(Font.BOLD, 13f));
        root.add(cfpLabel, BorderLayout.SOUTH);

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
                "Chiedi informazioni su Use Case / COSMIC (stesso modello configurato per l'analisi)"));
        root.add(chatScroll, BorderLayout.CENTER);

        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        chatInput.addActionListener(e -> onSendChatClicked());
        chatSendButton.addActionListener(e -> onSendChatClicked());
        inputRow.add(chatInput, BorderLayout.CENTER);
        inputRow.add(chatSendButton, BorderLayout.EAST);
        root.add(inputRow, BorderLayout.SOUTH);

        return root;
    }

    // ------------------------------------------------------------------
    // Selezione file: SOLO tramite il file chooser ufficiale di VP.
    // ------------------------------------------------------------------

    private void onChooseFileClicked() {
        // ViewManager.createJFileChooser() e' la via raccomandata dalla
        // documentazione ufficiale VP al posto di "new JFileChooser()":
        // garantisce comportamento corretto anche quando Visual Paradigm
        // e' eseguito dentro un IDE host che non e' un'applicazione Swing
        // pura (es. integrazioni SDE su Eclipse/Visual Studio).
        JFileChooser chooser = ApplicationManager.instance().getViewManager().createJFileChooser();
        chooser.setDialogTitle("Seleziona il file dei requisiti");
        chooser.setFileFilter(new FileNameExtensionFilter("Requisiti (*.json, *.txt)", "json", "txt"));
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

    private void handleSelectedFile(File file) {
        String content = readFileOrNull(file);
        if (content == null) {
            statusLabel.setText("Errore: il file e' vuoto o non leggibile.");
            analyzeButton.setEnabled(false);
            pendingRequirementsText = null;
            return;
        }
        pendingRequirementsText = content;
        statusLabel.setText("Caricato: " + file.getName() + " (" + content.length() + " caratteri).");
        analyzeButton.setEnabled(true);
        log("File caricato: " + file.getName() + ". Premi \"Analizza\" per procedere.");
    }

    private String readFileOrNull(File file) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? null : content;
        } catch (IOException e) {
            log("Impossibile leggere il file '" + file.getName() + "': " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Azioni principali: delegano SEMPRE al Service (nessuna logica di
    // business qui dentro).
    // ------------------------------------------------------------------

    private void onAnalyzeClicked() {
        if (pendingRequirementsText == null) {
            return;
        }
        CosmicAiService.getInstance().analyzeRequirements(pendingRequirementsText, this);
    }

    private void onDemoClicked() {
        CosmicAiService.getInstance().analyzeWithMockData(this);
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
        analyzeButton.setEnabled(!busy && pendingRequirementsText != null);
        demoButton.setEnabled(!busy);
        chooseFileButton.setEnabled(!busy);
        progressBar.setVisible(busy);
    }

    @Override
    public void onAnalysisCompleted(CosmicJsonModel model, CosmicReport report) {
        cfpLabel.setText("Totale progetto \"" + report.projectName + "\": " + report.totalCfp + " CFP");
    }

    @Override
    public void onAnalysisFailed(Throwable error) {
        log("ERRORE: " + error.getMessage());
    }

    private void log(String message) {
        String line = "[" + timeFormat.format(new Date()) + "] " + message + "\n";
        logArea.append(line);
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
