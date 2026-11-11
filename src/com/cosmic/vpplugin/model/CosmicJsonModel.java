package com.cosmic.vpplugin.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Modello di dominio del plugin: la radice e' il PROGETTO (questa classe), che contiene le
 * {@link FunctionalArea}; ogni area contiene i propri {@link UseCase} e i propri {@link Actor}.
 *
 * <pre>
 *   CosmicJsonModel (Project)
 *    └─ FunctionalArea  "F-PRE-04: Commissioned rooms management"   (sottosistema "PRE - Booking")
 *        ├─ Actor        "Hotel manager"                            (attori propri dell'area)
 *        └─ UseCase      "UC-PRE-04.5 Add commissioned room"
 *            └─ FunctionalProcess -> SubProcess (data movement E/X/R/W)
 * </pre>
 *
 * <h3>v5.0 - Gerarchia rigida (rifattorizzazione richiesta dalla revisione del professore)</h3>
 * Prima il modello era piatto: {@code List<Actor> actors} e {@code List<UseCase> useCases} direttamente sulla
 * radice, e il raggruppamento era solo una stringa opzionale ({@code UseCase.subsystem}). Ora l'appartenenza e'
 * STRUTTURALE: uno use case esiste soltanto dentro la {@code FunctionalArea} che lo contiene, e la stessa cosa vale
 * per gli attori (un attore che opera in due aree e' due {@link Actor} distinti, uno per area, ciascuno con id
 * univoco nel progetto). Le liste interne sono private: si modificano solo tramite i metodi di
 * {@link FunctionalArea} / {@link CosmicJsonModel}, che mantengono coerente il riferimento {@code areaId}.
 *
 * <h3>Navigazione</h3>
 * Gli id degli use case e degli attori sono univoci in TUTTO il progetto (non solo nell'area): le relazioni
 * ({@code includesIds}, {@code extendsList}, {@code primaryActorId}) restano semplici id e possono attraversare le
 * aree (es. {@code UC-SOG-01.2} include {@code UC-GES-03.4.1}). {@link #findUseCase}, {@link #allUseCases()} e
 * {@link #allActors()} sono VISTE derivate dalla gerarchia, non liste memorizzate.
 *
 * <h3>Punti di design ereditati dalle versioni precedenti</h3>
 * <ul>
 *   <li>Uno {@link UseCase} NON coincide 1:1 con un Processo Funzionale COSMIC (paper CosMet): contiene una lista
 *       di {@link FunctionalProcess}.</li>
 *   <li>{@link UseCase#id} e' la chiave LOGICA (dal documento); {@link UseCase#vpElementId} e' l'id dell'elemento
 *       {@code IUseCase} su Visual Paradigm (uno solo nel DB, anche se lo use case e' mostrato su piu' diagrammi).</li>
 *   <li>{@link UseCase#diagramId} e' il diagramma "master" dello use case (quello della sua area): le viste ausiliarie
 *       create per gli {@code <<include>>} cross-area NON lo modificano, cosi' i CFP "nello scope del diagramma" non
 *       vengono contati due volte.</li>
 * </ul>
 */
public final class CosmicJsonModel {

    /** Id/nome dell'area che accoglie gli use case senza area funzionale (documenti piatti, use case creati a mano). */
    public static final String DEFAULT_AREA_ID = "GENERAL";
    public static final String DEFAULT_AREA_NAME = "General (no functional area)";

    public String projectName;

    /**
     * true se questo modello e' stato letto da un file di persistenza precedente alla v5.0 (schema 1-2, liste piatte)
     * e raggruppato in aree in base al sottosistema. Non viene salvato: serve solo a segnalarlo all'utente.
     */
    public boolean migratedFromFlatSchema;

    private final List<FunctionalArea> areas = new ArrayList<>();

    // ======================================================================
    // Aree
    // ======================================================================

    /** Le aree funzionali del progetto, nell'ordine di apparizione nel documento (vista non modificabile). */
    public List<FunctionalArea> functionalAreas() {
        return Collections.unmodifiableList(areas);
    }

    public FunctionalArea findArea(String areaId) {
        if (areaId == null) return null;
        for (FunctionalArea a : areas) if (areaId.equals(a.id)) return a;
        return null;
    }

    /** L'area il cui diagramma su VP ha questo id (null se nessuna). */
    public FunctionalArea findAreaByDiagramId(String diagramId) {
        if (diagramId == null) return null;
        for (FunctionalArea a : areas) if (diagramId.equals(a.diagramId)) return a;
        return null;
    }

    /** L'area il cui package su VP ha questo id (null se nessuna). */
    public FunctionalArea findAreaByPackageId(String vpPackageId) {
        if (vpPackageId == null) return null;
        for (FunctionalArea a : areas) if (vpPackageId.equals(a.vpPackageId)) return a;
        return null;
    }

    /**
     * Restituisce l'area identificata da {@code ref} (es. "F-PRE-04"), oppure da {@code subsystem + nome} se il
     * documento non da' un codice; la crea se non esiste. Con ref e nome entrambi vuoti: l'area di default.
     */
    public FunctionalArea findOrCreateArea(String ref, String name, String subsystem) {
        String id = FunctionalArea.keyOf(ref, name, subsystem);
        FunctionalArea existing = findArea(id);
        if (existing != null) return existing;
        FunctionalArea a = new FunctionalArea();
        a.id = id;
        boolean unclassified = DEFAULT_AREA_ID.equals(id);
        a.name = unclassified ? DEFAULT_AREA_NAME : (blank(name) ? id : name.trim());
        a.subsystem = unclassified || blank(subsystem) ? null : subsystem.trim();
        areas.add(a);
        return a;
    }

    public FunctionalArea defaultArea() {
        return findOrCreateArea(null, null, null);
    }

    /** Area che contiene {@code uc} (null se lo use case non e' nel modello). */
    public FunctionalArea areaOf(UseCase uc) {
        if (uc == null) return null;
        for (FunctionalArea a : areas) if (a.useCases.contains(uc)) return a;
        return null;
    }

    /** Area che contiene {@code actor} (null se l'attore non e' nel modello). */
    public FunctionalArea areaOf(Actor actor) {
        if (actor == null) return null;
        for (FunctionalArea a : areas) if (a.actors.contains(actor)) return a;
        return null;
    }

    // ======================================================================
    // Viste piatte DERIVATE (nessuna lista piatta e' memorizzata)
    // ======================================================================

    /** Tutti gli use case, area per area (copia: si puo' iterare mentre si modifica il modello). */
    public List<UseCase> allUseCases() {
        List<UseCase> out = new ArrayList<>();
        for (FunctionalArea a : areas) out.addAll(a.useCases);
        return out;
    }

    public List<Actor> allActors() {
        List<Actor> out = new ArrayList<>();
        for (FunctionalArea a : areas) out.addAll(a.actors);
        return out;
    }

    public int useCaseCount() {
        int n = 0;
        for (FunctionalArea a : areas) n += a.useCases.size();
        return n;
    }

    public boolean hasUseCases() {
        for (FunctionalArea a : areas) if (!a.useCases.isEmpty()) return true;
        return false;
    }

    public UseCase findUseCase(String id) {
        if (id == null) return null;
        for (FunctionalArea a : areas) for (UseCase uc : a.useCases) if (id.equals(uc.id)) return uc;
        return null;
    }

    public UseCase findUseCaseByVpId(String vpElementId) {
        if (vpElementId == null) return null;
        for (FunctionalArea a : areas) for (UseCase uc : a.useCases) if (vpElementId.equals(uc.vpElementId)) return uc;
        return null;
    }

    public Actor findActor(String id) {
        if (id == null) return null;
        for (FunctionalArea a : areas) for (Actor x : a.actors) if (id.equals(x.id)) return x;
        return null;
    }

    public Actor findActorByVpId(String vpElementId) {
        if (vpElementId == null) return null;
        for (FunctionalArea a : areas) for (Actor x : a.actors) if (vpElementId.equals(x.vpElementId)) return x;
        return null;
    }

    // ======================================================================
    // Modifiche strutturali con pulizia delle relazioni
    // ======================================================================

    /** Id attore libero, univoco in tutto il progetto ("A1", "A2", ...). */
    public String nextActorId() {
        int max = 0;
        for (Actor a : allActors()) {
            if (a.id != null && a.id.matches("A\\d+")) max = Math.max(max, Integer.parseInt(a.id.substring(1)));
        }
        return "A" + (max + 1);
    }

    /**
     * Rimuove lo use case dalla sua area e ripulisce da ogni altro use case (di QUALSIASI area) gli
     * {@code <<include>>}/{@code <<extend>>} che lo puntavano.
     */
    public boolean removeUseCase(UseCase uc) {
        FunctionalArea owner = areaOf(uc);
        if (owner == null || !owner.useCases.remove(uc)) return false;
        uc.areaId = null;
        for (UseCase other : allUseCases()) {
            other.includesIds.remove(uc.id);
            other.extendsList.removeIf(e -> uc.id.equals(e.targetId));
        }
        return true;
    }

    /** Rimuove da tutte le aree gli attori che soddisfano il predicato; ritorna true se ne ha tolto almeno uno. */
    public boolean removeActorsIf(java.util.function.Predicate<Actor> filter) {
        boolean changed = false;
        for (FunctionalArea a : areas) changed |= a.actors.removeIf(filter);
        return changed;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ======================================================================
    // FunctionalArea
    // ======================================================================

    /**
     * Un'area funzionale del documento (es. "F-PRE-04: Commissioned rooms management"): e' l'unita' di
     * raggruppamento per package VP, diagramma, attori e totali CFP.
     */
    public static final class FunctionalArea {
        /** Codice dell'area nel documento ("F-PRE-04", "F-PRE-07, 05, 06"); se manca, "sottosistema/nome". Univoco nel progetto. */
        public String id;
        /** Nome senza codice ("Commissioned rooms management"). */
        public String name;
        /** Sottosistema che contiene l'area ("PRE - Booking"); null se il documento non ne ha. */
        public String subsystem;
        /** IModelElement.getId() dell'{@code IPackage} creato su VP per quest'area (null finche' non generato). */
        public String vpPackageId;
        /** IDiagramUIModel.getId() del diagramma use case dell'area (null finche' non generato). */
        public String diagramId;

        private final List<Actor> actors = new ArrayList<>();
        private final List<UseCase> useCases = new ArrayList<>();

        /** Chiave stabile dell'area: il codice del documento, altrimenti "sottosistema/nome", altrimenti l'area di default. */
        public static String keyOf(String ref, String name, String subsystem) {
            if (!blank(ref)) return ref.trim();
            if (blank(name)) return DEFAULT_AREA_ID;
            return (blank(subsystem) ? "" : subsystem.trim() + "/") + name.trim();
        }

        /** Etichetta per package, diagramma, albero e CSV: "F-PRE-04: Commissioned rooms management". */
        public String label() {
            if (DEFAULT_AREA_ID.equals(id)) return name;
            if (name == null || name.isEmpty() || name.equals(id)) return id;
            return id.contains("/") ? name : id + ": " + name;
        }

        public List<UseCase> useCases() { return Collections.unmodifiableList(useCases); }
        public List<Actor> actors() { return Collections.unmodifiableList(actors); }

        /** Aggiunge lo use case all'area e ne imposta {@code areaId}. */
        public void addUseCase(UseCase uc) {
            uc.areaId = id;
            useCases.add(uc);
        }

        /** Aggiunge l'attore all'area e ne imposta {@code areaId}. */
        public void addActor(Actor a) {
            a.areaId = id;
            actors.add(a);
        }

        public Actor findActor(String actorId) {
            if (actorId == null) return null;
            for (Actor a : actors) if (actorId.equals(a.id)) return a;
            return null;
        }

        /** Attore dell'area con questo nome (confronto senza maiuscole/minuscole), null se non c'e'. */
        public Actor findActorByName(String name) {
            if (name == null) return null;
            String key = name.trim().toLowerCase();
            for (Actor a : actors) if (a.name != null && a.name.trim().toLowerCase().equals(key)) return a;
            return null;
        }
    }

    // ======================================================================
    // Elementi dell'area
    // ======================================================================

    public static final class Actor {
        /** Univoco in tutto il progetto (non solo nell'area). */
        public String id;
        public String name;
        public String description;
        /** IModelElement.getId() dell'IActor creato su VP nel package dell'area (null finche' non disegnato). */
        public String vpElementId;
        /** Area che contiene l'attore (mantenuto da {@link FunctionalArea#addActor}); non serializzato. */
        public String areaId;
    }

    public static final class ExtendRelation {
        public String targetId;        // id dello Use Case "base" esteso (puo' stare in un'altra area)
        public String extensionPoint;  // punto di estensione (facoltativo)
    }

    public static final class UseCase {
        /** Univoco in tutto il progetto. */
        public String id;
        public String name;
        /** Id di un attore della STESSA area dello use case. */
        public String primaryActorId;
        /** Ulteriori attori (stessa area): es. "Primary actor: Hotel manager, Operator". */
        public List<String> coActorIds = new ArrayList<>();
        public String specification;         // descrizione libera / Documentation
        public List<String> mainScenario = new ArrayList<>();
        public List<String> exceptions = new ArrayList<>();

        // Relazioni strutturali (id di use case di QUALSIASI area)
        public List<String> includesIds = new ArrayList<>();     // UC che QUESTO UC include
        /** Relazioni <<extend>> in cui QUESTO UC e' l'estensione: la freccia va da questo UC a {@code targetId} (UC base). */
        public List<ExtendRelation> extendsList = new ArrayList<>();

        // Mappatura verso i Processi Funzionali COSMIC (1..N per UseCase)
        public List<FunctionalProcess> functionalProcesses = new ArrayList<>();

        /** Sottosistema del documento (informativo: il raggruppamento vero e' l'area che contiene lo use case). */
        public String subsystem;

        /** IModelElement.getId() dell'UNICO IUseCase su VP; null finche' non generato. */
        public String vpElementId;
        /** Id del diagramma "master" (quello dell'area); le viste ausiliarie cross-area non lo cambiano. */
        public String diagramId;

        /** Messaggio di violazione corrente (regola COSMIC minima o requisito originario), null se nessuna. */
        public String violation;

        /**
         * Impronta (nome + specifica) calcolata all'ultima misura LLM: "Rimisura da VP" la confronta con quella
         * corrente per capire se lo use case e' stato toccato.
         */
        public String specFingerprint;

        /** Area che contiene lo use case (mantenuto da {@link FunctionalArea#addUseCase}); non serializzato. */
        public String areaId;
    }

    public static final class FunctionalProcess {
        public String fpId;
        public String fpName;
        public String triggeringEvent;
        public List<SubProcess> subProcesses = new ArrayList<>();
    }

    /**
     * Sotto-processo / step atomico con il suo data movement COSMIC (Entry/Exit/Read/Write).
     */
    public static final class SubProcess {
        public int step;
        public String description;
        public String functionalUser;
        public String dataMovementType; // Entry (E) | Exit (X) | Read (R) | Write (W) | null
        public String dataGroup;
        public String objectOfInterest;

        /** true se la misura LLM di QUESTA frase e' fallita (anche dopo il retry). */
        public boolean unmeasured;
        /** true se l'utente ha modificato a mano questa riga (human-in-the-loop). */
        public boolean manuallyEdited;
    }
}
