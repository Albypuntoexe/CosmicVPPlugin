package com.cosmic.vpplugin.generator;

import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.ExtendRelation;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.diagram.IUseCaseDiagramUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IActorUIModel;
import com.vp.plugin.diagram.IUseCaseUIModel;
import com.vp.plugin.model.IActor;
import com.vp.plugin.model.IUseCase;
import com.vp.plugin.model.IInclude;
import com.vp.plugin.model.IExtend;
import com.vp.plugin.model.IAssociation;
import com.vp.plugin.model.factory.IModelElementFactory;
import com.vp.plugin.diagram.factory.ModelElementFactory;
import com.vp.plugin.model.factory.IDiagramTypeConstants;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;

/**
 * Traduce un {@link CosmicJsonModel} in un vero e proprio Use Case Diagram
 * dentro il progetto Visual Paradigm correntemente aperto.
 *
 * Classi della Open API utilizzate e perche':
 *
 *  - {@link ApplicationManager} / {@code getDiagramManager()}: punto di
 *    ingresso "ufficiale" per creare un nuovo diagramma nel progetto attivo.
 *  - {@link IUseCaseDiagramUIModel}: rappresentazione *grafica* (UI model) del
 *    diagramma Use Case; e' il contenitore su cui vengono posizionate le
 *    shape.
 *  - {@link IModelElementFactory} (livello MODELLO, non grafico): crea gli
 *    elementi concettuali IActor, IUseCase, IInclude, IExtend, IAssociation
 *    che finiscono nel repository del progetto (e quindi, ad esempio, sono
 *    visibili anche nel Model Explorer, non solo sul diagramma).
 *  - {@link ModelElementFactory} (factory grafica, package
 *    com.vp.plugin.diagram.factory): crea la *presentation* a schermo (shape
 *    o connettore) associata a un elemento di modello gia' creato, e la
 *    posiziona sul diagramma alle coordinate desiderate.
 *
 * Il pattern "crea l'elemento di modello, poi crea/posiziona la sua
 * presentation sul diagramma" e' quello richiesto dalla Open API di VP per
 * generare diagrammi in modo programmatico.
 *
 * NOTA IMPORTANTE: le firme esatte dei metodi di {@code ModelElementFactory}
 * (nomi degli overload per creare connettori Include/Extend/Association)
 * possono variare leggermente tra le versioni della Open API installate.
 * Il codice sotto usa i nomi piu' comuni documentati da Visual Paradigm;
 * verificarli con l'autocompletamento IDE contro il jar effettivo
 * (vp-openapi.jar) prima della prima compilazione.
 */
public final class UseCaseDiagramGenerator {

    private static final int ACTOR_X = 60;
    private static final int ACTOR_Y_START = 60;
    private static final int ACTOR_Y_STEP = 160;

    private static final int UC_X = 320;
    private static final int UC_Y_START = 60;
    private static final int UC_Y_STEP = 130;
    private static final int UC_COL_STEP = 260;
    private static final int UC_PER_COL = 5;

    public void generate(CosmicJsonModel model) {
        IUseCaseDiagramUIModel diagram = createDiagram(model.projectName);

        Map<String, IActorUIModel> actorShapes = new HashMap<>();
        Map<String, IUseCaseUIModel> useCaseShapes = new HashMap<>();
        Map<String, IUseCase> useCaseElements = new HashMap<>();

        drawActors(diagram, model, actorShapes);
        drawUseCases(diagram, model, useCaseShapes, useCaseElements);
        drawPrimaryActorAssociations(diagram, model, actorShapes, useCaseShapes);
        drawIncludeRelations(diagram, model, useCaseShapes, useCaseElements);
        drawExtendRelations(diagram, model, useCaseShapes, useCaseElements);
    }

    // ------------------------------------------------------------------
    // 1) Creazione del diagramma vuoto
    // ------------------------------------------------------------------

    private IUseCaseDiagramUIModel createDiagram(String projectName) {
        IDiagramUIModel diagramUIModel = ApplicationManager.instance()
                .getDiagramManager()
                .createDiagram(IDiagramTypeConstants.DIAGRAM_TYPE_USE_CASE_DIAGRAM);

        IUseCaseDiagramUIModel diagram = (IUseCaseDiagramUIModel) diagramUIModel;
        diagram.setName("COSMIC AI - " + (projectName == null ? "Generated Diagram" : projectName));
        return diagram;
    }

