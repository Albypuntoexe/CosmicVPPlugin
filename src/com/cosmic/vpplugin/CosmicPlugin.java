package com.cosmic.vpplugin;

import com.cosmic.vpplugin.service.CosmicAiService;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;

/**
 * Entry point del plugin.
 *
 * RISCRITTURA rispetto alla versione precedente: rimossi TUTTI i workaround
 * che causavano l'interfaccia "morta" descritta nel mandato:
 *
 *  - Rimosso {@code showMessagePaneComponent}/{@code removeMessagePaneComponent}:
 *    il Message Pane di Visual Paradigm e' documentato come area di
 *    messaggistica testuale (log), non come contenitore generico per
 *    pannelli interattivi con bottoni/DnD; il suo framework di docking
 *    proprietario intercettava gli eventi prima del nostro componente.
 *  - Rimosso il {@code KeyEventDispatcher} globale installato su
 *    {@code KeyboardFocusManager}: era un hook AWT a livello di intera
 *    applicazione (non un'API VP), rischioso e non necessario ora che
 *    esiste un punto di ingresso ufficiale via menu/menu contestuale.
 *  - Rimossa la gestione diretta dei listener di progetto: e' ora
 *    interamente responsabilita' di {@link CosmicAiService}, che la
 *    implementa in modo robusto anche rispetto al momento in cui un
 *    progetto viene aperto (si veda il javadoc di
 *    {@code CosmicAiService.startProjectWatcher()}).
 *
 * Questa classe si limita quindi ad avviare/fermare il Service: la UI si
 * apre esclusivamente su richiesta dell'utente (menu "Tools" o menu
 * contestuale sui diagrammi Use Case), tramite
 * {@code ViewManager.showDialog(IDialogHandler)}.
 */
public class CosmicPlugin implements VPPlugin {

    @Override
    public void loaded(VPPluginInfo info) {
        CosmicAiService.getInstance().start();
        ApplicationManager.instance().getViewManager().showMessage(
                "[COSMIC AI] Plugin caricato. Apri il pannello da Tools > COSMIC AI Analyzer... "
                        + "oppure con il tasto destro su un Use Case Diagram.");
    }

    @Override
    public void unloaded() {
        CosmicAiService.getInstance().stop();
        System.out.println("[COSMIC AI] Plugin disattivato.");
    }
}
