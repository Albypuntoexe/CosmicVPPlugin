package com.cosmic.vpplugin.ui;

import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.FunctionalProcessReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Epic 2 - Scomposizione e Raggruppamento (requisito del professore).
 *
 * Costruisce l'albero Swing che rende VISIBILE, senza ambiguita', che la
 * relazione tra Use Case e Processi Funzionali COSMIC NON e' 1:1:
 *
 *   Progetto
 *    └─ Use Case "Insert a New Customer" (3 CFP)
 *        ├─ FP "Insert new customer - Validation" (1 CFP)
 *        │    └─ 1. Enter customer data [nessun movimento]
 *        └─ FP "Insert new customer - Storage" (2 CFP)
 *             ├─ 1. Validate and store customer [W]
 *             └─ 2. Show confirmation / error message [X]
 *
 * Un singolo Use Case con PIU' nodi FP figli rende immediatamente evidente
 * la scomposizione 1:N; un FP che elenca PIU' Use Case genitori (raro, ma
 * possibile per fusione di piu' scenari in un solo processo) verrebbe
 * invece rappresentato ripetendo il nodo FP sotto ciascun Use Case
 * coinvolto, con un suffisso "(condiviso)" - si veda {@link #buildTree}.
 *
 * Questa classe e' puro "view model": non tocca mai l'Open API di VP, ne'
 * lo stato del {@code CosmicAiService}. Riceve model+report gia' calcolati
 * e produce solo nodi Swing.
 */
public final class CosmicTreeModelBuilder {

    private CosmicTreeModelBuilder() { }

    public static DefaultMutableTreeNode buildTree(CosmicJsonModel model, CosmicReport report) {
        String rootLabel = "Progetto '" + model.projectName + "' - TOTALE: " + report.totalCfp + " CFP";
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(rootLabel);

        Map<String, UseCaseReport> reportByUcId = new LinkedHashMap<>();
        for (UseCaseReport r : report.useCases) {
            reportByUcId.put(r.id, r);
        }

        for (UseCase uc : model.useCases) {
            UseCaseReport ucReport = reportByUcId.get(uc.id);
            int ucCfp = (ucReport != null) ? ucReport.totalCfp : 0;

            StringBuilder ucLabel = new StringBuilder();
            ucLabel.append("Use Case [").append(uc.id).append("] ").append(uc.name)
                   .append(" -> ").append(ucCfp).append(" CFP");
            if (uc.subsystem != null && !uc.subsystem.isBlank()) {
                ucLabel.append("  (subsystem: ").append(uc.subsystem).append(")");
            }
            if (uc.functionalProcesses.size() != 1) {
                // Evidenzia esplicitamente il caso non-1:1, cosi' l'utente
                // non deve contare i figli per notarlo.
                ucLabel.append("  [" + uc.functionalProcesses.size() + " Functional Process]");
            }
            if (uc.violation != null && !uc.violation.isBlank()) {
                ucLabel.append("  \u26A0 VIOLAZIONE");
            }

            DefaultMutableTreeNode ucNode = new DefaultMutableTreeNode(ucLabel.toString());
            root.add(ucNode);

            if (uc.functionalProcesses.isEmpty()) {
                ucNode.add(new DefaultMutableTreeNode("(nessun Functional Process ancora mappato)"));
                continue;
            }

            Map<String, FunctionalProcessReport> fpReportById = new LinkedHashMap<>();
            if (ucReport != null) {
                for (FunctionalProcessReport fpr : ucReport.functionalProcesses) {
                    fpReportById.put(fpr.fpId, fpr);
                }
            }

            for (FunctionalProcess fp : uc.functionalProcesses) {
                FunctionalProcessReport fpReport = fpReportById.get(fp.fpId);
                int fpCfp = (fpReport != null) ? fpReport.totalCfp : 0;

                String fpLabel = "FP [" + fp.fpId + "] " + fp.fpName + " -> " + fpCfp + " CFP"
                        + ((fpReport != null && fpReport.warning != null) ? "  \u26A0" : "");
                DefaultMutableTreeNode fpNode = new DefaultMutableTreeNode(fpLabel);
                ucNode.add(fpNode);

                fpNode.add(new DefaultMutableTreeNode("Triggering Event: " + fp.triggeringEvent));

                for (SubProcess sp : fp.subProcesses) {
                    String dm = (sp.dataMovementType != null) ? sp.dataMovementType : "-";
                    String spLabel = sp.step + ". " + sp.description + "  [" + dm + "]";
                    if (sp.dataGroup != null) {
                        spLabel += "  DG:" + sp.dataGroup;
                    }
                    if (sp.objectOfInterest != null) {
                        spLabel += " OOI:" + sp.objectOfInterest;
                    }
                    fpNode.add(new DefaultMutableTreeNode(spLabel));
                }

                if (fpReport != null && fpReport.warning != null) {
                    fpNode.add(new DefaultMutableTreeNode("\u26A0 " + fpReport.warning));
                }
            }
        }

        return root;
    }
}
