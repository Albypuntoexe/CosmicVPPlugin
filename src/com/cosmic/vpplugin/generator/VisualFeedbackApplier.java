package com.cosmic.vpplugin.generator;

import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.shape.IUseCaseUIModel;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IUseCase;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Epic 5 - Feedback Visivo su Canvas (WYSIWYG).
 *
 * Applica sulla shape reale di Visual Paradigm:
 *  1) un'etichetta COSMIC dinamica "&lt;&lt;CFP:n&gt;&gt;" quando il
 *     Calculator ricalcola i Function Point di uno Use Case;
 *  2) un colore/etichetta di allarme quando lo Use Case e' in violazione
 *     (regola COSMIC minima o requisito originario mancante).
 *
 * LIMITI NOTI DELL'OPEN API DI VP (dichiarati esplicitamente, non bug
 * nascosti):
 *  - Visual Paradigm non espone un concetto di "tagged value numerico
 *    dinamico" pensato per essere ridisegnato ad ogni ricalcolo con la
 *    stessa leggerezza di un semplice testo: la via realmente disponibile e
 *    documentata e' {@code IModelElement.addStereotype(String)} /
 *    {@code removeStereotype(String)}, che mostra il testo tra
 *    "«...»" accanto al nome dell'elemento su TUTTE le shape che lo
 *    rappresentano (comportamento nativo di VP, non replicabile diagramma
 *    per diagramma).
 *  - La colorazione e' applicata a livello di singola VISTA (shape), non di
 *    elemento di modello: {@code IUseCaseUIModel} eredita da
 *    {@code IShapeUIModel}, che espone {@code setFillColor(Color)}. Se lo
 *    stesso Use Case appare su piu' diagrammi (Epic 3), la colorazione va
 *    quindi ripetuta esplicitamente per ciascuna shape/vista: qui la
 *    risolviamo tramite {@code element.getDiagramElements()} (nome di
 *    metodo secondo la guida VP "Model Element and its Views" - se la tua
 *    versione dell'SDK usa una firma diversa, es. {@code getViews()},
 *    adatta questo unico punto).
 *  - Non abbiamo modificato plugin.xml: nessuna nuova voce di menu o
 *    listener nativo e' stata aggiunta, questa classe e' invocata
 *    programmaticamente da {@code UseCaseDiagramGenerator} e da
 *    {@code CosmicAiService} dopo ogni ricalcolo.
 */
public final class VisualFeedbackApplier {

    /** Prefisso usato per riconoscere ed eventualmente rimuovere la nostra etichetta CFP tra gli stereotipi esistenti. */
    private static final String CFP_STEREOTYPE_PREFIX = "CFP:";
    private static final String VIOLATION_STEREOTYPE_PREFIX = "Violation";

    private static final Color VIOLATION_FILL_COLOR = new Color(255, 205, 205); // rosso tenue, non invasivo
    private static final Color DEFAULT_FILL_COLOR = null; // null = "nessun colore custom", VP applica lo stile di default

    private VisualFeedbackApplier() { }

    /**
     * Applica CFP + violazioni per TUTTI gli Use Case del modello che
     * hanno gia' un {@code vpElementId} (cioe' sono stati effettivamente
     * disegnati). Va chiamato dopo ogni {@code CosmicCalculator.compute} +
     * {@code applyViolationFlags}.
     */
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
                continue; // non ancora disegnato: nulla su cui applicare feedback
            }
            IModelElement element = project.getModelElementById(ucDto.vpElementId);
            if (!(element instanceof IUseCase)) {
                continue; // elemento rimosso manualmente dal diagramma nel frattempo
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
                ucModel.setDescription(appendViolationNote(ucModel.getDescription(), violationMessage));
            }
        } catch (Exception ex) {
            System.out.println("[COSMIC AI][visual] Impossibile applicare lo stereotipo di violazione: " + ex);
        }

        for (IDiagramElement view : safeGetDiagramElements(ucModel)) {
            if (view instanceof IUseCaseUIModel) {
                try {
                    ((IUseCaseUIModel) view).setFillColor(hasViolation ? VIOLATION_FILL_COLOR : DEFAULT_FILL_COLOR);
                } catch (Exception ex) {
                    System.out.println("[COSMIC AI][visual] Impossibile colorare la shape: " + ex);
                }
            }
        }
    }

    /**
     * Punto dell'SDK con nome "best-effort": la guida ufficiale VP
     * ("Model Element and its Views") documenta la navigazione dal modello
     * alle sue shape sui diagrammi, ma il nome esatto del metodo varia tra
     * major version dell'SDK (visto sia come {@code getDiagramElements()}
     * sia come {@code getViews()} in build diverse). Centralizziamo qui il
     * punto di adattamento: se la tua versione compila con un nome diverso,
     * cambia SOLO questo metodo.
     */
    private static IDiagramElement[] safeGetDiagramElements(IModelElement element) {
        try {
            return element.getDiagramElements();
        } catch (Exception ex) {
            return new IDiagramElement[0];
        }
    }

    private static void removeStereotypesWithPrefix(IModelElement element, String prefix) {
        // Nome metodo secondo la guida VP "Working with Stereotypes":
        // IModelElement.toStereotypeArray() -> String[] degli stereotipi correnti.
        String[] existing = element.toStereotypeArray();
        if (existing == null) {
            return;
        }
        for (String stereotype : existing) {
            if (stereotype != null && stereotype.startsWith(prefix)) {
                element.removeStereotype(stereotype);
            }
        }
    }

    private static String appendViolationNote(String currentDescription, String violationMessage) {
        String base = (currentDescription == null) ? "" : currentDescription;
        String marker = "\n\n[COSMIC AI - VIOLAZIONE] ";
        // Evita di accumulare note duplicate ad ogni ricalcolo: rimuove
        // l'eventuale nota precedente prima di riscriverla.
        int markerIndex = base.indexOf("[COSMIC AI - VIOLAZIONE]");
        if (markerIndex >= 0) {
            int noteStart = base.lastIndexOf('\n', markerIndex);
            base = (noteStart > 0) ? base.substring(0, noteStart) : "";
        }
        return base + marker + violationMessage;
    }
}
