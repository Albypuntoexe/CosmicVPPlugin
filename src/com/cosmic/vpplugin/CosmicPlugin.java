package com.cosmic.vpplugin;

import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;
import com.vp.plugin.ApplicationManager;

public class CosmicPlugin implements VPPlugin {

    @Override
    public void loaded(VPPluginInfo info) {
        // Questo scatta appena Visual Paradigm legge il plugin all'avvio.
        ApplicationManager.instance().getViewManager().showMessage("[COSMIC AI] -> Plugin caricato con successo! Ciao Alberto, l'ambiente è pronto.");
    }

    @Override
    public void unloaded() {
        // Questo scatta quando chiudi Visual Paradigm.
        System.out.println("[COSMIC AI] -> Plugin disattivato.");
    }
}