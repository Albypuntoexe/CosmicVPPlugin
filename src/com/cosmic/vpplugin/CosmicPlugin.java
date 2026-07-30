package com.cosmic.vpplugin;

import com.cosmic.vpplugin.service.CosmicAiService;
import com.cosmic.vpplugin.ui.CosmicAnalyzerDialogHandler;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;

/**
 * Entry point del plugin.
 *
 * Abbiamo ripristinato il KeyEventDispatcher (CTRL+ALT+C) per bypassare
 * il motore XML di Visual Paradigm che ignora le voci di menu.
 */
public class CosmicPlugin implements VPPlugin {

    private static final int SHORTCUT_KEYCODE = KeyEvent.VK_C;
    private static final int SHORTCUT_MODIFIERS = KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK;
    private KeyEventDispatcher keyEventDispatcher;

    @Override
    public void loaded(VPPluginInfo info) {
        // Avvia il service in background
        CosmicAiService.getInstance().start();

        // Installa la scorciatoia da tastiera globale
        installGlobalShortcut();

        ApplicationManager.instance().getViewManager().showMessage(
                "[COSMIC AI] Plugin caricato. Premi CTRL+ALT+C in qualsiasi momento per aprire "
                        + "l'Analyzer (oppure usa il tasto destro su un Use Case Diagram).");
    }

    @Override
    public void unloaded() {
        if (keyEventDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher);
            keyEventDispatcher = null;
        }
        CosmicAiService.getInstance().stop();
        System.out.println("[COSMIC AI] Plugin disattivato.");
    }

    private void installGlobalShortcut() {
        keyEventDispatcher = event -> {
            if (event.getID() == KeyEvent.KEY_PRESSED
                    && event.getKeyCode() == SHORTCUT_KEYCODE
                    && (event.getModifiersEx() & SHORTCUT_MODIFIERS) == SHORTCUT_MODIFIERS) {

                // Apre la nuova finestra di dialogo ufficiale creata dal nuovo LLM
                CosmicAnalyzerDialogHandler.openOrNotify();
                return true;
            }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher);
    }
}