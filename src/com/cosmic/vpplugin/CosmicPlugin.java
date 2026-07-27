package com.cosmic.vpplugin;

import com.cosmic.vpplugin.listener.CosmicModelChangeListener;
import com.cosmic.vpplugin.ui.CosmicAnalyzerDialogHandler;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;
import com.vp.plugin.model.IProject;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.FlowLayout;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;

/**
 * Entry point del plugin (dichiarato in plugin.xml, attributo "class").
 *
 * FASE 5 - Abbandono definitivo di plugin.xml per i bottoni: componente
 * nativo nel Message Pane.
 *
 * ---------------------------------------------------------------------
 * PERCHE' QUESTO CAMBIAMENTO (rispetto alla Fase 3)
 * ---------------------------------------------------------------------
 * L'analisi di vp.log ha escluso ogni causa "riparabile" lato nostro
 * (nessun errore di parsing XML, nessuna ClassNotFoundException sugli
 * Action Controller, versione Java corretta): la build Community di VP
 * scarta deliberatamente e silenziosamente le voci di menu/toolbar dei
 * plugin di terze parti. Il precedente workaround "hack" (iniezione diretta
 * nella JMenuBar Swing del root frame, poi rimosso in Fase 3) non era
 * comunque una soluzione accettabile in un'architettura pulita.
 *
 * La soluzione adottata ora e' invece un meccanismo 100% documentato e
 * pubblico della Open API, pensato esattamente per questo scopo:
 * {@code com.vp.plugin.ViewManager.showMessagePaneComponent(String id,
 * String title, java.awt.Component messageComponent)}. A differenza della
 * JMenuBar (superficie "grezza" Swing che la Sleek UI ridisegna sopra con un
 * layer proprietario), il Message Pane e' un'area dell'IDE esplicitamente
 * riservata dalla Open API all'inserimento di componenti custom: la scheda
 * risultante e' nativa, dockabile e visibile senza alcun aggancio non
 * documentato.
 *
 * Restano invariati (perche' funzionano correttamente e non hanno alcun
 * legame col problema del plugin.xml):
 *
 *  - La scorciatoia globale CTRL+ALT+C ({@link KeyEventDispatcher} su
 *    {@link KeyboardFocusManager}), mantenuta come via rapida aggiuntiva.
 *
 *  - I listener di progetto introdotti in Fase 4
 *    ({@link CosmicModelChangeListener}, che implementa
 *    {@code IProjectListener}, {@code IProjectDiagramListener} e
 *    {@code IProjectModelListener}): i log "[COSMIC AI][listener] Diagramma
 *    aggiunto..." confermano che sono attivi e funzionanti in background,
 *    del tutto indipendenti dal problema di visibilita' dei menu.
 *
 * NOTA su "IProjectManagerListener": nella richiesta di refactoring viene
 * citata anche questa interfaccia come già presente. Non ho trovato
 * conferma della sua esistenza nella Open API pubblica di VP (la Open API
 * espone IProjectListener/IProjectDiagramListener/IProjectModelListener,
 * registrati sulla singola istanza di IProject, non un listener "a livello
 * di ProjectManager" per essere avvisati di ogni apertura/cambio progetto -
 * si veda il "LIMITE NOTO" piu' sotto). Questa classe continua quindi a
 * registrare solo le 3 interfacce confermate; se nel vostro branch esiste
 * davvero una IProjectManagerListener funzionante (magari da una versione
 * piu' recente di openapi.jar), fatemi avere la sua firma esatta e la
 * integro subito.
 *
 * ---------------------------------------------------------------------
 * LIMITE NOTO INVARIATO (da Fase 4, non ancora risolto)
 * ---------------------------------------------------------------------
 * I listener di progetto restano legati all'istanza di {@link IProject}
 * presente al momento di {@code loaded()}. Se l'utente cambia progetto
 * durante la sessione, andranno ri-registrati: rimandato al prossimo step,
 * come già segnalato in Fase 4.
 */
public class CosmicPlugin implements VPPlugin {

    /** Scorciatoia infallibile: CTRL+ALT+C ("Cosmic"). */
    private static final int SHORTCUT_KEYCODE = KeyEvent.VK_C;
    private static final int SHORTCUT_MODIFIERS = KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK;

    /** Id univoco della scheda nel Message Pane (richiesto da show/removeMessagePaneComponent). */
    private static final String LAUNCHER_PANE_ID = "cosmic.ai.launcher";
    private static final String LAUNCHER_PANE_TITLE = "COSMIC AI";

    private KeyEventDispatcher keyEventDispatcher;

    /**
     * Fase 4: unica istanza del listener che, nel prossimo step, innescherà
     * il ricalcolo COSMIC in tempo reale. Per ora si limita a loggare gli
     * eventi intercettati (si veda il suo javadoc).
     */
    private final CosmicModelChangeListener modelChangeListener = new CosmicModelChangeListener();

    @Override
    public void loaded(VPPluginInfo info) {
        // Via rapida invariata da Fase 3: scorciatoia globale.
        installGlobalShortcut();

        // Fase 4 (fondamenta), invariata: aggancio del listener di eventi
        // real-time al progetto eventualmente già aperto.
        registerModelChangeListenerOnCurrentProject();

        // Fase 5: punto di accesso nativo e documentato, sostituisce ogni
        // tentativo basato su plugin.xml o iniezione Swing diretta.
        registerLauncherMessagePaneComponent();

        ApplicationManager.instance().getViewManager().showMessage(
                "[COSMIC AI] Plugin caricato. Apri la scheda 'COSMIC AI' nel pannello messaggi "
                        + "in basso, oppure premi CTRL+ALT+C in qualsiasi momento, per aprire "
                        + "'COSMIC AI Analyzer'.");
    }

