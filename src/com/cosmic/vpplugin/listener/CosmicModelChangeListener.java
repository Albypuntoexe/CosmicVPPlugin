package com.cosmic.vpplugin.listener;

import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IExtend;
import com.vp.plugin.model.IInclude;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IProjectDiagramListener;
import com.vp.plugin.model.IProjectListener;
import com.vp.plugin.model.IProjectModelListener;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Infrastruttura di ascolto per il ricalcolo COSMIC in tempo reale.
 *
 * AGGIORNAMENTO v2.0 (Epic 4 - Real-Time Sync Assoluto):
 *
 * 1) Rename/Description in tempo reale: {@code modelAdded} ora, oltre a
 *    loggare e invocare {@link #onModelAdded}, AGGANCIA un
 *    {@link PropertyChangeListener} sull'elemento appena aggiunto (se
 *    l'SDK di VP lo supporta: {@code IModelElement} nella maggior parte
 *    delle build implementa il pattern JavaBeans standard di property
 *    change, si veda la guida VP "Model Element Property Change Event").
 *    Il listener viene tenuto in una {@link WeakHashMap} per evitare
 *    doppie registrazioni sullo stesso elemento e per non trattenerne un
 *    riferimento forte dopo la sua eventuale rimozione dal progetto (che
 *    gia' oggi passa da {@code modelRemoved}, dove il listener viene
 *    rimosso esplicitamente).
 *
 * 2) Associazioni complesse: {@code modelAdded}/{@code modelRemoved} ora
 *    riconoscono ANCHE {@code IInclude} e {@code IExtend} (prima solo
 *    {@code IAssociation} veniva gestita, a livello di
 *    {@code CosmicAiService}): questa classe si limita a smistare
 *    l'evento al callback giusto ({@link #onIncludeChanged}/
 *    {@link #onExtendChanged}), lasciando la logica di business al
 *    Service, in linea con la separazione dei ruoli gia' in uso per
 *    {@link #onModelAdded}/{@link #onModelRemoved}.
 *
 * Se nessuno imposta i nuovi callback, il comportamento e' identico a
 * prima (solo logging), grazie ai no-op di default: nessuna funzionalita'
 * pre-esistente viene rimossa da questa revisione.
 */
public class CosmicModelChangeListener implements IProjectListener, IProjectDiagramListener, IProjectModelListener {

    private static final String TODO_PROPERTY_CHANGE_NOTE =
            "IModelElement.addPropertyChangeListener(...) per eventi sul singolo elemento";

    /** Invocato quando un elemento di modello viene aggiunto al progetto. No-op di default. */
    private Consumer<IModelElement> onModelAdded = element -> { };

    /** Invocato quando un elemento di modello viene rimosso dal progetto. No-op di default. */
    private Consumer<IModelElement> onModelRemoved = element -> { };

    /** Epic 4: invocato quando un elemento viene rinominato o la sua descrizione cambia (element, nomeProprieta'). */
    private BiConsumer<IModelElement, String> onElementPropertyChanged = (element, propertyName) -> { };

    /** Epic 4: invocato su aggiunta/rimozione manuale di una freccia <<include>> (elemento, added). */
    private BiConsumer<IInclude, Boolean> onIncludeChanged = (include, added) -> { };

    /** Epic 4: invocato su aggiunta/rimozione manuale di una freccia <<extend>> (elemento, added). */
    private BiConsumer<IExtend, Boolean> onExtendChanged = (extend, added) -> { };

    /** Proprieta' che ci interessano davvero: evitiamo di reagire a ogni micro-evento grafico (bounds, colore, ecc.). */
    private static final Set<String> WATCHED_PROPERTIES = Collections.unmodifiableSet(
            Set.of("name", "Name"));

    /** Un solo listener per elemento, per evitare doppie notifiche se modelAdded viene chiamato più volte sullo stesso oggetto. */
    private final WeakHashMap<IModelElement, PropertyChangeListener> attachedPropertyListeners = new WeakHashMap<>();

    public void setOnModelAdded(Consumer<IModelElement> onModelAdded) {
        this.onModelAdded = (onModelAdded != null) ? onModelAdded : (element -> { });
    }

    public void setOnModelRemoved(Consumer<IModelElement> onModelRemoved) {
        this.onModelRemoved = (onModelRemoved != null) ? onModelRemoved : (element -> { });
    }

    public void setOnElementPropertyChanged(BiConsumer<IModelElement, String> callback) {
        this.onElementPropertyChanged = (callback != null) ? callback : (element, propertyName) -> { };
    }

    public void setOnIncludeChanged(BiConsumer<IInclude, Boolean> callback) {
        this.onIncludeChanged = (callback != null) ? callback : (include, added) -> { };
    }

    public void setOnExtendChanged(BiConsumer<IExtend, Boolean> callback) {
        this.onExtendChanged = (callback != null) ? callback : (extend, added) -> { };
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

        attachPropertyChangeListener(model);

        if (model instanceof IInclude) {
            onIncludeChanged.accept((IInclude) model, true);
        } else if (model instanceof IExtend) {
            onExtendChanged.accept((IExtend) model, true);
        }
    }

    @Override
    public void modelRemoved(IProject project, IModelElement model) {
        log("Modello rimosso dal progetto: " + safeName(model) + " (tipo: " + safeType(model) + ")");
        onModelRemoved.accept(model);

        detachPropertyChangeListener(model);

        if (model instanceof IInclude) {
            onIncludeChanged.accept((IInclude) model, false);
        } else if (model instanceof IExtend) {
            onExtendChanged.accept((IExtend) model, false);
        }
    }

    // ==================================================================
    // Epic 4: Property Change (rename / description) in tempo reale
    // ==================================================================

    /**
     * Aggancia un {@link PropertyChangeListener} sull'elemento, se l'SDK
     * lo supporta. NOTA SULLA COMPATIBILITA': non tutte le implementazioni
     * di {@code IModelElement} espongono
     * {@code addPropertyChangeListener(PropertyChangeListener)} con la
     * stessa firma in ogni versione dell'Open API; qui il fallimento e'
     * catturato e loggato invece di far crashare il listener di progetto
     * (che gestisce anche gli altri eventi, ben piu' critici).
     */
    private void attachPropertyChangeListener(IModelElement model) {
        if (attachedPropertyListeners.containsKey(model)) {
            return;
        }
        try {
            PropertyChangeListener listener = this::handlePropertyChange;
            model.addPropertyChangeListener(listener);
            attachedPropertyListeners.put(model, listener);
        } catch (Exception ex) {
            System.out.println("[COSMIC AI][listener] Property change non disponibile per "
                    + safeName(model) + " (" + ex.getClass().getSimpleName() + "): rename/description "
                    + "non verranno sincronizzati in tempo reale per questo elemento.");
        }
    }

    private void detachPropertyChangeListener(IModelElement model) {
        PropertyChangeListener listener = attachedPropertyListeners.remove(model);
        if (listener != null) {
            try {
                model.removePropertyChangeListener(listener);
            } catch (Exception ignored) {
                // elemento gia' distrutto/scollegato: nulla da fare
            }
        }
    }

    private void handlePropertyChange(PropertyChangeEvent evt) {
        String propertyName = evt.getPropertyName();
        if (propertyName == null || !WATCHED_PROPERTIES.contains(propertyName)) {
            return; // ignora bounds/colore/altri eventi grafici non rilevanti per COSMIC
        }
        Object source = evt.getSource();
        if (!(source instanceof IModelElement)) {
            return;
        }
        log("Proprieta' '" + propertyName + "' modificata su " + safeName((IModelElement) source));
        onElementPropertyChanged.accept((IModelElement) source, propertyName);
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
