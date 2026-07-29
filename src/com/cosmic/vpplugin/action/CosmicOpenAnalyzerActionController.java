package com.cosmic.vpplugin.action;

import com.cosmic.vpplugin.ui.CosmicAnalyzerDialogHandler;
import com.vp.plugin.action.VPAction;
import com.vp.plugin.action.VPActionController;

/**
 * Action Controller per la voce "Tools > COSMIC AI Analyzer...".
 * Nessuna logica qui: apre (o segnala come gia' aperto) l'unico dialog di
 * analisi, seguendo il pattern documentato com.vp.plugin.action.VPActionController.
 */
public class CosmicOpenAnalyzerActionController implements VPActionController {

    public CosmicOpenAnalyzerActionController() {
        // Costruttore pubblico senza argomenti: richiesto dal framework plugin di VP.
    }

    @Override
    public void performAction(VPAction action) {
        CosmicAnalyzerDialogHandler.openOrNotify();
    }

    @Override
    public void update(VPAction action) {
        // Sempre visibile/abilitata: nessuna condizione da valutare per una voce di menu globale.
    }
}
