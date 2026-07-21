package com.cosmic.vpplugin;

import com.cosmic.vpplugin.dnd.CosmicDropTargetListener;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;

import java.awt.Component;
import java.awt.dnd.DropTarget;

/**
 * Entry point del plugin (dichiarato in plugin.xml, attributo "class").
 *
 * Ciclo di vita gestito dalla Open API di Visual Paradigm:
 *  - loaded(VPPluginInfo)  viene invocato quando VP carica il plugin.
 *  - unloaded()            viene invocato alla chiusura / disattivazione.
 *
 * In loaded() agganciamo un java.awt.dnd.DropTarget sulla finestra principale
 * di VP (ottenuta tramite ApplicationManager.getViewManager().getRootFrame())
 * per abilitare il Drag & Drop richiesto, invece di un pulsante classico.
 *
 * ATTENZIONE / LIMITAZIONE NOTA (si veda anche la spiegazione fornita in
 * chat): la finestra principale di VP potrebbe gia' avere un proprio
 * DropTarget nativo (ad es. per aprire file .vpp trascinati). Impostare un
 * NUOVO DropTarget sullo stesso componente con
 * {@code new DropTarget(rootFrame, listener)} SOSTITUISCE quello esistente,
 * quindi in questa fase prototipale perdiamo l'eventuale comportamento
 * originario di VP sul root frame. Per un plugin di produzione l'alternativa
 * piu' pulita e robusta e' creare un pannello agganciabile (dockable pane)
 * dedicato tramite le API di ViewManager e installare il DropTarget SOLO su
 * quel pannello: in questo modo non si interferisce mai con la UI nativa di
 * VP. Questo prototipo usa la via piu' diretta (root frame) per semplicita',
 * essendo l'obiettivo dimostrare il flusso end-to-end.
 */
public class CosmicPlugin implements VPPlugin {

    private DropTarget dropTarget;

    @Override
    public void loaded(VPPluginInfo info) {
        Component rootFrame = ApplicationManager.instance().getViewManager().getRootFrame();

        // DropTarget accepts any java.awt.Component (Frame is-a Component in practice,
        // but getRootFrame() is declared to return Component, not Frame).
        dropTarget = new DropTarget(rootFrame, new CosmicDropTargetListener());
        rootFrame.setDropTarget(dropTarget);

        ApplicationManager.instance().getViewManager()
                .showMessage("[COSMIC AI] Plugin caricato. Trascina un file .json (o .txt) "
                        + "sulla finestra di Visual Paradigm per generare il diagramma.");
    }

    @Override
    public void unloaded() {
        if (dropTarget != null) {
            dropTarget.setComponent(null);
            dropTarget = null;
        }
        System.out.println("[COSMIC AI] Plugin disattivato.");
    }
}
