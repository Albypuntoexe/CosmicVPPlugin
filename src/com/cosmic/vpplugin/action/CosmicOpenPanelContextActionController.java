package com.cosmic.vpplugin.action;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.action.VPAction;
import com.vp.plugin.action.VPContext;
import com.vp.plugin.action.VPContextActionController;

import java.awt.event.ActionEvent;

/**
 * Action Controller per il menu contestuale (fallback se plugin.xml riprende a funzionare).
 * Ora porta in primo piano la scheda nativa del Message Pane.
 */
public class CosmicOpenPanelContextActionController implements VPContextActionController {

    public CosmicOpenPanelContextActionController() {
    }

    @Override
    public void performAction(VPAction action, VPContext context, ActionEvent e) {
        try {
            ApplicationManager.instance().getViewManager().showMessagePaneComponent("cosmic.ai.launcher");
        } catch (Exception ex) {
            System.out.println("[COSMIC AI] Impossibile portare in primo piano il pannello: " + ex);
        }
    }

    @Override
    public void update(VPAction action, VPContext context) {
    }
}