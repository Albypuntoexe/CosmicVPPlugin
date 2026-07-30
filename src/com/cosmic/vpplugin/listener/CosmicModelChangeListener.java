package com.cosmic.vpplugin.listener;

import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IProjectDiagramListener;
import com.vp.plugin.model.IProjectListener;
import com.vp.plugin.model.IProjectModelListener;

import java.util.function.Consumer;

/**
 * Infrastruttura di ascolto per il ricalcolo COSMIC in tempo reale.
 *
 * AGGIORNAMENTO (Task 4 - sync bidirezionale): {@code modelAdded} e
 * {@code modelRemoved} ora, oltre a loggare come prima, invocano un
 * callback esterno ({@link #onModelAdded}/{@link #onModelRemoved}),
 * impostato da {@link com.cosmic.vpplugin.service.CosmicAiService} nel suo
 * costruttore. Questa classe resta comunque riusabile "a se stante": se
 * nessuno imposta i callback, il comportamento e' identico a prima (solo
 * logging), grazie ai no-op di default.
 *
 * Gli altri eventi (progetto, diagrammi) restano solo loggati: la logica
 * di ricalcolo richiesta dal Task 4 riguarda esplicitamente le modifiche
 * agli elementi di modello (Use Case, Associazioni, ecc.), non l'apertura
 * di progetti o diagrammi.
 */
public class CosmicModelChangeListener implements IProjectListener, IProjectDiagramListener, IProjectModelListener {

    private static final String TODO_PROPERTY_CHANGE_NOTE =
            "IModelElement.addPropertyChangeListener(...) per eventi sul singolo elemento";

    /** Invocato quando un elemento di modello viene aggiunto al progetto. No-op di default. */
    private Consumer<IModelElement> onModelAdded = element -> { };

    /** Invocato quando un elemento di modello viene rimosso dal progetto. No-op di default. */
    private Consumer<IModelElement> onModelRemoved = element -> { };

    public void setOnModelAdded(Consumer<IModelElement> onModelAdded) {
        this.onModelAdded = (onModelAdded != null) ? onModelAdded : (element -> { });
    }

    public void setOnModelRemoved(Consumer<IModelElement> onModelRemoved) {
        this.onModelRemoved = (onModelRemoved != null) ? onModelRemoved : (element -> { });
    }

    // ==================================================================
    // IProjectListener - eventi sul progetto
    // ==================================================================

    @Override
    public void projectNewed(IProject project) {
        log("Progetto creato: " + safeName(project));
    }

    @Override
    public void projectOpened(IProject project) {
        log("Progetto aperto: " + safeName(project));
    }

    @Override
    public void projectAfterOpened(IProject project) {
        log("Progetto post-apertura completata: " + safeName(project));
    }

    @Override
    public void projectRenamed(IProject project) {
        log("Progetto rinominato: " + safeName(project));
    }

    @Override
    public void projectPreSave(IProject project) {
        // Nessuna azione prevista: non blocchiamo/alteriamo il salvataggio.
    }

    @Override
    public void projectSaved(IProject project) {
        // Nessuna azione prevista in questa fase.
    }

    // ==================================================================
    // IProjectDiagramListener - diagrammi aggiunti/rimossi dal progetto
    // ==================================================================

    @Override
    public void diagramAdded(IProject project, IDiagramUIModel diagram) {
        log("Diagramma aggiunto al progetto: " + safeName(diagram));
    }

    @Override
    public void diagramRemoved(IProject project, IDiagramUIModel diagram) {
        log("Diagramma rimosso dal progetto: " + safeName(diagram));
    }

    // ==================================================================
    // IProjectModelListener - modelli (UML) aggiunti/rimossi dal progetto
    // ==================================================================

    @Override
    public void modelAdded(IProject project, IModelElement model) {
        log("Modello aggiunto al progetto: " + safeName(model) + " (tipo: " + safeType(model) + ")");
        onModelAdded.accept(model);
    }

    @Override
    public void modelRemoved(IProject project, IModelElement model) {
        log("Modello rimosso dal progetto: " + safeName(model) + " (tipo: " + safeType(model) + ")");
        onModelRemoved.accept(model);
    }

    // ==================================================================
    // Utility interne (solo per il logging diagnostico di questa fase)
    // ==================================================================

    private void log(String message) {
        System.out.println("[COSMIC AI][listener] " + message);
    }

    private String safeName(IProject project) {
        return project == null ? "<null>" : String.valueOf(project.getName());
    }

    private String safeName(IDiagramUIModel diagram) {
        return diagram == null ? "<null>" : String.valueOf(diagram.getName());
    }

    private String safeName(IModelElement model) {
        return model == null ? "<null>" : String.valueOf(model.getName());
    }

    private String safeType(IModelElement model) {
        return model == null ? "<null>" : String.valueOf(model.getModelType());
    }
}
