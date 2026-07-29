package com.cosmic.vpplugin.listener;

import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IProjectDiagramListener;
import com.vp.plugin.model.IProjectListener;
import com.vp.plugin.model.IProjectModelListener;

/**
 * Fase 4 (fondamenta) - Infrastruttura di ascolto per il ricalcolo COSMIC
 * in tempo reale.
 *
 * QUESTA CLASSE NON RICALCOLA ANCORA NULLA: ogni metodo si limita a
 * loggare l'evento intercettato (per verificare che l'aggancio funzioni) e
 * contiene un commento "TODO" che descrive la logica prevista per il
 * prossimo step.
 */
public class CosmicModelChangeListener implements IProjectListener, IProjectDiagramListener, IProjectModelListener {

    /**
     * Nota di riferimento (non un vero campo/metodo invocabile): per gli
     * eventi su un singolo IModelElement (es. rinomina di uno specifico Use
     * Case, cambio di un suo attributo) andra' usato in futuro
     * {@code modelElement.addPropertyChangeListener(PropertyChangeListener)},
     * non un'interfaccia di questo package.
     */
    private static final String TODO_PROPERTY_CHANGE_NOTE =
            "IModelElement.addPropertyChangeListener(...) per eventi sul singolo elemento";

    // ==================================================================
    // IProjectListener - eventi sul progetto
    // ==================================================================

    @Override
    public void projectNewed(IProject project) {
        log("Progetto creato: " + safeName(project));
        // TODO (prossimo step): eventualmente ri-agganciare qui i listener
        // di diagramma/modello se non gia' fatto da CosmicPlugin.
    }

    @Override
    public void projectOpened(IProject project) {
        log("Progetto aperto: " + safeName(project));
        // TODO (prossimo step): ricalcolare da zero i CFP di tutti i
        // diagrammi del progetto appena aperto (baseline iniziale).
    }

    @Override
    public void projectAfterOpened(IProject project) {
        log("Progetto post-apertura completata: " + safeName(project));
        // Aggiunto per soddisfare l'interfaccia IProjectListener.
    }

    @Override
    public void projectRenamed(IProject project) {
        log("Progetto rinominato: " + safeName(project));
        // Nessun impatto previsto sul calcolo COSMIC.
    }

    @Override
    public void projectPreSave(IProject project) {
        // Nessuna azione prevista: non blocchiamo/alteriamo il salvataggio.
    }

    @Override
    public void projectSaved(IProject project) {
        // TODO (prossimo step): eventuale persistenza del report COSMIC
        // calcolato (es. accanto al file .vpp) al momento del salvataggio.
    }

    // ==================================================================
    // IProjectDiagramListener - diagrammi aggiunti/rimossi dal progetto
    // ==================================================================

    @Override
    public void diagramAdded(IProject project, IDiagramUIModel diagram) {
        log("Diagramma aggiunto al progetto: " + safeName(diagram));
        // TODO (prossimo step): registrare IDiagramListener dedicato e ricalcolare.
    }

    @Override
    public void diagramRemoved(IProject project, IDiagramUIModel diagram) {
        log("Diagramma rimosso dal progetto: " + safeName(diagram));
        // TODO (prossimo step): rimuovere il contributo di questo diagramma dal totale.
    }

    // ==================================================================
    // IProjectModelListener - modelli (UML) aggiunti/rimossi dal progetto
    // ==================================================================

    @Override
    public void modelAdded(IProject project, IModelElement model) {
        log("Modello aggiunto al progetto: " + safeName(model) + " (tipo: " + safeType(model) + ")");
        // TODO (prossimo step): aggiornare modello interno e ricalcolare.
    }

    @Override
    public void modelRemoved(IProject project, IModelElement model) {
        log("Modello rimosso dal progetto: " + safeName(model) + " (tipo: " + safeType(model) + ")");
        // TODO (prossimo step): simmetrico a modelAdded.
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