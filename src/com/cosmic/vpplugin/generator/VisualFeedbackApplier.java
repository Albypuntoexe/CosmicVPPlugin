package com.cosmic.vpplugin.generator;

import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IStereotype;
import com.vp.plugin.model.IUseCase;

import java.util.LinkedHashMap;
import java.util.Map;

public final class VisualFeedbackApplier {

    private static final String CFP_STEREOTYPE_PREFIX = "CFP:";
    private static final String VIOLATION_STEREOTYPE_PREFIX = "Violation";

    private VisualFeedbackApplier() { }

    public static void applyAll(CosmicJsonModel model, CosmicReport report) {
        IProject project = ApplicationManager.instance().getProjectManager().getProject();
        if (project == null) {
            return;
        }
        Map<String, UseCaseReport> cfpById = new LinkedHashMap<>();
        for (UseCaseReport r : report.useCases) {
            cfpById.put(r.id, r);
        }

        for (UseCase ucDto : model.useCases) {
            if (ucDto.vpElementId == null) {
                continue;
            }
            IModelElement element = project.getModelElementById(ucDto.vpElementId);
            if (!(element instanceof IUseCase)) {
                continue;
            }
            IUseCase ucModel = (IUseCase) element;

            UseCaseReport cfpReport = cfpById.get(ucDto.id);
            int cfp = (cfpReport != null) ? cfpReport.totalCfp : 0;

            applyCfpStereotype(ucModel, cfp);
            applyViolationFeedback(ucModel, ucDto.violation);
        }
    }

    private static void applyCfpStereotype(IUseCase ucModel, int cfp) {
        try {
            removeStereotypesWithPrefix(ucModel, CFP_STEREOTYPE_PREFIX);
            ucModel.addStereotype(CFP_STEREOTYPE_PREFIX + cfp);
        } catch (Exception ex) {
            System.out.println("[COSMIC AI][visual] Impossibile applicare l'etichetta CFP: " + ex);
        }
    }

    private static void applyViolationFeedback(IUseCase ucModel, String violationMessage) {
        boolean hasViolation = violationMessage != null && !violationMessage.isBlank();
        try {
            removeStereotypesWithPrefix(ucModel, VIOLATION_STEREOTYPE_PREFIX);
            if (hasViolation) {
                ucModel.addStereotype(VIOLATION_STEREOTYPE_PREFIX);

                // FIX: Controlliamo se la descrizione cambia davvero prima di sovrascriverla!
                String currentDesc = ucModel.getDescription();
                String newDesc = appendViolationNote(currentDesc, violationMessage);
                if (!newDesc.equals(currentDesc)) {
                    ucModel.setDescription(newDesc);
                }
            }
        } catch (Exception ex) {
            System.out.println("[COSMIC AI][visual] Impossibile applicare lo stereotipo di violazione: " + ex);
        }
    }

    private static void removeStereotypesWithPrefix(IModelElement element, String prefix) {
        // Fix per VP 17.3: usa toStereotypeModelArray() invece di toStereotypeArray()
        IStereotype[] existing = element.toStereotypeModelArray();
        if (existing == null) {
            return;
        }
        for (IStereotype stereotype : existing) {
            if (stereotype != null && stereotype.getName() != null && stereotype.getName().startsWith(prefix)) {
                element.removeStereotype(stereotype.getName());
            }
        }
    }

    private static String appendViolationNote(String currentDescription, String violationMessage) {
        String base = (currentDescription == null) ? "" : currentDescription;
        String marker = "\n\n[COSMIC AI - VIOLAZIONE] ";
        int markerIndex = base.indexOf("[COSMIC AI - VIOLAZIONE]");
        if (markerIndex >= 0) {
            int noteStart = base.lastIndexOf('\n', markerIndex);
            base = (noteStart > 0) ? base.substring(0, noteStart) : "";
        }
        return base + marker + violationMessage;
    }
}