    // ------------------------------------------------------------------
    // 2) Attori
    // ------------------------------------------------------------------

    private void drawActors(IUseCaseDiagramUIModel diagram, CosmicJsonModel model,
                             Map<String, IActorUIModel> actorShapes) {
        int i = 0;
        for (Actor actorDto : model.actors) {
            // Elemento di modello
            IActor actorModel = IModelElementFactory.instance().createActor();
            actorModel.setName(actorDto.name);
            actorModel.setDescription(actorDto.description);

            // Presentation grafica sul diagramma
            Point location = new Point(ACTOR_X, ACTOR_Y_START + i * ACTOR_Y_STEP);
            IActorUIModel actorShape = (IActorUIModel) ModelElementFactory.instance()
                    .createDiagramViewAndAddToDiagram(actorModel, diagram, location);

            actorShapes.put(actorDto.id, actorShape);
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
            Point location = new Point(UC_X + col * UC_COL_STEP, UC_Y_START + row * UC_Y_STEP);

            IUseCaseUIModel ucShape = (IUseCaseUIModel) ModelElementFactory.instance()
                    .createDiagramViewAndAddToDiagram(ucModel, diagram, location);

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
                                               Map<String, IUseCaseUIModel> useCaseShapes) {
        for (UseCase ucDto : model.useCases) {
            if (ucDto.primaryActorId == null) continue;
            IActorUIModel from = actorShapes.get(ucDto.primaryActorId);
            IUseCaseUIModel to = useCaseShapes.get(ucDto.id);
            if (from == null || to == null) continue;

            IAssociation assocModel = IModelElementFactory.instance()
                    .createAssociation(from.getModelElement(), to.getModelElement());

            ModelElementFactory.instance().createConnectorAndAddToDiagram(
                    assocModel, diagram, (IDiagramElement) from, (IDiagramElement) to);
        }
    }

    // ------------------------------------------------------------------
    // 5) Relazioni <<include>>
    // ------------------------------------------------------------------

    private void drawIncludeRelations(IUseCaseDiagramUIModel diagram, CosmicJsonModel model,
                                       Map<String, IUseCaseUIModel> useCaseShapes,
                                       Map<String, IUseCase> useCaseElements) {
        for (UseCase ucDto : model.useCases) {
            IUseCase base = useCaseElements.get(ucDto.id);
            IUseCaseUIModel baseShape = useCaseShapes.get(ucDto.id);
            if (base == null) continue;

            for (String includedId : ucDto.includesIds) {
                IUseCase included = useCaseElements.get(includedId);
                IUseCaseUIModel includedShape = useCaseShapes.get(includedId);
                if (included == null || includedShape == null) continue;

                // Nel modello COSMIC/UML la freccia <<include>> parte dal base
                // use case verso lo use case incluso.
                IInclude includeModel = IModelElementFactory.instance().createInclude(base, included);

                ModelElementFactory.instance().createConnectorAndAddToDiagram(
                        includeModel, diagram, (IDiagramElement) baseShape, (IDiagramElement) includedShape);
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
            IUseCase extension = useCaseElements.get(ucDto.id);
            IUseCaseUIModel extensionShape = useCaseShapes.get(ucDto.id);
            if (extension == null) continue;

            for (ExtendRelation ext : ucDto.extendsList) {
                IUseCase base = useCaseElements.get(ext.targetId);
                IUseCaseUIModel baseShape = useCaseShapes.get(ext.targetId);
                if (base == null || baseShape == null) continue;

                // La freccia <<extend>> parte dallo use case "estensione"
                // verso lo use case "base".
                IExtend extendModel = IModelElementFactory.instance().createExtend(extension, base);
                if (ext.extensionPoint != null && !ext.extensionPoint.isEmpty()) {
                    extendModel.setDescription(ext.extensionPoint);
                }

                ModelElementFactory.instance().createConnectorAndAddToDiagram(
                        extendModel, diagram, (IDiagramElement) extensionShape, (IDiagramElement) baseShape);
            }
        }
    }
}
