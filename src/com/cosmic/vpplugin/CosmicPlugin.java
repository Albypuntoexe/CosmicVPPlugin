package com.cosmic.vpplugin;

import com.cosmic.vpplugin.listener.CosmicModelChangeListener;
import com.cosmic.vpplugin.ui.CosmicAnalyzerDialogHandler;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;
import com.vp.plugin.model.IProject;

import javax.swing.SwingUtilities;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;

/**
 * Entry point del plugin (dichiarato in plugin.xml, attributo "class").
 *
 * FASE 3 - BYPASS del sistema di menu dichiarativo di Visual Paradigm.
 *
 * ---------------------------------------------------------------------
 * PERCHE' QUESTO CAMBIAMENTO
 * ---------------------------------------------------------------------
 * Nella build di VP attualmente in uso, il file plugin.xml (menuPath="Tools",
 * "Plug-ins/...", e persino un intero <contextSensitiveActionSet> con
 * contextTypes all="true") viene sistematicamente IGNORATO dalla Sleek UI:
 * nessuna voce compare, nonostante il plugin si carichi correttamente (il
 * messaggio "[COSMIC AI] Plugin caricato" viene mostrato). Cambiare l'id del
 * plugin per invalidare la cache non ha risolto il problema: si tratta quindi
 * di un problema di risoluzione del descrittore XML, non di caching.
 *
 * Non possiamo permetterci che l'intera tesi dipenda da un meccanismo che
 * l'IDE ospite si rifiuta di esporre. Per questo motivo il punto di accesso
 * REALE all'Analyzer non passa piu' (solo) dal plugin.xml, ma viene creato a
 * runtime, in Java puro, con un unico meccanismo che non ha alcuna
 * dipendenza dal sistema di menu/toolbar della Open API:
 *
 * Un {@link KeyEventDispatcher} globale registrato su
 * {@link KeyboardFocusManager#getCurrentKeyboardFocusManager()}: intercetta
 * la combinazione CTRL+ALT+C in QUALSIASI finestra della JVM di VP, prima che
 * l'evento raggiunga qualunque altro componente. Questo e' puro AWT: non
 * passa per VPAction, VPActionController, ne' per alcuna registrazione
 * dichiarativa, quindi non puo' essere "nascosto" dalla Sleek UI, ed e'
 * indipendente da qualunque scelta di toolkit UI fatta da VP.
 *
 * ---------------------------------------------------------------------
 * PERCHE' NON C'E' (PIU') UNA VOCE DI MENU INIETTATA A RUNTIME
 * ---------------------------------------------------------------------
 * Una prima versione di questa classe tentava anche di iniettare una voce
 * "COSMIC AI" direttamente nella {@code JMenuBar} del root frame ottenuto con
 * {@code ApplicationManager.instance().getViewManager().getRootFrame()} (la
 * stessa chiamata usata con successo in Fase 1 per il {@code DropTarget}, che
 * quindi si risolve correttamente in questo ambiente). Il tentativo e' stato
 * verificato empiricamente: il {@code JMenuItem} veniva creato e aggiunto
 * senza eccezioni, ma non compariva mai nella UI. La Sleek UI di Visual
 * Paradigm non usa quindi la {@code JMenuBar} Swing "grezza" del root frame
 * come superficie di rendering reale del proprio menu (probabilmente la
 * ridisegna con un layer/toolkit proprietario sopra o al posto di essa),
 * rendendo l'iniezione AWT/Swing diretta silenziosamente inefficace, oltre
 * che fragile (dipendente da dettagli implementativi non documentati che
 * potrebbero cambiare da una build all'altra).
 *
 * Per questo motivo la logica di iniezione e' stata rimossa: la scorciatoia
 * globale CTRL+ALT+C resta l'UNICO punto di accesso realmente garantito, ed
 * e' anche l'unico che serve, visto che funziona in modo affidabile.
 *
 * I vecchi VPActionController (CosmicOpenPanelActionController,
 * CosmicOpenPanelContextActionController, CosmicDiagramPopupActionController)
 * e il relativo plugin.xml NON vanno rimossi: se in una versione futura di VP
 * il descrittore verra' risolto correttamente, quelle voci di menu inizieranno
 * semplicemente a funzionare anche loro, senza alcun conflitto (chiamano lo
 * stesso showDialog(new CosmicAnalyzerDialogHandler())).
 */
