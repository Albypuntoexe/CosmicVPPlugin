package com.cosmic.vpplugin.generator;

import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.ExtendRelation;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Traduce un {@link CosmicJsonModel} in uno o piu' Use Case Diagram reali
 * dentro il progetto Visual Paradigm correntemente aperto.
 *
 * Pattern Open API (5 step, per ogni elemento), confermato dalla guida
 * ufficiale VP ("Working with diagrams/Diagram elements"):
 *  1. Creare l'oggetto di MODELLO con {@link IModelElementFactory#instance()}.
 *  2. Impostarne le proprieta' (name, description, ...).
 *  3. Creare la sua "shape" (presentation) con
 *     {@link DiagramManager#createDiagramElement}.
 *  4. Impostare le proprieta' grafiche della shape (bounds/posizione).
 *  5. Per le relazioni: creare il modello (Association/Include/Extend),
 *     impostare from/to, poi creare il connettore con
 *     {@link DiagramManager#createConnector}.
 *
 * AGGIORNAMENTI v2.0 (Ultimate):
 *
 * Epic 3 (Multi-Diagramma / Context-Awareness): se i requisiti sono
 * raggruppabili per {@code UseCase.subsystem} (campo opzionale prodotto
 * dall'LLM, si veda {@code CosmicAiService.buildCosmicSystemPrompt}), NON
 * viene piu' generato un solo diagramma monolitico: si genera un
 * {@link IUseCaseDiagramUIModel} per ciascun subsystem distinto. Se nessun
 * Use Case ha un subsystem assegnato, il comportamento resta identico a
 * prima (un solo diagramma "Generated Diagram"). Le relazioni
 * <<include>>/<<extend>> tra Use Case che finiscono su diagrammi DIVERSI
 * non possono avere una freccia disegnata (una connector VP richiede
 * entrambe le shape sullo stesso diagramma): in tal caso viene loggato un
 * avviso invece di forzare una freccia "invisibile" o duplicare lo Use
 * Case su piu' diagrammi (che falserebbe il conteggio COSMIC).
 *
 * Epic 4 (ID Tracking): dopo la creazione di ogni {@code IUseCase}, l'id
 * interno assegnato da VP viene salvato in {@code UseCase.vpElementId} (e
 * l'id del diagramma in {@code UseCase.diagramId}). Da qui in avanti
 * {@code CosmicAiService} usa questo id per la sync bidirezionale, non piu'
 * il nome (fragile su rename manuali).
 *
 * Epic 5 (Feedback visivo): a fine generazione viene invocato
 * {@link VisualFeedbackApplier} per applicare l'etichetta CFP iniziale (0,
 * perche' il calcolo avviene DOPO la generazione nella pipeline di
 * {@code CosmicAiService.runPipelineOnJson}): il valore reale arriva con il
 * primo ricalcolo, gia' cablato nello stesso service.
 *
 * Le righe di log/debug sulle relazioni include/extend restano inviate al
 * {@link #logSink} opzionale (non al Message Pane globale di VP), come
 * nella revisione precedente.
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

    /** Chiave usata per raggruppare gli Use Case senza subsystem esplicito: mantiene il comportamento "un solo diagramma" di v1.x. */
    private static final String DEFAULT_SUBSYSTEM_KEY = "__default__";

    private final DiagramManager diagramManager = ApplicationManager.instance().getDiagramManager();

    /** No-op di default: chi non imposta un log sink non perde alcuna funzionalita'. */
    private Consumer<String> logSink = message -> { };

    /** Collega il generatore al log della UI che ha invocato l'analisi (dialog, futura chat, ecc.). */
    public void setLogSink(Consumer<String> logSink) {
        this.logSink = (logSink != null) ? logSink : (message -> { });
    }

    public void generate(CosmicJsonModel model) {
        Map<String, List<UseCase>> groups = groupBySubsystem(model);
        boolean multiDiagram = groups.size() > 1;

        Map<String, IActor> actorElements = new HashMap<>();
        List<IUseCaseDiagramUIModel> createdDiagrams = new ArrayList<>();

        for (Map.Entry<String, List<UseCase>> group : groups.entrySet()) {
            String subsystemKey = group.getKey();
            List<UseCase> ucsInGroup = group.getValue();

            IUseCaseDiagramUIModel diagram = createDiagram(model.projectName, subsystemKey, multiDiagram);
            createdDiagrams.add(diagram);

            Map<String, IActorUIModel> actorShapes = new HashMap<>();
            Map<String, IUseCaseUIModel> useCaseShapes = new HashMap<>();
            Map<String, IUseCase> useCaseElements = new HashMap<>();

            drawActors(diagram, model, ucsInGroup, actorShapes, actorElements);
            drawUseCases(diagram, ucsInGroup, useCaseShapes, useCaseElements);
            drawPrimaryActorAssociations(diagram, ucsInGroup, actorShapes, actorElements, useCaseShapes, useCaseElements);
            drawIncludeRelations(diagram, ucsInGroup, useCaseShapes, useCaseElements);
            drawExtendRelations(diagram, ucsInGroup, useCaseShapes, useCaseElements);

            diagramManager.openDiagram(diagram);
        }

        if (multiDiagram) {
            logSink.accept("Generati " + createdDiagrams.size() + " diagrammi separati (raggruppamento per subsystem).");
        }
    }

    // ------------------------------------------------------------------
    // 0) Raggruppamento per subsystem (Epic 3)
    // ------------------------------------------------------------------

    /**
     * Restituisce gli Use Case raggruppati per {@code subsystem}, preservando
     * l'ordine di apparizione nel JSON. Se NESSUN Use Case ha un subsystem
     * (caso comune, retro-compatibile), il risultato e' una mappa con
     * un'unica entry {@link #DEFAULT_SUBSYSTEM_KEY} -> tutti gli Use Case,
     * cosi' {@link #generate} produce esattamente un diagramma come in v1.x.
     */
    private Map<String, List<UseCase>> groupBySubsystem(CosmicJsonModel model) {
        Map<String, List<UseCase>> groups = new LinkedHashMap<>();
        for (UseCase uc : model.useCases) {
            String key = (uc.subsystem == null || uc.subsystem.isBlank()) ? DEFAULT_SUBSYSTEM_KEY : uc.subsystem.trim();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(uc);
        }
        if (groups.isEmpty()) {
            groups.put(DEFAULT_SUBSYSTEM_KEY, new ArrayList<>());
        }
        return groups;
    }

    // ------------------------------------------------------------------
    // 1) Creazione del diagramma vuoto
    // ------------------------------------------------------------------

    private IUseCaseDiagramUIModel createDiagram(String projectName, String subsystemKey, boolean multiDiagram) {
        @SuppressWarnings("deprecation")
        String diagramType = DiagramManager.DIAGRAM_TYPE_USE_CASE_DIAGRAM;

        IUseCaseDiagramUIModel diagram =
                (IUseCaseDiagramUIModel) diagramManager.createDiagram(diagramType);

        String baseName = "COSMIC AI - " + (projectName == null ? "Generated Diagram" : projectName);
        String name = (multiDiagram && !DEFAULT_SUBSYSTEM_KEY.equals(subsystemKey))
                ? baseName + " [" + subsystemKey + "]"
                : baseName;
        diagram.setName(name);
        return diagram;
    }

    // ------------------------------------------------------------------
    // 2) Attori (limitati a quelli referenziati dagli Use Case del gruppo corrente)
    // ------------------------------------------------------------------

    private void drawActors(IUseCaseDiagramUIModel diagram, CosmicJsonModel model, List<UseCase> ucsInGroup,
                             Map<String, IActorUIModel> actorShapes,
                             Map<String, IActor> actorElements) {
        int i = 0;
        for (Actor actorDto : model.actors) {
            boolean referencedInGroup = ucsInGroup.stream()
                    .anyMatch(uc -> actorDto.id != null && actorDto.id.equals(uc.primaryActorId));
            if (!referencedInGroup) {
                continue;
            }

            // Il modello IActor e' condiviso tra diagrammi (stesso attore
            // puo' comparire in piu' subsystem): lo creiamo una sola volta
            // e ne riusiamo il riferimento per le shape sugli altri diagrammi.
            IActor actorModel = actorElements.get(actorDto.id);
            if (actorModel == null) {
                actorModel = IModelElementFactory.instance().createActor();
                actorModel.setName(actorDto.name);
                actorModel.setDescription(actorDto.description);
                actorElements.put(actorDto.id, actorModel);
            }

            IActorUIModel actorShape =
                    (IActorUIModel) diagramManager.createDiagramElement(diagram, actorModel);
            actorShape.setBounds(ACTOR_X, ACTOR_Y_START + i * ACTOR_Y_STEP, ACTOR_W, ACTOR_H);

            actorShapes.put(actorDto.id, actorShape);
            i++;
        }
    }

    // ------------------------------------------------------------------
    // 3) Use Case (con descrizione arricchita: scenario, eccezioni, FP)
    //    + Epic 4: salvataggio di vpElementId/diagramId sul DTO
    // ------------------------------------------------------------------

    private void drawUseCases(IUseCaseDiagramUIModel diagram, List<UseCase> ucsInGroup,
                               Map<String, IUseCaseUIModel> useCaseShapes,
                               Map<String, IUseCase> useCaseElements) {
        int i = 0;
        for (UseCase ucDto : ucsInGroup) {
            IUseCase ucModel = IModelElementFactory.instance().createUseCase();
            ucModel.setName(ucDto.name);
            ucModel.setDescription(buildDescription(ucDto));

            int col = i / UC_PER_COL;
            int row = i % UC_PER_COL;

            IUseCaseUIModel ucShape =
                    (IUseCaseUIModel) diagramManager.createDiagramElement(diagram, ucModel);
            ucShape.setBounds(UC_X + col * UC_COL_STEP, UC_Y_START + row * UC_Y_STEP, UC_W, UC_H);

            useCaseShapes.put(ucDto.id, ucShape);
            useCaseElements.put(ucDto.id, ucModel);

            // --- Epic 4: ID tracking robusto ---
            ucDto.vpElementId = ucModel.getId();
            ucDto.diagramId = diagram.getId();

            i++;
        }
    }

    /**
     * Costruisce il testo che finira' nel campo Description/Documentation
     * dello Use Case. Include lo scenario, le eccezioni e la scomposizione
     * in Processi Funzionali COSMIC: questo e' cio' che in futuro il
     * COSMIC Analyzer (component 2 del CosMet, si veda il paper) dovra'
     * poter leggere/aggiornare senza cambiare schema.
     */
    private String buildDescription(UseCase uc) {
        StringBuilder sb = new StringBuilder();
        sb.append(uc.specification == null ? "" : uc.specification).append("\n\n");

        sb.append("MAIN SCENARIO:\n");
        int step = 1;
        for (String s : uc.mainScenario) {
            sb.append(step++).append(") ").append(s).append("\n");
        }

        if (!uc.exceptions.isEmpty()) {
            sb.append("\nEXCEPTIONS:\n");
            for (String e : uc.exceptions) {
                sb.append("- ").append(e).append("\n");
            }
        }

        if (!uc.functionalProcesses.isEmpty()) {
            sb.append("\n--- COSMIC Functional Processes (").append(uc.functionalProcesses.size()).append(") ---\n");
            sb.append("NOTA: la scomposizione UC -> FP NON e' 1:1 (si veda CosMet, De Vito et al.): ")
              .append("questo Use Case genera ").append(uc.functionalProcesses.size())
              .append(" Processo/i Funzionale/i COSMIC distinti.\n");
            for (FunctionalProcess fp : uc.functionalProcesses) {
                sb.append("* FP [").append(fp.fpId).append("] ").append(fp.fpName).append("\n");
                sb.append("  Triggering Event: ").append(fp.triggeringEvent).append("\n");
                for (SubProcess sp : fp.subProcesses) {
                    sb.append("    ").append(sp.step).append(". ").append(sp.description);
                    if (sp.dataMovementType != null) {
                        sb.append(" [").append(sp.dataMovementType)
                          .append(" - DG:").append(sp.dataGroup)
                          .append(" - OOI:").append(sp.objectOfInterest).append("]");
                    } else {
                        sb.append(" [Data Movement da calcolare]");
                    }
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 4) Associazione Attore -> Use Case primario
    // ------------------------------------------------------------------

    private void drawPrimaryActorAssociations(IUseCaseDiagramUIModel diagram, List<UseCase> ucsInGroup,
                                               Map<String, IActorUIModel> actorShapes,
                                               Map<String, IActor> actorElements,
                                               Map<String, IUseCaseUIModel> useCaseShapes,
                                               Map<String, IUseCase> useCaseElements) {
        for (UseCase ucDto : ucsInGroup) {
            if (ucDto.primaryActorId == null) continue;

            IActor fromModel = actorElements.get(ucDto.primaryActorId);
            IUseCase toModel = useCaseElements.get(ucDto.id);
            IActorUIModel fromShape = actorShapes.get(ucDto.primaryActorId);
            IUseCaseUIModel toShape = useCaseShapes.get(ucDto.id);
            if (fromModel == null || toModel == null || fromShape == null || toShape == null) continue;

            IAssociation associationModel = IModelElementFactory.instance().createAssociation();
            associationModel.setFrom(fromModel);
            associationModel.setTo(toModel);

            diagramManager.createConnector(
                    diagram, associationModel, (IDiagramElement) fromShape, (IDiagramElement) toShape, null);
        }
    }

    // ------------------------------------------------------------------
    // 5) Relazioni <<include>> (solo intra-gruppo/diagramma: si veda Epic 3 nel Javadoc di classe)
    // ------------------------------------------------------------------

    private void drawIncludeRelations(IUseCaseDiagramUIModel diagram, List<UseCase> ucsInGroup,
                                       Map<String, IUseCaseUIModel> useCaseShapes,
                                       Map<String, IUseCase> useCaseElements) {
        for (UseCase ucDto : ucsInGroup) {
            IUseCase baseModel = useCaseElements.get(ucDto.id);
            IUseCaseUIModel baseShape = useCaseShapes.get(ucDto.id);
            if (baseModel == null || ucDto.includesIds.isEmpty()) {
                continue;
            }

            for (String includedId : ucDto.includesIds) {
                IUseCase includedModel = useCaseElements.get(includedId);
                IUseCaseUIModel includedShape = useCaseShapes.get(includedId);
                if (includedModel == null || includedShape == null) {
                    logSink.accept("<<include>> SALTATO: '" + includedId
                            + "' non trovato sullo stesso diagramma di '" + ucDto.id
                            + "' (o e' finito in un subsystem/diagramma diverso: con Epic 3 le relazioni "
                            + "cross-diagramma non vengono disegnate come freccia, solo segnalate qui).");
                    continue;
                }

                // Nel modello COSMIC/UML la freccia <<include>> parte dal
                // base use case verso lo use case incluso.
                IInclude includeModel = IModelElementFactory.instance().createInclude();
                includeModel.setFrom(baseModel);
                includeModel.setTo(includedModel);

                diagramManager.createConnector(
                        diagram, includeModel, (IDiagramElement) baseShape, (IDiagramElement) includedShape, null);
            }
        }
    }

    // ------------------------------------------------------------------
    // 6) Relazioni <<extend>> (solo intra-gruppo/diagramma)
    // ------------------------------------------------------------------

    private void drawExtendRelations(IUseCaseDiagramUIModel diagram, List<UseCase> ucsInGroup,
                                      Map<String, IUseCaseUIModel> useCaseShapes,
                                      Map<String, IUseCase> useCaseElements) {
        for (UseCase ucDto : ucsInGroup) {
            IUseCase extensionModel = useCaseElements.get(ucDto.id);
            IUseCaseUIModel extensionShape = useCaseShapes.get(ucDto.id);
            if (extensionModel == null || ucDto.extendsList.isEmpty()) {
                continue;
            }

            for (ExtendRelation ext : ucDto.extendsList) {
                IUseCase baseModel = useCaseElements.get(ext.targetId);
                IUseCaseUIModel baseShape = useCaseShapes.get(ext.targetId);
                if (baseModel == null || baseShape == null) {
                    logSink.accept("<<extend>> SALTATO: targetId '" + ext.targetId
                            + "' non trovato sullo stesso diagramma di '" + ucDto.id
                            + "' (o e' finito in un subsystem/diagramma diverso).");
                    continue;
                }

                // La freccia <<extend>> parte dallo use case "estensione"
                // verso lo use case "base".
                IExtend extendModel = IModelElementFactory.instance().createExtend();
                extendModel.setFrom(extensionModel);
                extendModel.setTo(baseModel);

                if (ext.extensionPoint != null && !ext.extensionPoint.isEmpty()) {
                    IExtensionPoint extensionPoint = IModelElementFactory.instance().createExtensionPoint();
                    extensionPoint.setName(ext.extensionPoint);
                    extendModel.setExtensionPoint(extensionPoint);
                }

                diagramManager.createConnector(
                        diagram, extendModel, (IDiagramElement) extensionShape, (IDiagramElement) baseShape, null);
            }
        }
    }
}
