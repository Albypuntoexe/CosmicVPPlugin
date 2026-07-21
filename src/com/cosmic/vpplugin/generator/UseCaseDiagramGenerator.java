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

import java.util.HashMap;
import java.util.Map;

/**
 * Traduce un {@link CosmicJsonModel} in un vero Use Case Diagram dentro il
 * progetto Visual Paradigm correntemente aperto.
 *
 * ATTENZIONE (revisione dopo errori di compilazione riportati dall'utente):
 * questa classe e' stata riscritta usando ESCLUSIVAMENTE l'API confermata
 * dalla documentazione ufficiale Visual Paradigm (Know-how "Create Use Case
 * Diagram using Open API" + JavaDoc di {@code com.vp.plugin.DiagramManager}).
 * La versione precedente usava una classe {@code ModelElementFactory}
 * (package {@code diagram.factory}) e una interfaccia
 * {@code IDiagramTypeConstants} che si sono rivelate non risolvibili nella
 * vostra installazione: sono state rimosse.
 *
 * Pattern reale della Open API (5 step, per ogni elemento):
 *  1. Creare l'oggetto di MODELLO con {@link IModelElementFactory#instance()}.
 *  2. Impostarne le proprieta' (name, description, ...).
 *  3. Creare la sua "shape" (presentation) con
 *     {@link DiagramManager#createDiagramElement(com.vp.plugin.diagram.IDiagramUIModel, com.vp.plugin.model.IModelElement)}.
 *  4. Impostare le proprieta' grafiche della shape (bounds/posizione).
 *  5. Per le relazioni: creare il modello (Association/Include/Extend),
 *     impostare from/to, poi creare il connettore con
 *     {@link DiagramManager#createConnector(com.vp.plugin.diagram.IDiagramUIModel, com.vp.plugin.model.IModelElement, IDiagramElement, IDiagramElement, java.awt.Point[])}.
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

    private final DiagramManager diagramManager = ApplicationManager.instance().getDiagramManager();

    public void generate(CosmicJsonModel model) {
        IUseCaseDiagramUIModel diagram = createDiagram(model.projectName);

        Map<String, IActorUIModel> actorShapes = new HashMap<>();
        Map<String, IUseCaseUIModel> useCaseShapes = new HashMap<>();
        Map<String, IUseCase> useCaseElements = new HashMap<>();
        Map<String, IActor> actorElements = new HashMap<>();

        drawActors(diagram, model, actorShapes, actorElements);
        drawUseCases(diagram, model, useCaseShapes, useCaseElements);
        drawPrimaryActorAssociations(diagram, model, actorShapes, actorElements, useCaseShapes, useCaseElements);
        drawIncludeRelations(diagram, model, useCaseShapes, useCaseElements);
        drawExtendRelations(diagram, model, useCaseShapes, useCaseElements);

        diagramManager.openDiagram(diagram);
    }

    // ------------------------------------------------------------------
    // 1) Creazione del diagramma vuoto
    // ------------------------------------------------------------------

    private IUseCaseDiagramUIModel createDiagram(String projectName) {
        // NOTA: DiagramManager.DIAGRAM_TYPE_USE_CASE_DIAGRAM e' formalmente
        // "deprecated in favore di IDiagramTypeConstants" nella documentazione
        // VP, ma resta funzionante ed e' garantito presente in ogni versione
        // (a differenza di IDiagramTypeConstants, che nella vostra
        // installazione non si e' risolto). Se in futuro volete rimuovere il
        // warning di deprecazione, verificate nell'IDE quale package espone
        // davvero IDiagramTypeConstants nella vostra versione di openapi.jar
        // e sostituite la costante qui sotto.
        @SuppressWarnings("deprecation")
        String diagramType = DiagramManager.DIAGRAM_TYPE_USE_CASE_DIAGRAM;

        IUseCaseDiagramUIModel diagram =
                (IUseCaseDiagramUIModel) diagramManager.createDiagram(diagramType);
        diagram.setName("COSMIC AI - " + (projectName == null ? "Generated Diagram" : projectName));
        return diagram;
    }

    // ------------------------------------------------------------------
    // 2) Attori
    // ------------------------------------------------------------------

    private void drawActors(IUseCaseDiagramUIModel diagram, CosmicJsonModel model,
                             Map<String, IActorUIModel> actorShapes,
                             Map<String, IActor> actorElements) {
        int i = 0;
        for (Actor actorDto : model.actors) {
            // Step 1-2: elemento di modello
            IActor actorModel = IModelElementFactory.instance().createActor();
            actorModel.setName(actorDto.name);
            actorModel.setDescription(actorDto.description);

            // Step 3-4: shape sul diagramma
            IActorUIModel actorShape =
                    (IActorUIModel) diagramManager.createDiagramElement(diagram, actorModel);
            actorShape.setBounds(ACTOR_X, ACTOR_Y_START + i * ACTOR_Y_STEP, ACTOR_W, ACTOR_H);

            actorShapes.put(actorDto.id, actorShape);
            actorElements.put(actorDto.id, actorModel);
            i++;
        }
    }

    // ------------------------------------------------------------------
    // 3) Use Case (con descrizione arricchita: scenario, eccezioni, FP)
    // ------------------------------------------------------------------

    private void drawUseCases(IUseCaseDiagramUIModel diagram, CosmicJsonModel model,
                               Map<String, IUseCaseUIModel> useCaseShapes,
                               Map<String, IUseCase> useCaseElements) {
        int i = 0;
        for (UseCase ucDto : model.useCases) {
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
            i++;
        }
    }

    /**
     * Costruisce il testo che finira' nel campo Description/Documentation
     * dello Use Case. Include lo scenario, le eccezioni e, soprattutto, la
     * scomposizione in Processi Funzionali COSMIC: questo e' cio' che in
     * futuro il COSMIC Analyzer (component 2 del CosMet, si veda il paper)
     * potra' rileggere per completare Data Movement / Data Group / Object of
     * Interest, senza dover ripartire dal solo testo del requisito.
     */
    private String buildDescription(UseCase uc) {
        StringBuilder sb = new StringBuilder();
        sb.append(uc.specification).append("\n\n");

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

    private void drawPrimaryActorAssociations(IUseCaseDiagramUIModel diagram, CosmicJsonModel model,
                                               Map<String, IActorUIModel> actorShapes,
                                               Map<String, IActor> actorElements,
                                               Map<String, IUseCaseUIModel> useCaseShapes,
                                               Map<String, IUseCase> useCaseElements) {
        for (UseCase ucDto : model.useCases) {
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
    // 5) Relazioni <<include>>
    // ------------------------------------------------------------------

    private void drawIncludeRelations(IUseCaseDiagramUIModel diagram, CosmicJsonModel model,
                                       Map<String, IUseCaseUIModel> useCaseShapes,
                                       Map<String, IUseCase> useCaseElements) {
        for (UseCase ucDto : model.useCases) {
            IUseCase baseModel = useCaseElements.get(ucDto.id);
            IUseCaseUIModel baseShape = useCaseShapes.get(ucDto.id);
            if (baseModel == null) continue;

            if (ucDto.includesIds.isEmpty()) {
                continue; // nessuna relazione include da tracciare per questo UC
            }

            for (String includedId : ucDto.includesIds) {
                ApplicationManager.instance().getViewManager().showMessage(
                        "[COSMIC AI][DEBUG] Tentativo <<include>> da '" + ucDto.id
                                + "' verso '" + includedId + "'...");

                IUseCase includedModel = useCaseElements.get(includedId);
                IUseCaseUIModel includedShape = useCaseShapes.get(includedId);
                if (includedModel == null || includedShape == null) {
                    ApplicationManager.instance().getViewManager().showMessage(
                            "[COSMIC AI][DEBUG] SALTATO: '" + includedId
                                    + "' non trovato tra gli Use Case disegnati "
                                    + "(controlla che l'id combaci esattamente con lo 'id' di un altro useCase nel JSON).");
                    continue;
                }

                // Nel modello COSMIC/UML la freccia <<include>> parte dal
                // base use case verso lo use case incluso.
                IInclude includeModel = IModelElementFactory.instance().createInclude();
                includeModel.setFrom(baseModel);
                includeModel.setTo(includedModel);

                diagramManager.createConnector(
                        diagram, includeModel, (IDiagramElement) baseShape, (IDiagramElement) includedShape, null);

                ApplicationManager.instance().getViewManager().showMessage(
                        "[COSMIC AI][DEBUG] <<include>> da '" + ucDto.id + "' a '" + includedId + "' -> Fatto.");
            }
        }
    }

    // ------------------------------------------------------------------
    // 6) Relazioni <<extend>>
    // ------------------------------------------------------------------

    private void drawExtendRelations(IUseCaseDiagramUIModel diagram, CosmicJsonModel model,
                                      Map<String, IUseCaseUIModel> useCaseShapes,
                                      Map<String, IUseCase> useCaseElements) {
        for (UseCase ucDto : model.useCases) {
            IUseCase extensionModel = useCaseElements.get(ucDto.id);
            IUseCaseUIModel extensionShape = useCaseShapes.get(ucDto.id);
            if (extensionModel == null) continue;

            if (ucDto.extendsList.isEmpty()) {
                continue; // nessuna relazione extend da tracciare per questo UC
            }

            for (ExtendRelation ext : ucDto.extendsList) {
                ApplicationManager.instance().getViewManager().showMessage(
                        "[COSMIC AI][DEBUG] Tentativo <<extend>> da '" + ucDto.id
                                + "' verso '" + ext.targetId + "' (extension point: '" + ext.extensionPoint + "')...");

                IUseCase baseModel = useCaseElements.get(ext.targetId);
                IUseCaseUIModel baseShape = useCaseShapes.get(ext.targetId);
                if (baseModel == null || baseShape == null) {
                    ApplicationManager.instance().getViewManager().showMessage(
                            "[COSMIC AI][DEBUG] SALTATO: targetId '" + ext.targetId
                                    + "' non trovato tra gli Use Case disegnati "
                                    + "(controlla che l'id combaci esattamente con lo 'id' di un altro useCase nel JSON).");
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

                ApplicationManager.instance().getViewManager().showMessage(
                        "[COSMIC AI][DEBUG] <<extend>> da '" + ucDto.id + "' a '" + ext.targetId + "' -> Fatto.");
            }
        }
    }
}
