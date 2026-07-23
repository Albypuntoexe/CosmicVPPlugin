package com.cosmic.vpplugin.action;

import com.cosmic.vpplugin.ui.CosmicAnalyzerDialogHandler;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.action.VPAction;
import com.vp.plugin.action.VPContext;
import com.vp.plugin.action.VPContextActionController;
import com.vp.plugin.diagram.IUseCaseDiagramUIModel;

import java.awt.event.ActionEvent;

/**
 * Action Controller per il menu contestuale (tasto destro) in Visual Paradigm.
 * Si attiva specificamente quando si clicca con il tasto destro nello sfondo
 * di un diagramma. Verifica che si tratti di un Use Case Diagram prima di abilitarsi.
 */
public class CosmicDiagramPopupActionController implements VPContextActionController {

    // Costruttore senza argomenti richiesto dalla Open API.
    public CosmicDiagramPopupActionController() {
    }

    @Override
    public void performAction(VPAction action, VPContext context, ActionEvent event) {
        // Apre il dialog non modale
        ApplicationManager.instance().getViewManager()
                .showDialog(new CosmicAnalyzerDialogHandler());
    }

    @Override
    public void update(VPAction action, VPContext context) {
        boolean isUseCaseDiagram = false;

        // Verifica in modo robusto che il context e il diagramma siano presenti
        if (context != null && context.getDiagram() != null) {
            // Controlla che il diagramma corrente sia un IUseCaseDiagramUIModel
            if (context.getDiagram() instanceof IUseCaseDiagramUIModel) {
                isUseCaseDiagram = true;
            }
        }

        // Abilita la voce di menu solo se ci troviamo in un Use Case Diagram
        action.setEnabled(isUseCaseDiagram);
    }
}
