package com.cosmic.vpplugin.ui;

import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.FunctionalProcessReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.Enumeration;
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

    /**
     * v4.0 - Payload di ogni nodo (al posto della semplice stringa): la UI sa a quale use case / functional process
     * appartiene il nodo selezionato senza dover interpretare l'etichetta, e puo' cosi' mostrare la tabella
     * modificabile dei data movement, ritrovare un nodo dopo una ricostruzione e sostituirlo (upsert).
     * {@link #toString()} restituisce l'etichetta, quindi JTree continua a mostrare lo stesso testo di prima.
     */
    public static final class NodeData {
        public enum Kind { ROOT, USE_CASE, FUNCTIONAL_PROCESS, SUB_PROCESS, INFO }

        public final Kind kind;
        public final String ucId;     // null per ROOT
        public final String fpId;     // non null solo per FP e sotto-processi
        public final String label;

        public NodeData(Kind kind, String ucId, String fpId, String label) {
            this.kind = kind;
            this.ucId = ucId;
            this.fpId = fpId;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static DefaultMutableTreeNode node(NodeData.Kind kind, String ucId, String fpId, String label) {
        return new DefaultMutableTreeNode(new NodeData(kind, ucId, fpId, label));
    }

    /** Payload di un nodo, o null se il nodo non e' stato costruito da questa classe (es. messaggio iniziale). */
    public static NodeData dataOf(Object treeNode) {
        if (treeNode instanceof DefaultMutableTreeNode) {
            Object o = ((DefaultMutableTreeNode) treeNode).getUserObject();
            if (o instanceof NodeData) return (NodeData) o;
        }
        return null;
    }

    /** Il figlio diretto di {@code root} che rappresenta lo use case {@code ucId}, o null. */
    public static DefaultMutableTreeNode findUseCaseNode(DefaultMutableTreeNode root, String ucId) {
        if (ucId == null) return null;
        for (Enumeration<?> e = root.children(); e.hasMoreElements(); ) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) e.nextElement();
            NodeData d = dataOf(child);
            if (d != null && d.kind == NodeData.Kind.USE_CASE && ucId.equals(d.ucId)) return child;
        }
        return null;
    }

    public static DefaultMutableTreeNode buildTree(CosmicJsonModel model, CosmicReport report) {
        DefaultMutableTreeNode root = node(NodeData.Kind.ROOT, null, null, rootLabel(model.projectName, report.totalCfp));

        Map<String, UseCaseReport> reportByUcId = new LinkedHashMap<>();
        for (UseCaseReport r : report.useCases) {
            reportByUcId.put(r.id, r);
        }

        for (UseCase uc : model.useCases) {
            root.add(buildUseCaseNode(uc, reportByUcId.get(uc.id)));
        }

        return root;
    }

    /**
     * v3.0 - Nodo Swing di UN solo use case (con i suoi FP e sotto-processi): permette alla UI di aggiungere i
     * nodi uno alla volta mentre l'analisi procede, senza ricostruire l'intero albero.
     */
    public static DefaultMutableTreeNode buildUseCaseNode(UseCase uc, UseCaseReport ucReport) {
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

        DefaultMutableTreeNode ucNode = node(NodeData.Kind.USE_CASE, uc.id, null, ucLabel.toString());

        if (uc.functionalProcesses.isEmpty()) {
            ucNode.add(node(NodeData.Kind.INFO, uc.id, null, "(nessun Functional Process ancora mappato)"));
            return ucNode;
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
            DefaultMutableTreeNode fpNode = node(NodeData.Kind.FUNCTIONAL_PROCESS, uc.id, fp.fpId, fpLabel);
            ucNode.add(fpNode);

            fpNode.add(node(NodeData.Kind.INFO, uc.id, fp.fpId, "Triggering Event: " + fp.triggeringEvent));

            for (SubProcess sp : fp.subProcesses) {
                String dm = (sp.dataMovementType != null) ? sp.dataMovementType : "-";
                String spLabel = sp.step + ". " + sp.description + "  [" + dm + "]";
                if (sp.dataGroup != null) {
                    spLabel += "  DG:" + sp.dataGroup;
                }
                if (sp.objectOfInterest != null) {
                    spLabel += " OOI:" + sp.objectOfInterest;
                }
                if (sp.unmeasured) spLabel += "  \u26A0 NON MISURATA";
                else if (sp.manuallyEdited) spLabel += "  \u270E modificata";
                fpNode.add(node(NodeData.Kind.SUB_PROCESS, uc.id, fp.fpId, spLabel));
            }

            if (fpReport != null && fpReport.warning != null) {
                fpNode.add(node(NodeData.Kind.INFO, uc.id, fp.fpId, "\u26A0 " + fpReport.warning));
            }
        }
        return ucNode;
    }

    /** Etichetta della radice dell'albero (nome progetto + totale CFP corrente). */
    public static String rootLabel(String projectName, int totalCfp) {
        return "Progetto '" + projectName + "' - TOTALE: " + totalCfp + " CFP";
    }
}