public class CosmicPlugin implements VPPlugin {

    /** Scorciatoia infallibile: CTRL+ALT+C ("Cosmic"). */
    private static final int SHORTCUT_KEYCODE = KeyEvent.VK_C;
    private static final int SHORTCUT_MODIFIERS = KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK;

    private KeyEventDispatcher keyEventDispatcher;

    /**
     * Fase 4 (fondamenta): unica istanza del listener che, nel prossimo
     * step, innescherà il ricalcolo COSMIC in tempo reale. Per ora si
     * limita a loggare gli eventi intercettati (si veda il suo javadoc).
     */
    private final CosmicModelChangeListener modelChangeListener = new CosmicModelChangeListener();

    @Override
    public void loaded(VPPluginInfo info) {
        // Unico meccanismo di accesso: intercettazione globale della
        // scorciatoia. Nessun'altra registrazione (menu, toolbar, ecc.) e'
        // necessaria né tentata: si e' rivelata inutile in questo ambiente
        // (si veda il javadoc della classe).
        installGlobalShortcut();

        // Fase 4 (fondamenta): aggancio del listener di eventi real-time al
        // progetto eventualmente già aperto. Non fa ancora nulla di
        // funzionale (si veda CosmicModelChangeListener): serve solo a
        // verificare che l'aggancio stesso funzioni, in modo che il
        // prossimo step possa concentrarsi sulla logica di ricalcolo.
        registerModelChangeListenerOnCurrentProject();

        ApplicationManager.instance().getViewManager().showMessage(
                "[COSMIC AI] Plugin caricato. Premi CTRL+ALT+C in qualsiasi momento "
                        + "per aprire 'COSMIC AI Analyzer' (i menu Tools/Plug-ins non sono "
                        + "attualmente esposti dalla UI di VP).");
    }

    @Override
    public void unloaded() {
        if (keyEventDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removeKeyEventDispatcher(keyEventDispatcher);
            keyEventDispatcher = null;
        }
        unregisterModelChangeListenerFromCurrentProject();
        System.out.println("[COSMIC AI] Plugin disattivato.");
    }

    // ------------------------------------------------------------------
    // Fase 4 (fondamenta): aggancio/distacco del listener di eventi
    // real-time. Nessuna logica di ricalcolo qui: solo infrastruttura.
    // Si veda il javadoc di CosmicModelChangeListener per il "perché" di
    // queste 3 interfacce e per il limite noto (cambio di progetto) da
    // risolvere nel prossimo step.
    // ------------------------------------------------------------------

    private void registerModelChangeListenerOnCurrentProject() {
        try {
            IProject project = ApplicationManager.instance().getProjectManager().getProject();
            if (project == null) {
                // Nessun progetto aperto al momento del caricamento del
                // plugin: normale, ad es. se VP e' appena stato avviato
                // senza aprire nulla. TODO (prossimo step): agganciarsi
                // anche quando un progetto viene aperto/creato in seguito
                // (si veda il "LIMITE NOTO" nel javadoc del listener).
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
    // 1) Scorciatoia globale (garantita, puro java.awt)
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
    // Apertura effettiva del pannello (unico punto, riusato dalla
    // scorciatoia e potenzialmente anche dai vecchi VPActionController)
    // ------------------------------------------------------------------

    private void openAnalyzerPanel() {
        SwingUtilities.invokeLater(() ->
                ApplicationManager.instance().getViewManager()
                        .showDialog(new CosmicAnalyzerDialogHandler()));
    }
}