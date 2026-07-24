package com.cosmic.vpplugin;

import com.cosmic.vpplugin.ui.CosmicAnalyzerDialogHandler;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
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
 * runtime, in Java puro, appoggiandosi a due meccanismi che non hanno alcuna
 * dipendenza dal sistema di menu/toolbar della Open API:
 *
 * 1) Un KeyEventDispatcher globale registrato su
 *    {@link KeyboardFocusManager#getCurrentKeyboardFocusManager()}: intercetta
 *    la combinazione CTRL+ALT+C in QUALSIASI finestra della JVM di VP, prima
 *    che l'evento raggiunga qualunque altro componente. Questo e' puro AWT:
 *    non passa per VPAction, VPActionController, ne' per alcuna registrazione
 *    dichiarativa, quindi non puo' essere "nascosto" dalla Sleek UI.
 *
 * 2) L'iniezione diretta di una voce "COSMIC AI Analyzer..." nella JMenuBar
 *    del root frame di VP (ottenuto con
 *    {@code ApplicationManager.instance().getViewManager().getRootFrame()},
 *    la stessa chiamata gia' usata con successo in Fase 1 per il DropTarget:
 *    sappiamo quindi per certo che e' risolvibile in questo ambiente). Questo
 *    e' un fallback visibile/scopribile per chi non conosce la scorciatoia.
 *
 * Il meccanismo (2) e' "best effort" (dipende dal fatto che il root frame sia
 * un JFrame con una JMenuBar Swing già presente: se la Sleek UI non usa una
 * vera JMenuBar Swing, l'iniezione fallisce silenziosamente e viene loggata).
 * Il meccanismo (1) invece e' garantito: KeyboardFocusManager e' API Java
 * standard, indipendente da qualunque scelta di toolkit UI fatta da VP.
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
    private JMenuItem injectedMenuItem;
    private JMenu injectedMenu;

    @Override
    public void loaded(VPPluginInfo info) {
        // 1) Meccanismo garantito: intercettazione globale della scorciatoia.
        installGlobalShortcut();

        // 2) Meccanismo "best effort": voce di menu iniettata direttamente
        //    nella JMenuBar del root frame. Il root frame potrebbe non essere
        //    ancora completamente inizializzato nell'istante esatto in cui
        //    loaded() viene invocato, quindi ritentiamo per qualche secondo
        //    con un javax.swing.Timer invece di un singolo tentativo secco.
        SwingUtilities.invokeLater(this::tryInjectMenuWithRetry);

        ApplicationManager.instance().getViewManager().showMessage(
                "[COSMIC AI] Plugin caricato. Premi CTRL+ALT+C in qualsiasi momento "
                        + "per aprire 'COSMIC AI Analyzer' (bypass dei menu Tools/Plug-ins, "
                        + "attualmente non esposti dalla UI di VP).");
    }

    @Override
    public void unloaded() {
        if (keyEventDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removeKeyEventDispatcher(keyEventDispatcher);
            keyEventDispatcher = null;
        }
        removeInjectedMenuIfPresent();
        System.out.println("[COSMIC AI] Plugin disattivato.");
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
    // 2) Voce di menu iniettata a runtime (best effort, con retry)
    // ------------------------------------------------------------------

    private void tryInjectMenuWithRetry() {
        // Il root frame di VP potrebbe non avere ancora una JMenuBar pronta
        // nell'istante in cui il plugin viene caricato: ritentiamo ogni 500ms
        // per un massimo di 10 secondi, poi ci fermiamo silenziosamente (in
        // ogni caso la scorciatoia CTRL+ALT+C resta sempre disponibile).
        final int[] attemptsLeft = {20};
        Timer timer = new Timer(500, null);
        timer.addActionListener((ActionEvent e) -> {
            boolean injected = injectMenuItem();
            attemptsLeft[0]--;
            if (injected || attemptsLeft[0] <= 0) {
                timer.stop();
                if (!injected) {
                    System.out.println("[COSMIC AI] Impossibile iniettare la voce di menu "
                            + "(root frame senza JMenuBar Swing risolvibile): resta comunque "
                            + "disponibile la scorciatoia CTRL+ALT+C.");
                }
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    /**
     * @return true se la voce e' stata iniettata con successo (o era gia' presente).
     */
    private boolean injectMenuItem() {
        if (injectedMenuItem != null) {
            return true; // gia' iniettata in un tentativo precedente
        }
        try {
            Component rootComponent = ApplicationManager.instance().getViewManager().getRootFrame();
            if (!(rootComponent instanceof JFrame)) {
                return false; // ambiente non Swing puro: solo la scorciatoia resta valida
            }
            JFrame jFrame = (JFrame) rootComponent;
            JMenuBar menuBar = jFrame.getJMenuBar();
            if (menuBar == null) {
                return false; // non ancora pronta: il Timer ritentera'
            }

            injectedMenu = new JMenu("COSMIC AI");
            injectedMenuItem = new JMenuItem("COSMIC AI Analyzer...");
            injectedMenuItem.setAccelerator(KeyStroke.getKeyStroke(SHORTCUT_KEYCODE, SHORTCUT_MODIFIERS));
            injectedMenuItem.addActionListener((ActionEvent e) -> openAnalyzerPanel());
            injectedMenu.add(injectedMenuItem);

            menuBar.add(injectedMenu);
            menuBar.revalidate();
            menuBar.repaint();
            return true;
        } catch (Exception ex) {
            // Qualunque eccezione qui (cast fallito, metodo non risolvibile,
            // ecc.) non deve mai impedire il caricamento del plugin: la
            // scorciatoia globale resta comunque il percorso garantito.
            System.out.println("[COSMIC AI] Iniezione menu non riuscita: " + ex);
            return false;
        }
    }

    private void removeInjectedMenuIfPresent() {
        if (injectedMenu == null) {
            return;
        }
        try {
            Component rootComponent = ApplicationManager.instance().getViewManager().getRootFrame();
            if (rootComponent instanceof JFrame) {
                JMenuBar menuBar = ((JFrame) rootComponent).getJMenuBar();
                if (menuBar != null) {
                    menuBar.remove(injectedMenu);
                    menuBar.revalidate();
                    menuBar.repaint();
                }
            }
        } catch (Exception ignored) {
            // best effort anche in rimozione
        } finally {
            injectedMenu = null;
            injectedMenuItem = null;
        }
    }

    // ------------------------------------------------------------------
    // Apertura effettiva del pannello (unico punto, riusato da entrambi
    // i meccanismi e potenzialmente anche dai vecchi VPActionController)
    // ------------------------------------------------------------------

    private void openAnalyzerPanel() {
        SwingUtilities.invokeLater(() ->
                ApplicationManager.instance().getViewManager()
                        .showDialog(new CosmicAnalyzerDialogHandler()));
    }
}