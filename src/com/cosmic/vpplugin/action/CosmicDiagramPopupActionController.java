package com.cosmic.vpplugin.action;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.action.VPAction;
import com.vp.plugin.action.VPContext;
import com.vp.plugin.action.VPContextActionController;
import com.vp.plugin.diagram.IUseCaseDiagramUIModel;

import java.awt.event.ActionEvent;

/**
 * Action Controller per diagrammi specifici.
 * Ora porta in primo piano la scheda nativa del Message Pane.
 */
public class CosmicDiagramPopupActionController implements VPContextActionController {

    public CosmicDiagramPopupActionController() {
    }

    @Override
    public void performAction(VPAction action, VPContext context, ActionEvent event) {
        try {
            ApplicationManager.instance().getViewManager().showMessagePaneComponent("cosmic.ai.launcher");
        } catch (Exception ex) {
            System.out.println("[COSMIC AI] Impossibile portare in primo piano il pannello: " + ex);
        }
    }

    @Override
    public void update(VPAction action, VPContext context) {
        boolean isUseCaseDiagram = false;
        if (context != null && context.getDiagram() != null) {
            if (context.getDiagram() instanceof IUseCaseDiagramUIModel) {
                isUseCaseDiagram = true;
            }
        }
        action.setEnabled(isUseCaseDiagram);
    }
}