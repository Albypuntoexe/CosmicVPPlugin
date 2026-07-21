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
 */
public final class CosmicJsonModel {

    public String projectName;
    public List<Actor> actors = new ArrayList<>();
    public List<UseCase> useCases = new ArrayList<>();

    // ---------------------------------------------------------------

    public static final class Actor {
        public String id;
        public String name;
        public String description;
    }

    public static final class ExtendRelation {
        public String targetId;        // id dello Use Case "base" esteso
        public String extensionPoint;  // punto di estensione (facoltativo)
    }

    public static final class UseCase {
        public String id;
        public String name;
        public String primaryActorId;
        public String specification;         // descrizione libera / Documentation
        public List<String> mainScenario = new ArrayList<>();
        public List<String> exceptions = new ArrayList<>();

        // Relazioni strutturali
        public List<String> includesIds = new ArrayList<>();     // UC che QUESTO UC include
        public List<ExtendRelation> extendsList = new ArrayList<>(); // UC che ESTENDONO questo UC -> target = questo UC

        // Mappatura verso i Processi Funzionali COSMIC (1..N per UseCase)
        public List<FunctionalProcess> functionalProcesses = new ArrayList<>();
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
    }
}