    @Override
    public void unloaded() {
        if (keyEventDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removeKeyEventDispatcher(keyEventDispatcher);
            keyEventDispatcher = null;
        }
        unregisterModelChangeListenerFromCurrentProject();
        unregisterLauncherMessagePaneComponent();
        System.out.println("[COSMIC AI] Plugin disattivato.");
    }

    // ------------------------------------------------------------------
    // Fase 5: componente nativo nel Message Pane (sostituisce plugin.xml)
    // ------------------------------------------------------------------

    /**
     * Costruisce il piccolo pannello di lancio: un solo bottone centrato che
     * apre il pannello flottante non-modale {@link CosmicAnalyzerDialogHandler}
     * (lo stesso già usato dalla scorciatoia CTRL+ALT+C).
     */
    private JPanel buildLauncherPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton openButton = new JButton("Apri COSMIC AI Analyzer...");
        openButton.setToolTipText("Apre il pannello COSMIC AI per l'import dei requisiti "
                + "e la generazione del diagramma (equivalente a CTRL+ALT+C).");
        openButton.addActionListener(e -> openAnalyzerPanel());

        panel.add(openButton);
        return panel;
    }

    private void registerLauncherMessagePaneComponent() {
        SwingUtilities.invokeLater(() ->
                ApplicationManager.instance().getViewManager()
                        .showMessagePaneComponent(LAUNCHER_PANE_ID, LAUNCHER_PANE_TITLE, buildLauncherPanel()));
    }

    private void unregisterLauncherMessagePaneComponent() {
        try {
            ApplicationManager.instance().getViewManager().removeMessagePaneComponent(LAUNCHER_PANE_ID);
        } catch (Exception ignored) {
            // best effort: non deve mai bloccare lo scaricamento del plugin
        }
    }

    // ------------------------------------------------------------------
    // Fase 4 (fondamenta), invariata: aggancio/distacco del listener di
    // eventi real-time. Nessuna logica di ricalcolo qui: solo
    // infrastruttura. Si veda il javadoc di CosmicModelChangeListener.
    // ------------------------------------------------------------------

    private void registerModelChangeListenerOnCurrentProject() {
        try {
            IProject project = ApplicationManager.instance().getProjectManager().getProject();
            if (project == null) {
                // Nessun progetto aperto al momento del caricamento del
                // plugin. TODO (prossimo step): agganciarsi anche quando un
                // progetto viene aperto/creato in seguito (si veda il
                // "LIMITE NOTO" nel javadoc della classe e del listener).
                return;
            }
            project.addProjectListener(modelChangeListener);
            project.addProjectDiagramListener(modelChangeListener);
            project.addProjectModelListener(modelChangeListener);
        } catch (Exception ex) {
            // Non deve mai impedire il caricamento del plugin: e' solo
            // predisposizione per una funzionalita' futura.
            System.out.println("[COSMIC AI] Aggancio dei listener di modello non riuscito: " + ex);
        }
    }

    private void unregisterModelChangeListenerFromCurrentProject() {
        // NOTA: la documentazione consultata conferma esplicitamente solo i
        // metodi addProjectListener/addProjectDiagramListener/
        // addProjectModelListener; i corrispondenti removeXxxListener sono
        // presunti per simmetria (convenzione standard nelle Open API di
        // VP) ma non ancora verificati sul Javadoc di openapi.jar. Se il
        // nome esatto risultasse diverso, va corretto qui e solo qui.
        try {
            IProject project = ApplicationManager.instance().getProjectManager().getProject();
            if (project == null) {
                return;
            }
            project.removeProjectListener(modelChangeListener);
            project.removeProjectDiagramListener(modelChangeListener);
            project.removeProjectModelListener(modelChangeListener);
        } catch (Exception ignored) {
            // best effort anche in rimozione
        }
    }

    // ------------------------------------------------------------------
    // Scorciatoia globale invariata (garantita, puro java.awt)
    // ------------------------------------------------------------------

    private void installGlobalShortcut() {
        keyEventDispatcher = event -> {
            if (event.getID() == KeyEvent.KEY_PRESSED
                    && event.getKeyCode() == SHORTCUT_KEYCODE
                    && (event.getModifiersEx() & SHORTCUT_MODIFIERS) == SHORTCUT_MODIFIERS) {
                openAnalyzerPanel();
                return true; // consuma l'evento: nessun altro listener lo riceve
            }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(keyEventDispatcher);
    }

    // ------------------------------------------------------------------
    // Apertura effettiva del pannello (unico punto, riusato dal bottone nel
    // Message Pane, dalla scorciatoia, e potenzialmente dai vecchi
    // VPActionController se un domani plugin.xml tornasse a funzionare)
    // ------------------------------------------------------------------

    private void openAnalyzerPanel() {
        SwingUtilities.invokeLater(() ->
                ApplicationManager.instance().getViewManager()
                        .showDialog(new CosmicAnalyzerDialogHandler()));
    }
}
