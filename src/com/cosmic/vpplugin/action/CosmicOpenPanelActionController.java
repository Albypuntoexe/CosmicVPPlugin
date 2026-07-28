package com.cosmic.vpplugin.action;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.action.VPAction;
import com.vp.plugin.action.VPActionController;

/**
 * Action Controller per il menu (fallback se plugin.xml riprende a funzionare).
 * Ora porta in primo piano la scheda nativa del Message Pane.
 */
public class CosmicOpenPanelActionController implements VPActionController {

    public CosmicOpenPanelActionController() {
    }

    @Override
    public void performAction(VPAction action) {
        try {
            ApplicationManager.instance().getViewManager().showMessagePaneComponent("cosmic.ai.launcher");
        } catch (Exception e) {
            System.out.println("[COSMIC AI] Impossibile portare in primo piano il pannello: " + e);
        }
    }

    @Override
    public void update(VPAction action) {
    }
}