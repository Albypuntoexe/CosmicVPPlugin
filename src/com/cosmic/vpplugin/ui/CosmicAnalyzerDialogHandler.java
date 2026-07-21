package com.cosmic.vpplugin.ui;

import com.vp.plugin.view.IDialog;
import com.vp.plugin.view.IDialogHandler;

import java.awt.Component;

/**
 * Adatta {@link CosmicAnalyzerPane} all'unico meccanismo di UI custom
 * realmente documentato dalla Open API di Visual Paradigm:
 * {@code com.vp.plugin.view.IDialogHandler} + {@code ViewManager.showDialog(...)}.
 *
 * Impostando {@code dialog.setModal(false)} in {@link #prepare(IDialog)},
 * la finestra resta apribile insieme al resto dell'interfaccia di VP (si
 * puo' continuare a lavorare sui diagrammi con il pannello aperto), il che
 * la rende, in pratica, l'equivalente funzionale piu' vicino a un pannello
 * agganciabile che l'API pubblica permette di realizzare in modo stabile.
 *
 * Nota: NON e' un vero pannello incorporato nella cornice/i tab dell'IDE
 * (come una "view" Eclipse) — e' una finestra separata non modale. La Open
 * API di VP non espone, ad oggi, un punto di estensione pubblico per un
 * docking reale nella finestra principale (verificato sulla documentazione
 * ufficiale e su una discussione del forum VP che pone esplicitamente questa
 * domanda senza ottenere un meccanismo diverso da questo).
 */
public class CosmicAnalyzerDialogHandler implements IDialogHandler {

    private final CosmicAnalyzerPane pane = new CosmicAnalyzerPane();
    private IDialog dialog;

    @Override
    public Component getComponent() {
        return pane;
    }

    @Override
    public void prepare(IDialog dialog) {
        this.dialog = dialog;
        dialog.setTitle("COSMIC AI - Analyzer");
        dialog.setModal(false);   // permette di continuare a lavorare su VP con il pannello aperto
        dialog.setResizable(true);
        dialog.pack();
    }

    @Override
    public void shown() {
        // nessuna azione necessaria al momento della visualizzazione
    }

    @Override
    public boolean canClosed() {
        return true; // il pannello puo' sempre essere chiuso liberamente
    }
}
