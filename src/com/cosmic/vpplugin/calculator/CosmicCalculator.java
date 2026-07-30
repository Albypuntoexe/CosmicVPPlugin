package com.cosmic.vpplugin.calculator;

import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Motore di calcolo COSMIC (Fase 3).
 *
 * Implementa, sulla struttura dati gia' esistente {@link CosmicJsonModel},
 * le regole di conteggio descritte nel paper CosMet (Sez. 2.1 e Tab. 1):
 *
 *  - Ogni Data Movement (Entry "E", Exit "X", Read "R", Write "W") presente
 *    in un {@link SubProcess#dataMovementType} vale 1 CFP (COSMIC Function
 *    Point). I sotto-processi con {@code dataMovementType == null} (nessun
 *    movimento dati, es. un semplice click che non scambia dati) non
 *    contribuiscono al conteggio.
 *
 *  - Regola COSMIC di validita' minima di un Functional Process: deve
 *    contenere almeno 2 CFP, tipicamente una Entry più una Write o una Exit
 *    ("Each process must include at least two CFPs, typically formed by an
 *    E plus either a W or an X" - si veda Sez. 2.1 del paper). Questa classe
 *    NON scarta né "corregge" i FP che non rispettano la regola (sarebbe
 *    un'invenzione arbitraria di dati non presenti nell'input): si limita a
 *    segnalarli come warning nel report, cosi' che l'utente/misuratore possa
 *    intervenire manualmente sui requisiti, in linea con lo spirito di
 *    "trasparenza e interpretabilita'" di CosMet.
 *
 *  - Un singolo Use Case puo' generare piu' Functional Process (mai un
 *    rapporto 1:1 imposto): il totale per Use Case e' semplicemente la somma
 *    dei CFP dei suoi FP, cosi' come il totale di progetto e' la somma dei
 *    CFP di tutti gli Use Case.
 *
 * La classe e' interamente "stateless" per {@link #compute}: ogni chiamata
 * riceve il modello e restituisce un nuovo {@link CosmicReport}, senza
 * effetti collaterali su {@link CosmicJsonModel} (che resta la fonte di
 * verita' proveniente dal JSON/LLM). L'unica eccezione deliberata e'
 * {@link #applyViolationFlags}, introdotto in v2.0 per l'Epic 5: scrive
 * {@code UseCase.violation} SUL modello, perche' e' proprio quel campo che
 * il generatore di diagramma legge per decidere se colorare la shape.
 *
 * NOVITA' v2.0 (Ultimate):
 *  - {@link #computeScoped}: Epic 3 (multi-diagramma / context-awareness).
 *    Calcola un report limitato a un sottoinsieme di Use Case (tipicamente:
 *    quelli disegnati sul diagramma attualmente attivo in VP, individuati
 *    da {@code UseCase.diagramId}), lasciando {@link #compute} come calcolo
 *    "Totale Progetto" invariato.
 *  - {@link #applyViolationFlags}: Epic 5 (feedback visivo). Dopo un
 *    {@link #compute}, propaga sul modello un messaggio di violazione
 *    sintetico per ogni Use Case che contiene almeno un FP non conforme.
 */
public final class CosmicCalculator {

    /** I quattro tipi di Data Movement validi secondo il manuale COSMIC. */
    private static final String ENTRY = "E";
    private static final String EXIT = "X";
    private static final String READ = "R";
    private static final String WRITE = "W";

    public CosmicReport compute(CosmicJsonModel model) {
        return computeScoped(model, null);
    }

    /**
     * Come {@link #compute}, ma se {@code useCaseIdsInScope} non e' null
     * limita la somma ai soli Use Case il cui {@code id} e' contenuto
     * nell'insieme (Epic 3: CFP "nello scope del diagramma attivo").
     * Se {@code useCaseIdsInScope} e' null, il comportamento e' identico
     * a prima (Totale Progetto, tutti gli Use Case).
     */
    public CosmicReport computeScoped(CosmicJsonModel model, Set<String> useCaseIdsInScope) {
        CosmicReport report = new CosmicReport(model.projectName);

        for (UseCase uc : model.useCases) {
            if (useCaseIdsInScope != null && !useCaseIdsInScope.contains(uc.id)) {
                continue;
            }
            UseCaseReport ucReport = new UseCaseReport(uc.id, uc.name);

            for (FunctionalProcess fp : uc.functionalProcesses) {
                FunctionalProcessReport fpReport = computeFunctionalProcess(fp);
                ucReport.functionalProcesses.add(fpReport);
                ucReport.totalCfp += fpReport.totalCfp;
            }

            report.useCases.add(ucReport);
            report.totalCfp += ucReport.totalCfp;
        }

        return report;
    }

    /**
     * Epic 5: propaga sul modello (mutandolo) un messaggio di violazione
     * sintetico per ogni Use Case che ha almeno un Functional Process non
     * conforme alla regola minima COSMIC. Il generatore/service leggeranno
     * {@code UseCase.violation} per applicare il feedback visivo sulla
     * shape (stereotipo/colore). Se un Use Case torna conforme dopo una
     * modifica, il campo viene ripulito (settato a null) per far
     * "sparire" la colorazione di allarme.
     */
    public void applyViolationFlags(CosmicJsonModel model, CosmicReport report) {
        Map<String, UseCaseReport> byId = new LinkedHashMap<>();
        for (UseCaseReport r : report.useCases) {
            byId.put(r.id, r);
        }
        for (UseCase uc : model.useCases) {
            UseCaseReport r = byId.get(uc.id);
            uc.violation = (r != null) ? buildViolationSummary(r) : null;
        }
    }

    private String buildViolationSummary(UseCaseReport ucReport) {
        List<String> problems = new ArrayList<>();
        for (FunctionalProcessReport fp : ucReport.functionalProcesses) {
            if (fp.warning != null) {
                problems.add("[" + fp.fpId + "] " + fp.warning);
            }
            if (!fp.unknownMovementTypes.isEmpty()) {
                problems.add("[" + fp.fpId + "] Data movement non riconosciuti: " + fp.unknownMovementTypes);
            }
        }
        if (problems.isEmpty()) {
            return null;
        }
        return String.join(" | ", problems);
    }

    private FunctionalProcessReport computeFunctionalProcess(FunctionalProcess fp) {
        FunctionalProcessReport fpReport = new FunctionalProcessReport(fp.fpId, fp.fpName, fp.triggeringEvent);

        for (SubProcess sp : fp.subProcesses) {
            String dm = normalize(sp.dataMovementType);
            if (dm == null) {
                continue; // nessun movimento dati per questo sotto-processo (es. semplice click)
            }
            switch (dm) {
                case ENTRY:
                    fpReport.entryCount++;
                    break;
                case EXIT:
                    fpReport.exitCount++;
                    break;
                case READ:
                    fpReport.readCount++;
                    break;
                case WRITE:
                    fpReport.writeCount++;
                    break;
                default:
                    fpReport.unknownMovementTypes.add(sp.dataMovementType);
                    break;
            }
        }

        fpReport.totalCfp = fpReport.entryCount + fpReport.exitCount + fpReport.readCount + fpReport.writeCount;

        // Regola COSMIC minima: almeno 1 Entry e almeno 1 (Write o Exit).
        boolean hasEntry = fpReport.entryCount > 0;
        boolean hasWriteOrExit = fpReport.writeCount > 0 || fpReport.exitCount > 0;
        if (fpReport.totalCfp < 2 || !hasEntry || !hasWriteOrExit) {
            fpReport.warning = "Il processo funzionale non rispetta la regola minima COSMIC "
                    + "(>= 2 CFP, con almeno una Entry e una Write/Exit): CFP attuali = "
                    + fpReport.totalCfp + ". Verificare i requisiti di origine.";
        }

        return fpReport;
    }

    private String normalize(String dataMovementType) {
        if (dataMovementType == null) {
            return null;
        }
        String trimmed = dataMovementType.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Accetta sia la sigla ("E","X","R","W") sia il nome per intero
        // ("Entry","Exit","Read","Write"), case-insensitive, per tollerare
        // eventuali variazioni nell'output del futuro LLM reale.
        String upper = trimmed.toUpperCase();
        switch (upper) {
            case "E": case "ENTRY": return ENTRY;
            case "X": case "EXIT": return EXIT;
            case "R": case "READ": return READ;
            case "W": case "WRITE": return WRITE;
            default: return upper; // resta "sconosciuto": verra' segnalato
        }
    }

    // ==================================================================
    // Modello del report (in-memory, riusabile sia per stampa testuale
    // sia per eventuale futura serializzazione/visualizzazione grafica)
    // ==================================================================

    public static final class CosmicReport {
        public final String projectName;
        public final List<UseCaseReport> useCases = new ArrayList<>();
        public int totalCfp;

        public CosmicReport(String projectName) {
            this.projectName = projectName;
        }

        /** Report leggibile, pensato per essere stampato nella JTextArea di log. */
        public String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== COSMIC Functional Size Measurement ===\n");
            sb.append("Progetto: ").append(projectName).append("\n");
            sb.append("TOTALE: ").append(totalCfp).append(" CFP\n\n");

            for (UseCaseReport uc : useCases) {
                sb.append("- Use Case [").append(uc.id).append("] ").append(uc.name)
                  .append(" -> ").append(uc.totalCfp).append(" CFP\n");
                for (FunctionalProcessReport fp : uc.functionalProcesses) {
                    sb.append("    * FP [").append(fp.fpId).append("] ").append(fp.fpName)
                      .append(": E=").append(fp.entryCount)
                      .append(" X=").append(fp.exitCount)
                      .append(" R=").append(fp.readCount)
                      .append(" W=").append(fp.writeCount)
                      .append(" -> ").append(fp.totalCfp).append(" CFP\n");
                    if (fp.warning != null) {
                        sb.append("      [WARNING] ").append(fp.warning).append("\n");
                    }
                    if (!fp.unknownMovementTypes.isEmpty()) {
                        sb.append("      [WARNING] Data movement type non riconosciuti: ")
                          .append(fp.unknownMovementTypes).append("\n");
                    }
                }
                if (uc.functionalProcesses.isEmpty()) {
                    sb.append("    (nessun Functional Process ancora mappato per questo Use Case)\n");
                }
            }
            return sb.toString();
        }

        /** Vista "a mappa" per chi preferisce lavorare con dati strutturati invece di testo. */
        public Map<String, Object> toMap() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("projectName", projectName);
            root.put("totalCfp", totalCfp);
            List<Object> ucList = new ArrayList<>();
            for (UseCaseReport uc : useCases) {
                Map<String, Object> ucMap = new LinkedHashMap<>();
                ucMap.put("id", uc.id);
                ucMap.put("name", uc.name);
                ucMap.put("totalCfp", uc.totalCfp);
                List<Object> fpList = new ArrayList<>();
                for (FunctionalProcessReport fp : uc.functionalProcesses) {
                    Map<String, Object> fpMap = new LinkedHashMap<>();
                    fpMap.put("fpId", fp.fpId);
                    fpMap.put("fpName", fp.fpName);
                    fpMap.put("triggeringEvent", fp.triggeringEvent);
                    fpMap.put("entry", fp.entryCount);
                    fpMap.put("exit", fp.exitCount);
                    fpMap.put("read", fp.readCount);
                    fpMap.put("write", fp.writeCount);
                    fpMap.put("totalCfp", fp.totalCfp);
                    fpMap.put("warning", fp.warning);
                    fpList.add(fpMap);
                }
                ucMap.put("functionalProcesses", fpList);
                ucList.add(ucMap);
            }
            root.put("useCases", ucList);
            return root;
        }
    }

    public static final class UseCaseReport {
        public final String id;
        public final String name;
        public final List<FunctionalProcessReport> functionalProcesses = new ArrayList<>();
        public int totalCfp;

        public UseCaseReport(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class FunctionalProcessReport {
        public final String fpId;
        public final String fpName;
        public final String triggeringEvent;
        public int entryCount;
        public int exitCount;
        public int readCount;
        public int writeCount;
        public int totalCfp;
        /** Non-null se il FP viola la regola minima COSMIC (>=2 CFP, E + W/X). */
        public String warning;
        public final List<String> unknownMovementTypes = new ArrayList<>();

        public FunctionalProcessReport(String fpId, String fpName, String triggeringEvent) {
            this.fpId = fpId;
            this.fpName = fpName;
            this.triggeringEvent = triggeringEvent;
        }
    }
}
