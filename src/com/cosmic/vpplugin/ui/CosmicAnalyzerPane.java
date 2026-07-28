package com.cosmic.vpplugin.ui;

import com.cosmic.vpplugin.calculator.CosmicCalculator;
import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.generator.UseCaseDiagramGenerator;
import com.cosmic.vpplugin.json.MiniJsonParser;
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
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pannello Swing autonomo del plugin COSMIC AI.
 *
 * FASE 6: questo pannello non e' piu' mostrato tramite
 * {@code ViewManager.showDialog(...)} (Fase 1-5), ma iniettato DIRETTAMENTE
 * come scheda nativa nel Message Pane da {@code CosmicPlugin} tramite
 * {@code ViewManager.showMessagePaneComponent(id, title, this)}. Per questo
 * motivo non forza piu' una {@code setPreferredSize(...)} pensata per una
 * finestra fluttuante: la scheda del Message Pane gestisce lei stessa lo
 * spazio disponibile.
 *
 * Il {@code DropTarget} resta di proprieta' esclusiva di QUESTO pannello
 * (non tocca in alcun modo la finestra principale di VP), cosi' come in
 * tutte le fasi precedenti.
 *
 * FASE 6 (Task B): il pulsante "Invia ad OpenAI" ora effettua una vera
 * chiamata HTTP POST, in un thread separato, verso un server LLM compatibile
 * con l'API "chat completions" di OpenAI, in esecuzione sulla rete
 * dell'universita'. Il flusso e':
 *
 *  1) L'utente droppa un file di testo/JSON con i requisiti in linguaggio
 *     naturale (Use Case, scenari, eccezioni...): il contenuto viene SOLO
 *     memorizzato (si veda {@link #pendingRequirementsText}) e loggato,
 *     NON piu' passato direttamente a {@link CosmicJsonMapper#map}: il
 *     file droppato non e' garantito essere gia' nel formato
 *     {@link CosmicJsonModel} (anzi, nel caso reale non lo e' quasi mai:
 *     e' testo libero che deve prima passare dall'LLM).
 *  2) Al click su "Invia ad OpenAI", il testo memorizzato viene inviato
 *     all'endpoint LLM insieme a un prompt di sistema che ne descrive lo
 *     schema JSON di output atteso (lo stesso letto/scritto da
 *     {@link CosmicJsonMapper}).
 *  3) La risposta del server (un payload "chat completions") viene
 *     analizzata con {@link MiniJsonParser} (nessuna nuova dipendenza
 *     esterna, in linea con la scelta architetturale già fatta per il
 *     parsing del JSON di dominio) per estrarre il testo generato
 *     dall'assistente, che DOVREBBE essere il JSON dei requisiti COSMIC
 *     (con un margine di tolleranza per eventuali code fence Markdown
 *     ```json ... ``` che l'LLM potrebbe comunque produrre nonostante le
 *     istruzioni contrarie nel prompt).
 *  4) Quel JSON viene infine passato alla pipeline già esistente
 *     ({@link #processInBackground}): parsing con {@link CosmicJsonMapper},
 *     disegno del diagramma, calcolo COSMIC.
 */
public class CosmicAnalyzerPane extends JPanel implements DropTargetListener {

    /**
     * Endpoint del server LLM universitario. Costante isolata qui in cima
     * alla classe per essere facilmente modificabile (es. cambio di IP/porta
     * quando l'endpoint verra' spostato in produzione).
     */
    private static final String LLM_ENDPOINT_URL = "http://192.168.4.62:2345/v1/chat/completions";

    /** Modello richiesto esplicitamente nel payload, come da specifica. */
    private static final String LLM_MODEL = "openai/gpt-oss-20b";

    /** Timeout generoso: modelli locali/da 20B possono essere lenti su hardware universitario. */
    private static final Duration LLM_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final JTextArea logArea = new JTextArea();
    private final JButton sendToOpenAiButton = new JButton("Invia ad OpenAI (Calcola COSMIC)");
    private final JLabel dropZoneLabel = new JLabel(
            "<html><div style='text-align:center;'>Trascina qui il file dei requisiti<br/>(.json / .txt)</div></html>",
            SwingConstants.CENTER);

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    /** Client HTTP condiviso (thread-safe, riutilizzabile per tutte le richieste). */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * Unico thread dedicato alle chiamate all'LLM: evita di bloccare l'EDT
     * (Swing) durante la richiesta HTTP, che puo' durare a lungo. Thread
     * daemon: non deve impedire la chiusura della JVM ospite (Visual
     * Paradigm) se il plugin viene scaricato senza un arresto esplicito.
     */
    private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cosmic-ai-llm-call");
        t.setDaemon(true);
        return t;
    });

    /**
     * Testo dei requisiti dell'ultimo file droppato con successo, in
     * attesa di essere inviato all'LLM. {@code null} se nessun file e'
     * ancora stato caricato (o se l'ultimo era vuoto/non leggibile).
     */
    private String pendingRequirementsText;

    public CosmicAnalyzerPane() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildDropZone(), BorderLayout.NORTH);
        add(buildLogArea(), BorderLayout.CENTER);
        add(buildActionBar(), BorderLayout.SOUTH);

        // FIX: Espandiamo il DropTarget a TUTTO il pannello, inclusa la console dei log
        this.setDropTarget(new DropTarget(this, this));
        dropZoneLabel.setDropTarget(new DropTarget(dropZoneLabel, this));
        logArea.setDropTarget(new DropTarget(logArea, this));

        log("Pannello pronto. Trascina un file .json/.txt ovunque su questo pannello, poi premi "
                + "'Invia ad OpenAI' per generare il diagramma e calcolare i CFP.");
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
        // Fase 6: endpoint reale collegato, il pulsante e' ora attivo.
        sendToOpenAiButton.setToolTipText("Invia il testo dei requisiti caricato al server LLM ("
                + LLM_ENDPOINT_URL + ", modello " + LLM_MODEL + ") e genera diagramma + CFP dalla risposta.");
        sendToOpenAiButton.addActionListener(e -> onSendToOpenAiClicked());
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

            String content = readFileOrNull(dropped);
            if (content == null) {
                log("Errore: Il file caricato è vuoto o non valido. "
                        + "Inserisci un file JSON contenente i requisiti.");
                pendingRequirementsText = null;
                return; // esecuzione interrotta: nessun fallback automatico al mock
            }

            // Fase 6: NON si generano piu' diagramma/CFP direttamente da
            // qui. Il contenuto e' testo libero di requisiti, destinato
            // all'LLM: viene solo memorizzato, in attesa del click su
            // "Invia ad OpenAI".
            pendingRequirementsText = content;
            log("File ricevuto: " + dropped.getName() + " (" + content.length() + " caratteri). "
                    + "Premi 'Invia ad OpenAI (Calcola COSMIC)' per avviare l'analisi.");

        } catch (Exception ex) {
            dtde.dropComplete(false);
            log("ERRORE durante il drop: " + ex.getMessage());
        }
    }

    private boolean accepts(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".json") || name.endsWith(".txt");
    }

    /**
     * Legge il contenuto del file droppato. Restituisce {@code null} se il
     * file e' vuoto o non leggibile; e' compito del chiamante ({@link #drop})
     * decidere come reagire (attualmente: loggare l'errore e interrompere
     * l'esecuzione, senza alcun fallback automatico a dati mock).
     */
    private String readFileOrNull(File file) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return null;
            }
            return content;
        } catch (Exception e) {
            log("Impossibile leggere il file '" + file.getName() + "': " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Fase 6 (Task B): chiamata reale all'LLM universitario
    // ------------------------------------------------------------------

    private void onSendToOpenAiClicked() {
        String requirementsText = pendingRequirementsText;
        if (requirementsText == null || requirementsText.isEmpty()) {
            log("Errore: nessun file caricato. Trascina un file dei requisiti nella Drop Zone "
                    + "prima di premere questo pulsante.");
            return;
        }

        sendToOpenAiButton.setEnabled(false);
        log("Invio richiesta al server LLM (" + LLM_ENDPOINT_URL + ", modello " + LLM_MODEL + ")...");

        // Chiamata HTTP + parsing eseguiti sul thread dedicato: non blocca
        // l'EDT di Visual Paradigm. Ogni aggiornamento di UI/log viene poi
        // rimandato sull'EDT con SwingUtilities.invokeLater.
        llmExecutor.submit(() -> callLlmAndHandle(requirementsText));
    }

    private void callLlmAndHandle(String requirementsText) {
        try {
            String responseBody = callLlm(requirementsText);
            String assistantContent = extractAssistantContent(responseBody);
            String cleanedJson = stripMarkdownFences(assistantContent);

            SwingUtilities.invokeLater(() -> {
                log("Risposta LLM ricevuta (" + cleanedJson.length() + " caratteri). "
                        + "Avvio parsing, generazione diagramma e calcolo COSMIC...");
                sendToOpenAiButton.setEnabled(true);
                processInBackground(cleanedJson);
            });
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> {
                log("ERRORE durante la chiamata all'LLM: " + ex.getMessage());
                sendToOpenAiButton.setEnabled(true);
            });
        }
    }

    /**
     * Esegue la vera chiamata HTTP POST verso {@link #LLM_ENDPOINT_URL}.
     * Eseguito SEMPRE su {@link #llmExecutor}, mai sull'EDT.
     */
    private String callLlm(String requirementsText) throws IOException, InterruptedException {
        String requestBody = buildChatCompletionRequestJson(buildSystemPrompt(), requirementsText);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LLM_ENDPOINT_URL))
                .timeout(LLM_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() / 100 != 2) {
            throw new IOException("Il server LLM ha risposto con codice HTTP "
                    + response.statusCode() + ": " + truncate(response.body(), 500));
        }
        return response.body();
    }

    /**
     * Prompt di sistema: descrive all'LLM esattamente lo schema JSON "piatto"
     * che {@link CosmicJsonMapper} sa leggere (includesIds/extendsList come
     * chiavi dirette dell'oggetto useCase, non annidate in "relations" - si
     * veda il javadoc di CosmicJsonMapper per il motivo storico di questa
     * scelta).
     */
    private String buildSystemPrompt() {
        return "Sei un assistente esperto di UML Use Case e del metodo COSMIC (Functional Size "
                + "Measurement). Riceverai una descrizione testuale in linguaggio naturale di uno o "
                + "piu' Use Case (attori, scenario principale, eccezioni). Il tuo compito e' restituire "
                + "ESCLUSIVAMENTE un oggetto JSON valido, senza alcun testo aggiuntivo, senza spiegazioni "
                + "e senza delimitatori Markdown (niente ```), con questa struttura esatta:\n"
                + "{\n"
                + "  \"projectName\": string,\n"
                + "  \"actors\": [ { \"id\": string, \"name\": string, \"description\": string } ],\n"
                + "  \"useCases\": [\n"
                + "    {\n"
                + "      \"id\": string,\n"
                + "      \"name\": string,\n"
                + "      \"primaryActorId\": string,\n"
                + "      \"specification\": string,\n"
                + "      \"mainScenario\": [ string, ... ],\n"
                + "      \"exceptions\": [ string, ... ],\n"
                + "      \"includesIds\": [ string, ... ],\n"
                + "      \"extendsList\": [ { \"targetId\": string, \"extensionPoint\": string } ],\n"
                + "      \"functionalProcesses\": [\n"
                + "        {\n"
                + "          \"fpId\": string,\n"
                + "          \"fpName\": string,\n"
                + "          \"triggeringEvent\": string,\n"
                + "          \"subProcesses\": [\n"
                + "            {\n"
                + "              \"step\": number,\n"
                + "              \"description\": string,\n"
                + "              \"functionalUser\": string,\n"
                + "              \"dataMovementType\": \"E\"|\"X\"|\"R\"|\"W\"|null,\n"
                + "              \"dataGroup\": string|null,\n"
                + "              \"objectOfInterest\": string|null\n"
                + "            }\n"
                + "          ]\n"
                + "        }\n"
                + "      ]\n"
                + "    }\n"
                + "  ]\n"
                + "}\n"
                + "Applica le regole COSMIC standard per identificare i Data Movement (Entry, Exit, "
                + "Read, Write) di ogni sotto-processo. Se un sotto-processo non comporta alcun "
                + "movimento dati, usa null per dataMovementType. Non aggiungere campi diversi da "
                + "quelli elencati e non omettere campi richiesti.";
    }

    /**
     * Costruisce manualmente il corpo JSON della richiesta "chat completions"
     * (nessuna libreria esterna: coerente con la scelta architetturale già
     * fatta per {@link MiniJsonParser}).
     */
    private String buildChatCompletionRequestJson(String systemPrompt, String userRequirementsText) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"model\":\"").append(escapeJson(LLM_MODEL)).append("\",");
        sb.append("\"temperature\":0,");
        sb.append("\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(systemPrompt)).append("\"},");
        sb.append("{\"role\":\"user\",\"content\":\"").append(escapeJson(userRequirementsText)).append("\"}");
        sb.append("]");
        sb.append('}');
        return sb.toString();
    }

    /**
     * Estrae {@code choices[0].message.content} dalla risposta "chat
     * completions", usando {@link MiniJsonParser} (nessuna libreria JSON
     * esterna). Lancia {@link IllegalArgumentException} con un messaggio
     * chiaro se la struttura attesa non e' presente, invece di propagare una
     * ClassCastException/NullPointerException poco leggibile nel log.
     */
    @SuppressWarnings("unchecked")
    private String extractAssistantContent(String responseBody) {
        Object root = MiniJsonParser.parse(responseBody);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Risposta LLM non e' un oggetto JSON valido.");
        }
        Object choicesObj = ((Map<String, Object>) root).get("choices");
        if (!(choicesObj instanceof List) || ((List<Object>) choicesObj).isEmpty()) {
            throw new IllegalArgumentException("Risposta LLM priva del campo 'choices' atteso: "
                    + truncate(responseBody, 300));
        }
        Object firstChoice = ((List<Object>) choicesObj).get(0);
        if (!(firstChoice instanceof Map)) {
            throw new IllegalArgumentException("Il primo elemento di 'choices' non e' un oggetto JSON.");
        }
        Object messageObj = ((Map<String, Object>) firstChoice).get("message");
        if (!(messageObj instanceof Map)) {
            throw new IllegalArgumentException("Campo 'message' assente in choices[0].");
        }
        Object contentObj = ((Map<String, Object>) messageObj).get("content");
        if (contentObj == null) {
            throw new IllegalArgumentException("Campo 'content' assente in choices[0].message.");
        }
        return String.valueOf(contentObj);
    }

    /**
     * Rimuove eventuali code fence Markdown (```json ... ``` oppure ``` ... ```)
     * che l'LLM potrebbe comunque aggiungere nonostante le istruzioni del
     * prompt di sistema, cosi' che il testo passato a
     * {@link CosmicJsonMapper#map} sia JSON puro.
     */
    private String stripMarkdownFences(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        String withoutOpeningFence = (firstNewline >= 0) ? trimmed.substring(firstNewline + 1) : trimmed;
        int closingFenceIndex = withoutOpeningFence.lastIndexOf("```");
        String withoutClosingFence = (closingFenceIndex >= 0)
                ? withoutOpeningFence.substring(0, closingFenceIndex)
                : withoutOpeningFence;
        return withoutClosingFence.trim();
    }

    /** Escaping JSON minimale per incorporare stringhe arbitrarie nel body della richiesta. */
    private String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLength) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }

    // ------------------------------------------------------------------
    // Pipeline diagramma + calcolo COSMIC (invariata dalla Fase 3),
    // ora invocata SOLO con il JSON prodotto dall'LLM, non piu' con il
    // testo grezzo droppato dall'utente.
    // ------------------------------------------------------------------

    private void processInBackground(String jsonText) {
        SwingUtilities.invokeLater(() -> {
            try {
                log("Parsing JSON e generazione diagramma in corso...");
                CosmicJsonModel model = CosmicJsonMapper.map(jsonText);
                new UseCaseDiagramGenerator().generate(model);
                log("Diagramma generato: " + model.useCases.size() + " Use Case, "
                        + model.actors.size() + " Attori.");

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
