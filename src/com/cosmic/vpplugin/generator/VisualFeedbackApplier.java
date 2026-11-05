package com.cosmic.vpplugin.generator;

import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import com.vp.plugin.ApplicationManager;
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

    /** Applica CFP + violazioni a TUTTI gli use case del modello (uso: ricalcoli globali). */
    public static void applyAll(CosmicJsonModel model, CosmicReport report) {
        Map<String, UseCaseReport> cfpById = new LinkedHashMap<>();
        for (UseCaseReport r : report.useCases) {
            cfpById.put(r.id, r);
        }
        for (UseCase ucDto : model.useCases) {
            applyToUseCase(ucDto, cfpById.get(ucDto.id));
        }
    }

    /**
     * v3.0 - Applica stereotipo {@code <<CFP:N>>} e, se {@code ucDto.violation} e' valorizzato, {@code <<Violation>>}
     * a UN solo use case. E' la variante usata dal rendering incrementale: dopo ogni use case misurato si tocca
     * solo la sua shape, invece di rifare tutti gli use case del diagramma (O(n^2) chiamate all'Open API).
     * Va invocato sul thread grafico (EDT).
     */
    public static void applyToUseCase(UseCase ucDto, UseCaseReport cfpReport) {
        if (ucDto == null || ucDto.vpElementId == null) {
            return;
        }
        IProject project = ApplicationManager.instance().getProjectManager().getProject();
        if (project == null) {
            return;
        }
        IModelElement element = project.getModelElementById(ucDto.vpElementId);
        if (!(element instanceof IUseCase)) {
            return;
        }
        IUseCase ucModel = (IUseCase) element;
        int cfp = (cfpReport != null) ? cfpReport.totalCfp : 0;
        applyCfpStereotype(ucModel, cfp);
        applyViolationFeedback(ucModel, ucDto.violation);
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
        if (currentDescription == null) {
            currentDescription = "";
        }

        // Cerca e rimuove perfettamente la vecchia nota, inclusi i due a-capo precedenti
        int noteIndex = currentDescription.indexOf("\n\n[COSMIC AI - VIOLAZIONE]");
        if (noteIndex >= 0) {
            currentDescription = currentDescription.substring(0, noteIndex);
        } else {
            // Fallback nel caso in cui gli a-capo siano stati cancellati a mano
            int fallbackIndex = currentDescription.indexOf("[COSMIC AI - VIOLAZIONE]");
            if (fallbackIndex >= 0) {
                currentDescription = currentDescription.substring(0, fallbackIndex).trim();
            }
        }

        return currentDescription + "\n\n[COSMIC AI - VIOLAZIONE] " + violationMessage;
    }
}