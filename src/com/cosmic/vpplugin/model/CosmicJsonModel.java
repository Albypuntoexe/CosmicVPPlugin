package com.cosmic.vpplugin.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Modello di dominio "intermedio" tra la risposta (mock, in futuro reale) del
 * LLM e la generazione grafica su Visual Paradigm.
 *
 * Punti di design rilevanti per la tesi:
 *
 * 1) Un {@link UseCase} NON coincide 1:1 con un Processo Funzionale COSMIC.
 *    Come evidenziato dal paper CosMet (De Vito et al.), un singolo Use Case
 *    puo' generare piu' Functional Process (es. "Search" + "Display" possono
 *    essere due FP distinti, oppure un FP puo' derivare dalla fusione di piu'
 *    step). Per questo motivo ogni UseCase contiene una lista di
 *    {@link FunctionalProcess}, non un singolo processo.
 *
 * 2) I campi COSMIC veri e propri (Data Movement Type, Data Group, Object of
 *    Interest) sono gia' presenti come attributi di {@link SubProcess} ma
 *    sono nullable: in questa fase (mock, nessuna chiamata LLM reale) restano
 *    vuoti. Quando il COSMIC Analyzer verra' collegato, valorizzera' questi
 *    campi senza richiedere modifiche allo schema JSON: e' gia' "pronto".
 *
 * 3) Le relazioni Include/Extend sono modellate a livello di UseCase e
 *    referenziano gli id di altri UseCase: questo permette all'ereditarieta'
 *    (un UseCase incluso/esteso eredita/aggiorna il costo COSMIC del padre)
 *    di essere ricalcolata in futuro senza duplicare i dati.
 *
 * AGGIORNAMENTI v2.0 (Ultimate):
 *
 * 4) {@link UseCase#vpElementId} (Epic 4): l'id LOGICO usato dall'LLM per
 *    le cross-reference nel JSON ({@code id}, usato da includesIds/extendsList)
 *    e l'id INTERNO assegnato da Visual Paradigm alla creazione dell'IUseCase
 *    reale sul diagramma sono due cose diverse e vanno tenuti separati:
 *      - {@code id}           -> chiave logica, stabile, generata dall'LLM;
 *        usata SOLO per risolvere le relazioni dentro il JSON.
 *      - {@code vpElementId}  -> IModelElement.getId() del vero oggetto VP,
 *        valorizzato da UseCaseDiagramGenerator DOPO la creazione. E' la
 *        chiave usata da CosmicAiService per la sync bidirezionale (rename,
 *        aggiunta/rimozione), che prima si basava sul nome (fragile: un
 *        rename manuale "rompeva" la corrispondenza).
 *
 * 5) {@link UseCase#subsystem} (Epic 3): raggruppamento logico opzionale
 *    (es. "Prenotazioni", "Fatturazione") suggerito dall'LLM in base al
 *    documento dei requisiti. UseCaseDiagramGenerator lo usa per decidere
 *    se generare un solo diagramma o uno per subsystem.
 *
 * 6) {@link UseCase#diagramId} (Epic 3): l'id del IUseCaseDiagramUIModel su
 *    cui questo Use Case e' stato effettivamente disegnato. Permette al
 *    Service di calcolare i CFP "nello scope" del diagramma attivo in VP,
 *    senza dover indovinare la corrispondenza a runtime.
 *
 * 7) {@link UseCase#violation} (Epic 5): messaggio di violazione COSMIC/
 *    requisiti corrente (null se nessuna). Viene scritto dal Calculator o
 *    dal Service dopo un ricalcolo, e letto da UseCaseDiagramGenerator/
 *    VisualFeedbackApplier per colorare la shape sul diagramma.
 */
public final class CosmicJsonModel {

    public String projectName;
    public List<Actor> actors = new ArrayList<>();
    public List<UseCase> useCases = new ArrayList<>();
    public List<com.cosmic.vpplugin.document.UseCaseChunk> originalChunks = new ArrayList<>();

    // ---------------------------------------------------------------

    public static final class Actor {
        public String id;
        public String name;
        public String description;
        /** v3.0: IModelElement.getId() dell'IActor creato su VP (null finche' non disegnato): serve alla sync dei rename. */
        public String vpElementId;
    }

    public static final class ExtendRelation {
        public String targetId;        // id dello Use Case "base" esteso
        public String extensionPoint;  // punto di estensione (facoltativo)
    }

    public static final class UseCase {
        public String id;
        public String name;
        public String primaryActorId;
        /** v3.0: ulteriori attori associati allo Use Case (es. "Primary actor: Hotel manager, Operator"). */
        public List<String> coActorIds = new ArrayList<>();
        public String specification;         // descrizione libera / Documentation
        public List<String> mainScenario = new ArrayList<>();
        public List<String> exceptions = new ArrayList<>();

        // Relazioni strutturali
        public List<String> includesIds = new ArrayList<>();     // UC che QUESTO UC include
        /** Relazioni <<extend>> in cui QUESTO UC e' l'estensione: la freccia va da questo UC a {@code targetId} (UC base). */
        public List<ExtendRelation> extendsList = new ArrayList<>();

        // Mappatura verso i Processi Funzionali COSMIC (1..N per UseCase)
        public List<FunctionalProcess> functionalProcesses = new ArrayList<>();

        // --- v2.0: raggruppamento multi-diagramma (Epic 3) ---
        /** Raggruppamento logico opzionale (subsystem/modulo); null = "default". */
        public String subsystem;

        // --- v2.0: ID tracking robusto (Epic 4) ---
        /** IModelElement.getId() del vero IUseCase creato su VP; null finche' non generato. */
        public String vpElementId;
        /** IDiagramUIModel.getId() del diagramma su cui questo Use Case vive; null finche' non generato. */
        public String diagramId;

        // --- v2.0: feedback visivo / warning (Epic 5) ---
        /** Messaggio di violazione corrente (regola COSMIC minima o requisito originario), null se nessuna. */
        public String violation;

        // --- v4.0: sorgente di verita' = Visual Paradigm ---
        /**
         * Impronta (nome + testo della specifica, whitespace normalizzato) calcolata al momento dell'ULTIMA misura LLM.
         * "Rimisura da VP" la confronta con l'impronta corrente del campo Specification: se coincide lo use case non e'
         * stato toccato e la sua misura (comprese le correzioni manuali dell'utente) viene conservata.
         */
        public String specFingerprint;
    }

    public static final class FunctionalProcess {
        public String fpId;
        public String fpName;
        public String triggeringEvent;
        public List<SubProcess> subProcesses = new ArrayList<>();
    }

    /**
     * Sotto-processo / step atomico. dataMovementType, dataGroup e
     * objectOfInterest sono i campi che verranno valorizzati dal futuro
     * "COSMIC Analyzer" (si veda il paper allegato, Sez. 3): per ora restano
     * null nel mock, lo schema pero' li prevede gia'.
     */
    public static final class SubProcess {
        public int step;
        public String description;
        public String functionalUser;
        public String dataMovementType; // Entry (E) | Exit (X) | Read (R) | Write (W) | null
        public String dataGroup;        // null finche' non calcolato dal COSMIC Analyzer
        public String objectOfInterest; // null finche' non calcolato dal COSMIC Analyzer

        // --- v4.0 ---
        /** true se la misura LLM di QUESTA frase e' fallita (anche dopo il retry): va corretta a mano o rimisurata. */
        public boolean unmeasured;
        /** true se l'utente ha modificato a mano questa riga (human-in-the-loop). */
        public boolean manuallyEdited;
    }
}
