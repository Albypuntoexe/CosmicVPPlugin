package com.cosmic.vpplugin.action;

import com.cosmic.vpplugin.ui.CosmicAnalyzerDialogHandler;
import com.vp.plugin.action.VPAction;
import com.vp.plugin.action.VPContext;
import com.vp.plugin.action.VPContextActionController;
import com.vp.plugin.diagram.IUseCaseDiagramUIModel;

import java.awt.event.ActionEvent;

/**
 * Action Controller per il menu contestuale (tasto destro).
 *
 * In plugin.xml il {@code <contextSensitiveActionSet>} usa
 * {@code contextTypes all="true"}: l'azione viene quindi VALUTATA per
 * qualunque popup menu dell'applicazione. E' compito di {@link #update}
 * abilitarla SOLO quando il contesto e' effettivamente un Use Case Diagram
 * (sfondo del diagramma o una delle sue shape) - esattamente il pattern
 * suggerito dalla guida ufficiale VP per l'Action Controller di popup menu.
 */
public class CosmicDiagramContextActionController implements VPContextActionController {

    public CosmicDiagramContextActionController() {
        // Costruttore pubblico senza argomenti: richiesto dal framework plugin di VP.
    }

    @Override
    public void performAction(VPAction action, VPContext context, ActionEvent event) {
        CosmicAnalyzerDialogHandler.openOrNotify();
    }

    @Override
    public void update(VPAction action, VPContext context) {
        boolean isUseCaseDiagram = context != null && context.getDiagram() instanceof IUseCaseDiagramUIModel;
        action.setEnabled(isUseCaseDiagram);
    }
}
