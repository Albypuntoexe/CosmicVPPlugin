package com.cosmic.vpplugin.generator;

import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.ExtendRelation;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.DiagramManager;
import com.vp.plugin.diagram.shape.IActorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IUseCaseDiagramUIModel;
import com.vp.plugin.diagram.shape.IUseCaseUIModel;
import com.vp.plugin.model.IActor;
import com.vp.plugin.model.IAssociation;
import com.vp.plugin.model.IExtend;
import com.vp.plugin.model.IExtensionPoint;
import com.vp.plugin.model.IInclude;
import com.vp.plugin.model.IUseCase;
import com.vp.plugin.model.factory.IModelElementFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Traduce gli Use Case in Use Case Diagram reali di Visual Paradigm, UNO ALLA VOLTA.
 *
 * v3.0 - RENDERING INCREMENTALE (anti-freeze). Prima {@code generate(model)} riceveva l'intero modello e
 * disegnava centinaia di elementi in un unico blocco sul thread grafico (Event Dispatch Thread), congelando
 * l'IDE. Ora il ciclo di analisi usa il ciclo di vita:
 *
 * <pre>
 *   generator.begin(projectName, multiDiagram);          // una volta
 *   generator.appendUseCase(model, uc);                  // dopo OGNI use case misurato (pochi elementi, ~ms)
 *   generator.finish();                                  // una volta
 * </pre>
 *
 * Tra due {@code appendUseCase} l'EDT e' libero (il thread di lavoro e' in attesa dell'LLM): l'utente vede il
 * diagramma crescere pezzo per pezzo e l'IDE resta reattivo. {@link #generate(CosmicJsonModel)} resta come
 * scorciatoia "a lotto" per chi ha gia' un modello completo.
 *
 * TUTTI i metodi pubblici vanno invocati sul thread grafico (EDT): l'Open API di VP non e' thread-safe.
 *
 * Multi-diagramma (Epic 3): un diagramma per {@code UseCase.subsystem} (creato al primo use case del gruppo e
 * aperto subito, cosi' l'ultimo aperto e' quello "in costruzione"). Senza subsystem: un solo diagramma.
 * Le relazioni &lt;&lt;include&gt;&gt;/&lt;&lt;extend&gt;&gt; si disegnano appena ESISTONO ENTRAMBE le shape sullo
 * stesso diagramma (l'use case incluso puo' comparire piu' avanti nel documento); se finiscono su diagrammi
 * diversi una connector VP non e' possibile e viene solo segnalato nel log (niente duplicati di UC: falserebbero
 * il conteggio COSMIC).
 *
 * ID tracking (Epic 4): {@code UseCase.vpElementId}/{@code diagramId} e {@code Actor.vpElementId} sono
 * valorizzati subito dopo la creazione dell'elemento.
 */
public final class UseCaseDiagramGenerator {

    private static final int ACTOR_X = 60;
    private static final int ACTOR_Y_START = 60;
    private static final int ACTOR_Y_STEP = 160;
    private static final int ACTOR_W = 60;
    private static final int ACTOR_H = 90;

    private static final int UC_X = 320;
    private static final int UC_Y_START = 60;
    private static final int UC_Y_STEP = 130;
    private static final int UC_COL_STEP = 260;
    private static final int UC_PER_COL = 5;
    private static final int UC_W = 220;
    private static final int UC_H = 90;

    /** Chiave per gli use case senza subsystem: mantiene il comportamento "un solo diagramma". */
    private static final String DEFAULT_SUBSYSTEM_KEY = "__default__";

    private final DiagramManager diagramManager = ApplicationManager.instance().getDiagramManager();

    private Consumer<String> logSink = message -> { };

    // ---- stato della sessione incrementale ----
    private String projectName;
    private boolean multiDiagram;
    private final Map<String, DiagramState> diagrams = new LinkedHashMap<>();
    private final Map<String, IActor> actorElements = new HashMap<>();
    private final Map<String, String> useCaseDiagramKey = new HashMap<>();
    private final List<PendingRelation> pending = new ArrayList<>();

    private static final class DiagramState {
        final String key;
        final IUseCaseDiagramUIModel diagram;
        final Map<String, IActorUIModel> actorShapes = new HashMap<>();
        final Map<String, IUseCaseUIModel> useCaseShapes = new HashMap<>();
        final Map<String, IUseCase> useCaseElements = new HashMap<>();
        int useCaseCount;
        int actorCount;

        DiagramState(String key, IUseCaseDiagramUIModel diagram) {
            this.key = key;
            this.diagram = diagram;
        }
    }

    private static final class PendingRelation {
        final boolean include;
        final String fromUcId;
        final String toUcId;
        final String extensionPoint;

        PendingRelation(boolean include, String fromUcId, String toUcId, String extensionPoint) {
            this.include = include;
            this.fromUcId = fromUcId;
            this.toUcId = toUcId;
            this.extensionPoint = extensionPoint;
        }
    }

    /** Collega il generatore al log della UI che ha invocato l'analisi. */
    public void setLogSink(Consumer<String> logSink) {
        this.logSink = (logSink != null) ? logSink : (message -> { });
    }

    // ------------------------------------------------------------------
    // Ciclo di vita incrementale
    // ------------------------------------------------------------------

    /**
     * Avvia una nuova sessione di disegno.
     *
     * @param multiDiagram true se gli use case appartengono a piu' subsystem distinti (i nomi dei diagrammi
     *                     riportano il subsystem tra parentesi quadre)
     */
    public void begin(String projectName, boolean multiDiagram) {
        this.projectName = projectName;
        this.multiDiagram = multiDiagram;
        diagrams.clear();
        actorElements.clear();
        useCaseDiagramKey.clear();
        pending.clear();
    }

    /**
     * Disegna UN use case (e gli attori ancora non presenti sul suo diagramma, le associazioni attore-UC e le
     * relazioni di include/extend ora risolvibili). Pochi elementi: tempo trascurabile sull'EDT.
     */
    public void appendUseCase(CosmicJsonModel model, UseCase uc) {
        String key = subsystemKey(uc);
        DiagramState state = diagrams.get(key);
        if (state == null) {
            state = new DiagramState(key, createDiagram(key));
            diagrams.put(key, state);
            diagramManager.openDiagram(state.diagram);   // visibile subito: l'utente lo vede crescere
        }

        // --- Use Case ---
        IUseCase ucModel = IModelElementFactory.instance().createUseCase();
        ucModel.setName(uc.name);
        ucModel.setDescription(buildDescription(uc));
        IUseCaseUIModel ucShape = (IUseCaseUIModel) diagramManager.createDiagramElement(state.diagram, ucModel);
        int col = state.useCaseCount / UC_PER_COL;
        int row = state.useCaseCount % UC_PER_COL;
        ucShape.setBounds(UC_X + col * UC_COL_STEP, UC_Y_START + row * UC_Y_STEP, UC_W, UC_H);
        state.useCaseCount++;

        state.useCaseShapes.put(uc.id, ucShape);
        state.useCaseElements.put(uc.id, ucModel);
        useCaseDiagramKey.put(uc.id, key);
        uc.vpElementId = ucModel.getId();          // Epic 4: ID tracking robusto
        uc.diagramId = state.diagram.getId();

        // --- Attori + associazioni (primario, poi gli altri) ---
        List<String> actorIds = new ArrayList<>();
        if (uc.primaryActorId != null) {
            actorIds.add(uc.primaryActorId);
        }
        for (String co : uc.coActorIds) {
            if (co != null && !actorIds.contains(co)) {
                actorIds.add(co);
            }
        }
        for (String actorId : actorIds) {
            drawActorAssociation(state, model, actorId, uc.id);
        }

        // --- Relazioni (si disegnano quando esistono entrambe le shape) ---
        for (String includedId : uc.includesIds) {
            pending.add(new PendingRelation(true, uc.id, includedId, null));
        }
        for (ExtendRelation ext : uc.extendsList) {
            pending.add(new PendingRelation(false, uc.id, ext.targetId, ext.extensionPoint));
        }
        flushPendingRelations(false);
    }

    /** Chiude la sessione: segnala le relazioni mai risolte e riassume i diagrammi creati. */
    public void finish() {
        flushPendingRelations(true);
        if (multiDiagram) {
            logSink.accept("Generati " + diagrams.size() + " diagrammi separati (raggruppamento per subsystem).");
        }
        pending.clear();
    }

    /** Scorciatoia "a lotto": equivale a begin + appendUseCase per ogni use case + finish. */
    public void generate(CosmicJsonModel model) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (UseCase uc : model.useCases) {
            keys.add(subsystemKey(uc));
        }
        begin(model.projectName, keys.size() > 1);
        for (UseCase uc : model.useCases) {
            appendUseCase(model, uc);
        }
        finish();
    }

    // ------------------------------------------------------------------
    // Diagramma
    // ------------------------------------------------------------------

    private static String subsystemKey(UseCase uc) {
        return (uc.subsystem == null || uc.subsystem.isBlank()) ? DEFAULT_SUBSYSTEM_KEY : uc.subsystem.trim();
    }

    private IUseCaseDiagramUIModel createDiagram(String subsystemKey) {
        @SuppressWarnings("deprecation")
        String diagramType = DiagramManager.DIAGRAM_TYPE_USE_CASE_DIAGRAM;

        IUseCaseDiagramUIModel diagram = (IUseCaseDiagramUIModel) diagramManager.createDiagram(diagramType);

        String baseName = "COSMIC AI - " + (projectName == null ? "Generated Diagram" : projectName);
        String name = (multiDiagram && !DEFAULT_SUBSYSTEM_KEY.equals(subsystemKey))
                ? baseName + " [" + subsystemKey + "]"
                : baseName;
        diagram.setName(name);
        return diagram;
    }

    // ------------------------------------------------------------------
    // Attori
    // ------------------------------------------------------------------

    private void drawActorAssociation(DiagramState state, CosmicJsonModel model, String actorId, String ucId) {
        Actor actorDto = findActor(model, actorId);
        IUseCase ucModel = state.useCaseElements.get(ucId);
        IUseCaseUIModel ucShape = state.useCaseShapes.get(ucId);
        if (actorDto == null || ucModel == null || ucShape == null) {
            return;
        }

        // Il modello IActor e' condiviso tra i diagrammi: si crea una volta sola, le shape sono per diagramma.
        IActor actorModel = actorElements.get(actorId);
        if (actorModel == null) {
            actorModel = IModelElementFactory.instance().createActor();
            actorModel.setName(actorDto.name);
            actorModel.setDescription(actorDto.description == null ? "" : actorDto.description);
            actorElements.put(actorId, actorModel);
            actorDto.vpElementId = actorModel.getId();
        }

        IActorUIModel actorShape = state.actorShapes.get(actorId);
        if (actorShape == null) {
            actorShape = (IActorUIModel) diagramManager.createDiagramElement(state.diagram, actorModel);
            actorShape.setBounds(ACTOR_X, ACTOR_Y_START + state.actorCount * ACTOR_Y_STEP, ACTOR_W, ACTOR_H);
            state.actorCount++;
            state.actorShapes.put(actorId, actorShape);
        }

        IAssociation associationModel = IModelElementFactory.instance().createAssociation();
        associationModel.setFrom(actorModel);
        associationModel.setTo(ucModel);
        diagramManager.createConnector(
                state.diagram, associationModel, (IDiagramElement) actorShape, (IDiagramElement) ucShape, null);
    }

    private static Actor findActor(CosmicJsonModel model, String actorId) {
        for (Actor a : model.actors) {
            if (a.id != null && a.id.equals(actorId)) {
                return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Relazioni <<include>> / <<extend>>
    // ------------------------------------------------------------------

    private void flushPendingRelations(boolean finalPass) {
        Iterator<PendingRelation> it = pending.iterator();
        while (it.hasNext()) {
            PendingRelation rel = it.next();
            String fromKey = useCaseDiagramKey.get(rel.fromUcId);
            String toKey = useCaseDiagramKey.get(rel.toUcId);
            String kind = rel.include ? "<<include>>" : "<<extend>>";

            if (fromKey == null || toKey == null) {
                if (finalPass) {
                    logSink.accept(kind + " NON DISEGNATO: " + rel.fromUcId + " -> " + rel.toUcId
                            + " (use case di destinazione assente dal modello).");
                    it.remove();
                }
                continue; // l'altro estremo potrebbe comparire piu' avanti nel documento
            }
            if (!fromKey.equals(toKey)) {
                logSink.accept(kind + " non disegnato (cross-diagramma): " + rel.fromUcId + " -> " + rel.toUcId
                        + " [" + describeKey(fromKey) + " -> " + describeKey(toKey) + "]");
                it.remove();
                continue;
            }
            DiagramState st = diagrams.get(fromKey);
            if (rel.include) {
                drawInclude(st, rel);
            } else {
                drawExtend(st, rel);
            }
            it.remove();
        }
    }

    private static String describeKey(String key) {
        return DEFAULT_SUBSYSTEM_KEY.equals(key) ? "default" : key;
    }

    private void drawInclude(DiagramState st, PendingRelation rel) {
        IUseCase baseModel = st.useCaseElements.get(rel.fromUcId);
        IUseCase includedModel = st.useCaseElements.get(rel.toUcId);
        IUseCaseUIModel baseShape = st.useCaseShapes.get(rel.fromUcId);
        IUseCaseUIModel includedShape = st.useCaseShapes.get(rel.toUcId);
        if (baseModel == null || includedModel == null || baseShape == null || includedShape == null) {
            return;
        }
        // Nel modello COSMIC/UML la freccia <<include>> parte dal base use case verso lo use case incluso.
        IInclude includeModel = IModelElementFactory.instance().createInclude();
        includeModel.setFrom(baseModel);
        includeModel.setTo(includedModel);
        diagramManager.createConnector(
                st.diagram, includeModel, (IDiagramElement) baseShape, (IDiagramElement) includedShape, null);
    }

    private void drawExtend(DiagramState st, PendingRelation rel) {
        IUseCase extensionModel = st.useCaseElements.get(rel.fromUcId);
        IUseCase baseModel = st.useCaseElements.get(rel.toUcId);
        IUseCaseUIModel extensionShape = st.useCaseShapes.get(rel.fromUcId);
        IUseCaseUIModel baseShape = st.useCaseShapes.get(rel.toUcId);
        if (extensionModel == null || baseModel == null || extensionShape == null || baseShape == null) {
            return;
        }
        // La freccia <<extend>> parte dallo use case "estensione" verso lo use case "base".
        IExtend extendModel = IModelElementFactory.instance().createExtend();
        extendModel.setFrom(extensionModel);
        extendModel.setTo(baseModel);
        if (rel.extensionPoint != null && !rel.extensionPoint.isEmpty()) {
            IExtensionPoint extensionPoint = IModelElementFactory.instance().createExtensionPoint();
            extensionPoint.setName(rel.extensionPoint);
            extendModel.setExtensionPoint(extensionPoint);
        }
        diagramManager.createConnector(
                st.diagram, extendModel, (IDiagramElement) extensionShape, (IDiagramElement) baseShape, null);
    }

    // ------------------------------------------------------------------
    // Descrizione dello use case (scenario, eccezioni, FP COSMIC)
    // ------------------------------------------------------------------

    /**
     * Testo del campo Description dello Use Case. v4.0: la struttura (specifica modificabile + blocco COSMIC generato)
     * e' definita in {@link UseCaseDescription}, cosi' "Rimisura da VP" e le correzioni manuali la leggono/riscrivono
     * con la stessa logica.
     */
    private String buildDescription(UseCase uc) {
        return UseCaseDescription.build(uc);
    }
}
