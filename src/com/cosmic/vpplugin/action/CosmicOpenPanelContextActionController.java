package com.cosmic.vpplugin.action;

import com.cosmic.vpplugin.ui.CosmicAnalyzerDialogHandler;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.action.VPAction;
import com.vp.plugin.action.VPContext;
import com.vp.plugin.action.VPContextActionController;

import java.awt.event.ActionEvent;

/**
 * Action Controller per azioni su popup menu di diagramma/modello, richiesto
 * dalla Open API per gli elementi dentro un {@code <contextSensitiveActionSet>}
 * (interfaccia diversa da {@code VPActionController}, usata invece per le
 * azioni di menu/toolbar "normali" — si veda {@link CosmicOpenPanelActionController}).
 *
 * Firma confermata dalla documentazione ufficiale VP
 * ("Implementing a Visual Paradigm plug-in"):
 *   performAction(VPAction action, VPContext context, ActionEvent e)
 *   update(VPAction action, VPContext context)
 *
 * Con {@code contextTypes all="true"} in plugin.xml, questa azione compare
 * SEMPRE nel popup menu (tasto destro) di qualunque diagramma — anche
 * cliccando sullo sfondo vuoto — indipendentemente da cosa e' selezionato.
 * E' quindi il punto di accesso "infallibile" richiesto.
 */
public class CosmicOpenPanelContextActionController implements VPContextActionController {

    // Costruttore senza argomenti richiesto dalla Open API.
    public CosmicOpenPanelContextActionController() {
    }

    @Override
    public void performAction(VPAction action, VPContext context, ActionEvent e) {
        ApplicationManager.instance().getViewManager()
                .showDialog(new CosmicAnalyzerDialogHandler());
    }

    @Override
    public void update(VPAction action, VPContext context) {
        // Nessuna proprieta' dinamica da aggiornare: la voce resta sempre
        // visibile e attiva, qualunque sia l'elemento (o il "vuoto") su cui
        // si e' cliccato con il tasto destro.
    }
}
