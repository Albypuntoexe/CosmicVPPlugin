package com.cosmic.vpplugin.action;

import com.cosmic.vpplugin.ui.CosmicAnalyzerDialogHandler;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.action.VPAction;
import com.vp.plugin.action.VPActionController;

/**
 * Action Controller richiesto dalla Open API per le azioni di menu/toolbar
 * (deve implementare {@code com.vp.plugin.action.VPActionController}, con
 * costruttore senza argomenti — si veda plugin.xml, elemento
 * {@code <actionController class="...">}).
 *
 * Al click sulla voce di menu "COSMIC AI Analyzer..." apre il pannello non
 * modale {@link CosmicAnalyzerDialogHandler}.
 */
public class CosmicOpenPanelActionController implements VPActionController {

    // Costruttore senza argomenti richiesto dalla Open API.
    public CosmicOpenPanelActionController() {
    }

    @Override
    public void performAction(VPAction action) {
        ApplicationManager.instance().getViewManager()
                .showDialog(new CosmicAnalyzerDialogHandler());
    }

    @Override
    public void update(VPAction action) {
        // nessuna proprieta' dinamica da aggiornare per questa azione
    }
}
