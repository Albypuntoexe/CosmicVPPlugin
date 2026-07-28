package com.cosmic.vpplugin;

import com.cosmic.vpplugin.listener.CosmicModelChangeListener;
import com.cosmic.vpplugin.ui.CosmicAnalyzerPane;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;
import com.vp.plugin.model.IProject;

import javax.swing.SwingUtilities;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;

public class CosmicPlugin implements VPPlugin {

    private static final int SHORTCUT_KEYCODE = KeyEvent.VK_C;
    private static final int SHORTCUT_MODIFIERS = KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK;

    private static final String ANALYZER_PANE_ID = "cosmic.ai.launcher";
    private static final String ANALYZER_PANE_TITLE = "COSMIC AI";

    private KeyEventDispatcher keyEventDispatcher;

    // FIX: Rimossa l'inizializzazione diretta (che avveniva sul thread sbagliato).
    private CosmicAnalyzerPane analyzerPane;

    private final CosmicModelChangeListener modelChangeListener = new CosmicModelChangeListener();

    @Override
    public void loaded(VPPluginInfo info) {
        installGlobalShortcut();
        registerModelChangeListenerOnCurrentProject();
        registerAnalyzerPaneComponent();

        ApplicationManager.instance().getViewManager().showMessage(
                "[COSMIC AI] Plugin caricato. Il pannello 'COSMIC AI' e' disponibile come "
                        + "scheda nativa nel pannello messaggi in basso.");
    }

    @Override
    public void unloaded() {
        if (keyEventDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removeKeyEventDispatcher(keyEventDispatcher);
            keyEventDispatcher = null;
        }
        unregisterModelChangeListenerFromCurrentProject();
        unregisterAnalyzerPaneComponent();
        System.out.println("[COSMIC AI] Plugin disattivato.");
    }

    private void registerAnalyzerPaneComponent() {
        // FIX: La creazione dei componenti grafici avviene in sicurezza sull'EDT.
        SwingUtilities.invokeLater(() -> {
            if (analyzerPane == null) {
                analyzerPane = new CosmicAnalyzerPane();
            }
            ApplicationManager.instance().getViewManager()
                    .showMessagePaneComponent(ANALYZER_PANE_ID, ANALYZER_PANE_TITLE, analyzerPane);
        });
    }

    private void unregisterAnalyzerPaneComponent() {
        try {
            ApplicationManager.instance().getViewManager().removeMessagePaneComponent(ANALYZER_PANE_ID);
        } catch (Exception ignored) {
        }
    }

    private void bringAnalyzerPaneToFront() {
        SwingUtilities.invokeLater(() -> {
            try {
                ApplicationManager.instance().getViewManager().showMessagePaneComponent(ANALYZER_PANE_ID);
            } catch (Exception ex) {
                System.out.println("[COSMIC AI] Impossibile portare in primo piano la scheda: " + ex);
            }
        });
    }

    private void registerModelChangeListenerOnCurrentProject() {
        try {
            IProject project = ApplicationManager.instance().getProjectManager().getProject();
            if (project == null) return;
            project.addProjectListener(modelChangeListener);
            project.addProjectDiagramListener(modelChangeListener);
            project.addProjectModelListener(modelChangeListener);
        } catch (Exception ex) {
            System.out.println("[COSMIC AI] Aggancio dei listener di modello non riuscito: " + ex);
        }
    }

    private void unregisterModelChangeListenerFromCurrentProject() {
        try {
            IProject project = ApplicationManager.instance().getProjectManager().getProject();
            if (project == null) return;
            project.removeProjectListener(modelChangeListener);
            project.removeProjectDiagramListener(modelChangeListener);
            project.removeProjectModelListener(modelChangeListener);
        } catch (Exception ignored) {
        }
    }

    private void installGlobalShortcut() {
        keyEventDispatcher = event -> {
            if (event.getID() == KeyEvent.KEY_PRESSED
                    && event.getKeyCode() == SHORTCUT_KEYCODE
                    && (event.getModifiersEx() & SHORTCUT_MODIFIERS) == SHORTCUT_MODIFIERS) {
                bringAnalyzerPaneToFront();
                return true;
            }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(keyEventDispatcher);
    }
}