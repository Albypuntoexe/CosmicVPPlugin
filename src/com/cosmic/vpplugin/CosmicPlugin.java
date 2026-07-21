package com.cosmic.vpplugin;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;

/**
 * Entry point del plugin (dichiarato in plugin.xml, attributo "class").
 *
 * FASE 2 - ripulito dal DropTarget globale: nella Fase 1 questo metodo
 * agganciava un java.awt.dnd.DropTarget direttamente al root frame di
 * Visual Paradigm (ApplicationManager.getViewManager().getRootFrame()),
 * rischiando di sostituire un eventuale drop-handling nativo di VP su quel
 * componente.
 *
 * Ora il root frame di VP resta completamente intoccato: il Drag & Drop e'
 * di proprieta' esclusiva del pannello com.cosmic.vpplugin.ui.CosmicAnalyzerPane,
 * mostrato tramite un IDialogHandler non modale quando l'utente sceglie la
 * voce di menu "COSMIC AI Analyzer..." (si veda plugin.xml e
 * com.cosmic.vpplugin.action.CosmicOpenPanelActionController).
 *
 * loaded()/unloaded() restano quindi minimali: servono solo per il ciclo di
 * vita del plugin richiesto dalla Open API, non per agganciare piu' nulla
 * alla UI globale di VP.
 */
public class CosmicPlugin implements VPPlugin {

    @Override
    public void loaded(VPPluginInfo info) {
        ApplicationManager.instance().getViewManager()
                .showMessage("[COSMIC AI] Plugin caricato. Apri 'Tools > COSMIC AI Analyzer...' "
                        + "per trascinare il file dei requisiti e generare il diagramma.");
    }

    @Override
    public void unloaded() {
        System.out.println("[COSMIC AI] Plugin disattivato.");
    }
}
