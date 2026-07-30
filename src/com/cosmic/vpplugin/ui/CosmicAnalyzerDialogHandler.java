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
import java.awt.FlowLayout;
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
 * Integration. Il ciclo di vita della finestra (apertura, focus, chiusura,
 * modalita') e' gestito NATIVAMENTE da Visual Paradigm.
 *
 * NOVITA' di questa revisione:
 *  - Rimosso il percorso "Demo senza rete" (Task 1): non serve piu', la
 *    connettivita' verso l'LLM e' stabile tramite il tunnel LM Link.
 *  - Aggiunto un pulsante "Valida Modello vs Requisiti" nella tab
 *    Assistente (Task 3): richiama {@code CosmicAiService.validateModelAgainstRequirements}
 *    e stampa l'esito nella stessa {@code chatArea} usata dalla chat.
 *  - Il dialog si registra/deregistra come "UI listener attivo" del
 *    Service in {@link #shown()}/{@link #canClosed()} (Task 4): mentre e'
 *    aperto, riceve anche gli aggiornamenti scatenati da modifiche manuali
 *    al diagramma (nuovo Use Case disegnato, associazione rimossa, ...),
 *    tramite lo stesso {@code onAnalysisCompleted} usato per l'analisi LLM.
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
    private String pendingRequirementsText;

    private final JTextArea logArea = new JTextArea();
    private final JButton chooseFileButton = new JButton("Seleziona file requisiti (.txt / .json)...");
    private final JButton analyzeButton = new JButton("Analizza (invia al modello COSMIC AI)");
    private final JLabel statusLabel = new JLabel("Nessun file caricato.");
    private final JLabel cfpLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();

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
        // Task 4: da questo momento il Service puo' notificarci i ricalcoli
        // scatenati da modifiche manuali al diagramma.
        CosmicAiService.getInstance().setActiveUiListener(this);
        log("Pannello pronto. Seleziona un file dei requisiti, poi premi \"Analizza\".");
    }

    @Override
    public boolean canClosed() {
        OPEN = false;
        // Task 4: da qui in poi il dialog non esiste piu' come UI: il
        // Service continua a tenere in memoria il modello e a ricalcolare
        // in background, ma non deve piu' provare ad aggiornare componenti
        // Swing di questa finestra.
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
    // Selezione file: SOLO tramite il file chooser ufficiale di VP.
    // ------------------------------------------------------------------

    private void onChooseFileClicked() {
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

    /** Task 3: chiede all'LLM di controllare la copertura del modello generato rispetto ai requisiti originali. */
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
    // sull'EDT, quindi qui si puo' toccare Swing direttamente. Vengono
    // richiamati sia dall'analisi LLM sia dal ricalcolo live (Task 4).
    // ------------------------------------------------------------------

    @Override
    public void onLog(String message) {
        log(message);
    }

    @Override
    public void onBusyStateChanged(boolean busy) {
        analyzeButton.setEnabled(!busy && pendingRequirementsText != null);
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
