package com.cosmic.vpplugin.generator;

import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.ExtendRelation;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalArea;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;
import com.cosmic.vpplugin.service.VpModelLookup;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.DiagramManager;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.diagram.IUseCaseDiagramUIModel;
import com.vp.plugin.diagram.LayoutOption;
import com.vp.plugin.diagram.connector.IIncludeUIModel;
import com.vp.plugin.diagram.shape.IActorUIModel;
import com.vp.plugin.diagram.shape.ISystemUIModel;
import com.vp.plugin.diagram.shape.IUseCaseUIModel;
import com.vp.plugin.model.IActor;
import com.vp.plugin.model.IAssociation;
import com.vp.plugin.model.IExtend;
import com.vp.plugin.model.IExtensionPoint;
import com.vp.plugin.model.IInclude;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IPackage;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.ISystem;
import com.vp.plugin.model.IUseCase;
import com.vp.plugin.model.factory.IModelElementFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Traduce il modello COSMIC in Use Case Diagram reali di Visual Paradigm, UNO USE CASE ALLA VOLTA.
 *
 * <h3>Separazione MODEL / VIEW (v5.0, estesa agli attori nella v5.1)</h3>
 * In Visual Paradigm ogni cosa ha due facce:
 * <ul>
 *   <li><b>MODEL</b>: l'elemento nel database del progetto ({@code IUseCase}, {@code IActor}, {@code IInclude},
 *       {@code ISystem}, {@code IPackage}), con un id univoco. Si crea con {@code IModelElementFactory}.</li>
 *   <li><b>VIEW</b>: la forma grafica su UN diagramma ({@code IUseCaseUIModel}, {@code IActorUIModel},
 *       {@code ISystemUIModel}, connettore). Si crea con {@code DiagramManager.createDiagramElement(diagram, model)}
 *       e PUNTA a un elemento model esistente: lo stesso elemento puo' avere piu' viste (la prima e' la "master
 *       view", le altre "auxiliary view").</li>
 * </ul>
 *
 * <h3>Struttura generata per ogni {@link FunctionalArea}</h3>
 * <pre>
 *   Actors                                   IPackage GLOBALE (uno solo, alla radice): gli IActor del progetto
 *   PRE - Booking                            IPackage del sottosistema (se il documento ne ha uno)
 *    └─ F-PRE-04: Commissioned rooms ...     IPackage dell'area
 *        ├─ UCD - F-PRE-04: ...              il diagramma, dentro il package ({@code addSubDiagram})
 *        └─ F-PRE-04: Commissioned rooms     ISystem = System Boundary (nome = area)
 *            └─ Add commissioned room, ...   IUseCase dell'area (figli MODEL del System)
 * </pre>
 * <ul>
 *   <li><b>Use case</b>: un solo {@code IUseCase} per UC nel database. Se {@code UC-A} (area 1) include {@code UC-B}
 *       (area 2) per UC-B NON si chiama {@code createUseCase()}: si crea soltanto una nuova VIEW
 *       ({@code createDiagramElement(diagramArea1, ucBEsistente)}) FUORI dal boundary, poi la freccia.</li>
 *   <li><b>Attori (v5.1)</b>: un solo {@code IActor} per nome nell'intero progetto (confronto senza maiuscole e
 *       spazi multipli), nel package "Actors". Prima di crearlo si cerca per nome tra quelli di questa sessione e
 *       tra quelli gia' presenti nel database VP; se esiste si crea solo la vista {@code IActorUIModel} nel
 *       diagramma corrente. Gli attori restano FUORI dal boundary.</li>
 *   <li><b>System Boundary (v5.1)</b>: un {@code ISystem} per area, figlio del package dell'area, con il nome
 *       dell'area. La sua forma {@code ISystemUIModel} e' il CONTENITORE delle viste degli use case propri
 *       dell'area: {@code systemShape.addChild(useCaseShape)} (containment di UI) in aggiunta a
 *       {@code systemModel.addChild(useCaseModel)} (containment di model, come nell'esempio ufficiale VP).</li>
 *   <li><b>Auto-layout (v5.1)</b>: in {@link #finish()}, a diagramma completo, si lancia il layout di VP
 *       ({@code openAndLayoutDiagram} con {@code LayoutOption}, ripiego su {@code layout(diagram, LAYOUT_*)}) e poi
 *       si ricalcola il rettangolo del boundary sulle forme che contiene e si riportano fuori attori e viste
 *       ausiliarie se il layout li ha messi sopra. Prima del layout le forme sono comunque su una griglia ordinata:
 *       se il layout non e' disponibile il diagramma resta leggibile.</li>
 * </ul>
 * Le viste ausiliarie non toccano {@code UseCase.vpElementId}/{@code diagramId} (restano quelli della master view): la
 * sincronizzazione con VP e i CFP "dello scope del diagramma" non vedono mai uno use case duplicato.
 *
 * <h3>Ciclo di vita incrementale (anti-freeze, invariato dalla v3.0)</h3>
 * <pre>
 *   generator.begin(projectName);                // una volta
 *   generator.appendUseCase(model, uc);          // dopo OGNI use case misurato (pochi elementi, ~ms)
 *   generator.finish();                          // una volta: relazioni residue + auto-layout di ogni diagramma
 * </pre>
 * TUTTI i metodi pubblici vanno invocati sul thread grafico (EDT): l'Open API di VP non e' thread-safe.
 *
 * ID tracking (Epic 4): {@code UseCase.vpElementId}/{@code diagramId}, {@code Actor.vpElementId} (l'id dell'unico
 * IActor, uguale in tutte le aree che lo usano), {@code FunctionalArea.vpPackageId}/{@code vpSystemId}/{@code diagramId}
 * sono valorizzati subito dopo la creazione.
 */
public final class UseCaseDiagramGenerator {

    /** Algoritmo di auto-layout lanciato a fine generazione. */
    public enum LayoutStyle {
        /** A livelli (yFiles "Hierarchic"): attori -> use case -> use case inclusi. Default. */
        HIERARCHICAL,
        /** Ortogonale (yFiles "Orthogonal"). */
        ORTHOGONAL,
        /** Nessun layout automatico: resta la griglia iniziale. */
        NONE
    }

    /** Nome del package globale degli attori. */
    public static final String ACTORS_PACKAGE_NAME = "Actors";

    // ---- griglia iniziale (prima dell'auto-layout) ----
    private static final int ACTOR_X = 40;
    private static final int ACTOR_W = 60;
    private static final int ACTOR_H = 90;
    private static final int ACTOR_Y_STEP = 160;

    private static final int BOUNDARY_X = 200;
    private static final int BOUNDARY_Y = 40;
    private static final int BOUNDARY_PAD = 30;
    /** Fascia alta del boundary riservata al nome dell'area. */
    private static final int BOUNDARY_TITLE = 40;

    private static final int UC_COLS = 3;
    private static final int UC_W = 220;
    private static final int UC_H = 90;
    private static final int UC_COL_STEP = 260;
    private static final int UC_Y_STEP = 130;
    /** Spazio tra il boundary e la colonna delle viste ausiliarie / tra attori e boundary. */
    private static final int OUTSIDE_GAP = 60;

    private final DiagramManager diagramManager = ApplicationManager.instance().getDiagramManager();

    private Consumer<String> logSink = message -> { };
    private LayoutStyle layoutStyle = LayoutStyle.HIERARCHICAL;

    // ---- stato della sessione incrementale ----
    private String projectName;

    /** Package dei sottosistemi (nome -> IPackage). */
    private final Map<String, IPackage> subsystemPackages = new HashMap<>();
    /** Stato per area funzionale (areaId -> package + boundary + diagramma + viste). */
    private final Map<String, AreaState> areas = new LinkedHashMap<>();

    /** MODEL: l'UNICO IUseCase nel database per ogni use case (ucId -> elemento). */
    private final Map<String, IUseCase> useCaseElements = new HashMap<>();
    /** ucId -> id dell'area che lo possiede (dove sta la sua master view). */
    private final Map<String, String> useCaseHomeArea = new HashMap<>();

    /** Package globale "Actors" (creato o riusato al primo attore che serve). */
    private IPackage actorsPackage;
    /** MODEL: l'UNICO IActor per nome (chiave = {@link CosmicJsonModel#actorKey}); comprende quelli gia' nel DB. */
    private final Map<String, IActor> actorElements = new HashMap<>();
    private boolean existingActorsScanned;
    private int actorsCreated;
    private int actorsReused;
    private int actorAuxiliaryViews;
    /** Chiavi di attore che hanno gia' una master view in qualche diagramma di questa sessione. */
    private final Set<String> mastersOfActors = new HashSet<>();

    private final List<PendingRelation> pending = new ArrayList<>();
    /** Relazioni gia' disegnate ("include|from|to"): un solo IInclude/IExtend nel model per coppia. */
    private final Set<String> drawnRelations = new HashSet<>();
    private int auxiliaryViews;

    /** Tutto cio' che il generatore sa di UN'area: package, boundary, diagramma e le viste che ci ha messo. */
    private static final class AreaState {
        final FunctionalArea area;
        final IPackage pkg;
        final ISystem systemModel;
        final ISystemUIModel boundary;
        final IUseCaseDiagramUIModel diagram;
        /** VIEW: ucId -> forma su QUESTO diagramma (master view se lo use case e' dell'area, auxiliary view altrimenti). */
        final Map<String, IUseCaseUIModel> useCaseViews = new HashMap<>();
        /** Le sole viste degli use case PROPRI dell'area: quelle contenute nel boundary. */
        final List<IUseCaseUIModel> ownedViews = new ArrayList<>();
        /** VIEW: chiave attore -> forma su questo diagramma (fuori dal boundary). */
        final Map<String, IActorUIModel> actorViews = new LinkedHashMap<>();
        /** Viste ausiliarie di use case di altre aree (fuori dal boundary). */
        final List<IUseCaseUIModel> auxiliaryViews = new ArrayList<>();

        AreaState(FunctionalArea area, IPackage pkg, ISystem systemModel, ISystemUIModel boundary,
                  IUseCaseDiagramUIModel diagram) {
            this.area = area;
            this.pkg = pkg;
            this.systemModel = systemModel;
            this.boundary = boundary;
            this.diagram = diagram;
        }
    }

    private static final class PendingRelation {
        final boolean include;
        final String fromUcId;
        final String toUcId;
        final String extensionPoint;

        PendingRelation(boolean include, String fromUcId, String toUcId, String extensionPoint) {
            this.include = include;
            this.fromUcId = fromUcId;
            this.toUcId = toUcId;
            this.extensionPoint = extensionPoint;
        }
    }

    /** Collega il generatore al log della UI che ha invocato l'analisi. */
    public void setLogSink(Consumer<String> logSink) {
        this.logSink = (logSink != null) ? logSink : (message -> { });
    }

    /** Sceglie l'algoritmo di auto-layout di fine generazione (default {@link LayoutStyle#HIERARCHICAL}). */
    public void setLayoutStyle(LayoutStyle style) {
        this.layoutStyle = (style != null) ? style : LayoutStyle.HIERARCHICAL;
    }

    // ------------------------------------------------------------------
    // Ciclo di vita incrementale
    // ------------------------------------------------------------------

    /** Avvia una nuova sessione di disegno (package, boundary e diagrammi si creano al primo use case di ciascuna area). */
    public void begin(String projectName) {
        this.projectName = projectName;
        subsystemPackages.clear();
        areas.clear();
        useCaseElements.clear();
        useCaseHomeArea.clear();
        actorsPackage = null;
        actorElements.clear();
        existingActorsScanned = false;
        actorsCreated = 0;
        actorsReused = 0;
        actorAuxiliaryViews = 0;
        mastersOfActors.clear();
        pending.clear();
        drawnRelations.clear();
        auxiliaryViews = 0;
    }

    /**
     * Disegna UN use case nel boundary del diagramma della SUA area (creando package, boundary e diagramma al primo
     * use case dell'area), gli attori che ancora non ci sono (fuori dal boundary), le associazioni e le relazioni di
     * include/extend ora risolvibili (comprese quelle verso use case di altre aree, via vista ausiliaria).
     */
    public void appendUseCase(CosmicJsonModel model, UseCase uc) {
        FunctionalArea area = model.areaOf(uc);
        if (area == null) {
            throw new IllegalStateException("Lo use case [" + uc.id + "] non appartiene ad alcuna area funzionale del modello.");
        }
        AreaState st = ensureArea(area);

        // --- MODEL: l'unico IUseCase, figlio del System Boundary dell'area ---
        IUseCase ucModel = IModelElementFactory.instance().createUseCase();
        ucModel.setName(uc.name);
        ucModel.setDescription(UseCaseDescription.build(uc));
        st.systemModel.addChild(ucModel);

        // --- VIEW: la master view, CONTENUTA nella forma del boundary (containment di UI) ---
        IUseCaseUIModel ucView = (IUseCaseUIModel) diagramManager.createDiagramElement(st.diagram, ucModel);
        if (!st.boundary.addChild((IShapeUIModel) ucView)) {
            logSink.accept("[" + uc.id + "] la forma non e' stata accettata come figlia del System Boundary di '"
                    + area.label() + "'.");
        }
        int index = st.ownedViews.size();
        ucView.setBounds(ownedX(index % UC_COLS), ownedY(index / UC_COLS), UC_W, UC_H);
        st.ownedViews.add(ucView);
        fitBoundaryToGrid(st);

        st.useCaseViews.put(uc.id, ucView);
        useCaseElements.put(uc.id, ucModel);
        useCaseHomeArea.put(uc.id, area.id);
        uc.vpElementId = ucModel.getId();          // Epic 4: ID tracking robusto (l'id del MODEL, uno solo)
        uc.diagramId = st.diagram.getId();         // diagramma master: le viste ausiliarie non lo cambiano

        // --- Attori + associazioni (primario, poi gli altri) ---
        List<String> actorIds = new ArrayList<>();
        if (uc.primaryActorId != null) {
            actorIds.add(uc.primaryActorId);
        }
        for (String co : uc.coActorIds) {
            if (co != null && !actorIds.contains(co)) {
                actorIds.add(co);
            }
        }
        for (String actorId : actorIds) {
            drawActorAssociation(st, model, actorId, ucModel, ucView);
        }

        // --- Relazioni (si disegnano quando esistono entrambi i model) ---
        for (String includedId : uc.includesIds) {
            pending.add(new PendingRelation(true, uc.id, includedId, null));
        }
        for (ExtendRelation ext : uc.extendsList) {
            pending.add(new PendingRelation(false, uc.id, ext.targetId, ext.extensionPoint));
        }
        flushPendingRelations(false);
    }

    /**
     * Chiude la sessione: segnala le relazioni mai risolte, lancia l'auto-layout di OGNI diagramma ormai completo e
     * riassume cio' che e' stato creato.
     */
    public void finish() {
        flushPendingRelations(true);
        int laidOut = 0;
        for (AreaState st : areas.values()) {
            if (layoutDiagram(st)) {
                laidOut++;
            }
        }
        logSink.accept("Generati " + areas.size() + " package/diagrammi con System Boundary (uno per area funzionale), "
                + useCaseElements.size() + " use case nel modello VP; "
                + auxiliaryViews + " viste ausiliarie per gli use case referenziati da altre aree. "
                + "Attori: " + actorsCreated + " creati nel package '" + ACTORS_PACKAGE_NAME + "', "
                + actorsReused + " riusati dal database, " + actorAuxiliaryViews + " viste ausiliarie aggiuntive. "
                + "Auto-layout: " + laidOut + "/" + areas.size() + " diagrammi (" + layoutStyle + ").");
        pending.clear();
    }

    /** Scorciatoia "a lotto": equivale a begin + appendUseCase per ogni use case + finish. */
    public void generate(CosmicJsonModel model) {
        begin(model.projectName);
        for (UseCase uc : model.allUseCases()) {
            appendUseCase(model, uc);
        }
        finish();
    }

    // ------------------------------------------------------------------
    // Package, boundary e diagramma dell'area
    // ------------------------------------------------------------------

    /** Package + System Boundary + diagramma dell'area, creati al primo bisogno (vedi la struttura nel javadoc della classe). */
    private AreaState ensureArea(FunctionalArea area) {
        AreaState st = areas.get(area.id);
        if (st != null) {
            return st;
        }

        IPackage pkg = IModelElementFactory.instance().createPackage();
        pkg.setName(area.label());
        IPackage subsystemPkg = ensureSubsystemPackage(area.subsystem);
        if (subsystemPkg != null) {
            subsystemPkg.addChild(pkg);
        }
        area.vpPackageId = pkg.getId();

        // MODEL del System Boundary: figlio del package dell'area, con il nome dell'area.
        ISystem systemModel = IModelElementFactory.instance().createSystem();
        systemModel.setName(area.label());
        pkg.addChild(systemModel);
        area.vpSystemId = systemModel.getId();

        @SuppressWarnings("deprecation")
        String diagramType = DiagramManager.DIAGRAM_TYPE_USE_CASE_DIAGRAM;
        IUseCaseDiagramUIModel diagram = (IUseCaseDiagramUIModel) diagramManager.createDiagram(diagramType);
        diagram.setName("UCD - " + area.label());
        pkg.addSubDiagram(diagram);                 // il diagramma sta FISICAMENTE dentro il package dell'area
        area.diagramId = diagram.getId();

        // VIEW del System Boundary: creata subito, cosi' sta sotto tutte le altre forme.
        ISystemUIModel boundary = (ISystemUIModel) diagramManager.createDiagramElement(diagram, systemModel);
        boundary.setBounds(BOUNDARY_X, BOUNDARY_Y, boundaryWidth(1), boundaryHeight(1));

        st = new AreaState(area, pkg, systemModel, boundary, diagram);
        areas.put(area.id, st);
        diagramManager.openDiagram(diagram);        // visibile subito: l'utente lo vede crescere
        return st;
    }

    private IPackage ensureSubsystemPackage(String subsystem) {
        if (subsystem == null || subsystem.isBlank()) {
            return null;
        }
        String key = subsystem.trim();
        IPackage pkg = subsystemPackages.get(key);
        if (pkg == null) {
            pkg = IModelElementFactory.instance().createPackage();
            pkg.setName(key);
            subsystemPackages.put(key, pkg);
        }
        return pkg;
    }

    // ------------------------------------------------------------------
    // Geometria iniziale (griglia dentro il boundary)
    // ------------------------------------------------------------------

    private static int ownedX(int col) {
        return BOUNDARY_X + BOUNDARY_PAD + col * UC_COL_STEP;
    }

    private static int ownedY(int row) {
        return BOUNDARY_Y + BOUNDARY_TITLE + row * UC_Y_STEP;
    }

    private static int boundaryWidth(int cols) {
        return (cols - 1) * UC_COL_STEP + UC_W + 2 * BOUNDARY_PAD;
    }

    private static int boundaryHeight(int rows) {
        return (rows - 1) * UC_Y_STEP + UC_H + BOUNDARY_TITLE + BOUNDARY_PAD;
    }

    /** X della colonna delle viste ausiliarie: a destra del boundary alla sua larghezza massima (3 colonne). */
    private static int auxiliaryX() {
        return BOUNDARY_X + boundaryWidth(UC_COLS) + OUTSIDE_GAP;
    }

    /** Ridimensiona il boundary sulla griglia degli use case propri (cresce man mano che se ne aggiungono). */
    private void fitBoundaryToGrid(AreaState st) {
        int owned = Math.max(1, st.ownedViews.size());
        int cols = Math.min(owned, UC_COLS);
        int rows = (owned + UC_COLS - 1) / UC_COLS;
        st.boundary.setBounds(BOUNDARY_X, BOUNDARY_Y, boundaryWidth(cols), boundaryHeight(rows));
    }

    // ------------------------------------------------------------------
    // Attori: MODEL globale (package "Actors"), VIEW per diagramma
    // ------------------------------------------------------------------

    private void drawActorAssociation(AreaState st, CosmicJsonModel model, String actorId,
                                      IUseCase ucModel, IUseCaseUIModel ucView) {
        Actor actorDto = model.findActor(actorId);
        if (actorDto == null) {
            return;
        }
        String key = CosmicJsonModel.actorKey(actorDto.name);

        // MODEL: l'unico IActor per nome (sessione -> database VP -> nuovo nel package "Actors").
        IActor actorModel = actorModelFor(actorDto);
        actorDto.vpElementId = actorModel.getId();     // tutte le aree che lo usano puntano allo stesso id

        // VIEW: la forma su questo diagramma. La prima in assoluto e' la master view; ogni altra e' ausiliaria.
        IActorUIModel actorView = st.actorViews.get(key);
        if (actorView == null) {
            actorView = (IActorUIModel) diagramManager.createDiagramElement(st.diagram, actorModel);
            actorView.setBounds(ACTOR_X, BOUNDARY_Y + BOUNDARY_TITLE + st.actorViews.size() * ACTOR_Y_STEP, ACTOR_W, ACTOR_H);
            st.actorViews.put(key, actorView);               // fuori dal boundary: NON si aggiunge come figlio
            if (!mastersOfActors.add(key)) {
                actorAuxiliaryViews++;
            }
        }

        IAssociation associationModel = IModelElementFactory.instance().createAssociation();
        associationModel.setFrom(actorModel);
        associationModel.setTo(ucModel);
        diagramManager.createConnector(
                st.diagram, associationModel, (IDiagramElement) actorView, (IDiagramElement) ucView, null);
    }

    private IActor actorModelFor(Actor actorDto) {
        String key = CosmicJsonModel.actorKey(actorDto.name);
        scanExistingActors();
        IActor actorModel = actorElements.get(key);
        if (actorModel != null) {
            return actorModel;
        }
        actorModel = IModelElementFactory.instance().createActor();
        actorModel.setName(actorDto.name == null ? "Actor" : actorDto.name.trim());
        actorModel.setDescription(actorDto.description == null ? "" : actorDto.description);
        ensureActorsPackage().addChild(actorModel);
        actorElements.put(key, actorModel);
        actorsCreated++;
        return actorModel;
    }

    /**
     * Una volta per sessione: censisce gli {@code IActor} gia' presenti nel database VP (generazioni precedenti,
     * attori disegnati a mano) per nome, preferendo quelli gia' nel package "Actors". Se l'API di enumerazione non e'
     * disponibile la mappa resta vuota e gli attori si creano (al piu' un duplicato rispetto a prima, mai piu' di uno
     * per nome nella sessione).
     */
    private void scanExistingActors() {
        if (existingActorsScanned) {
            return;
        }
        existingActorsScanned = true;
        IPackage actors = findExistingActorsPackage();
        for (Object o : VpModelLookup.elementsOfType(IModelElementFactory.MODEL_TYPE_ACTOR)) {
            if (!(o instanceof IActor)) {
                continue;
            }
            IActor actor = (IActor) o;
            String key = CosmicJsonModel.actorKey(actor.getName());
            if (key.isEmpty()) {
                continue;
            }
            IActor known = actorElements.get(key);
            boolean inActorsPackage = actors != null && actor.getParent() != null
                    && actors.getId().equals(actor.getParent().getId());
            if (known == null || inActorsPackage) {
                if (known == null) {
                    actorsReused++;
                }
                actorElements.put(key, actor);
            }
        }
        if (actors != null) {
            actorsPackage = actors;
        }
    }

    /** Un package "Actors" gia' presente alla radice del progetto (generazione precedente), altrimenti null. */
    private IPackage findExistingActorsPackage() {
        for (Object o : VpModelLookup.elementsOfType(IModelElementFactory.MODEL_TYPE_PACKAGE)) {
            if (!(o instanceof IPackage)) {
                continue;
            }
            IPackage pkg = (IPackage) o;
            IModelElement parent = pkg.getParent();
            boolean atRoot = parent == null || parent instanceof IProject;
            if (atRoot && pkg.getName() != null && ACTORS_PACKAGE_NAME.equalsIgnoreCase(pkg.getName().trim())) {
                return pkg;
            }
        }
        return null;
    }

    private IPackage ensureActorsPackage() {
        if (actorsPackage == null) {
            actorsPackage = IModelElementFactory.instance().createPackage();   // senza genitore: alla radice del progetto
            actorsPackage.setName(ACTORS_PACKAGE_NAME);
        }
        return actorsPackage;
    }

    // ------------------------------------------------------------------
    // Relazioni <<include>> / <<extend>>
    // ------------------------------------------------------------------

    private void flushPendingRelations(boolean finalPass) {
        Iterator<PendingRelation> it = pending.iterator();
        while (it.hasNext()) {
            PendingRelation rel = it.next();
            IUseCase fromModel = useCaseElements.get(rel.fromUcId);
            IUseCase toModel = useCaseElements.get(rel.toUcId);
            String kind = rel.include ? "<<include>>" : "<<extend>>";

            if (fromModel == null || toModel == null) {
                if (finalPass) {
                    logSink.accept(kind + " NON DISEGNATO: " + rel.fromUcId + " -> " + rel.toUcId
                            + " (use case di destinazione assente dal modello).");
                    it.remove();
                }
                continue; // l'altro estremo potrebbe comparire piu' avanti nel documento
            }
            it.remove();

            String key = (rel.include ? "include|" : "extend|") + rel.fromUcId + "|" + rel.toUcId;
            if (!drawnRelations.add(key)) {
                continue;                                         // gia' nel model: mai un secondo IInclude/IExtend
            }
            // La relazione si disegna sul diagramma dell'area del use case di PARTENZA.
            AreaState st = areas.get(useCaseHomeArea.get(rel.fromUcId));
            if (rel.include) {
                drawInclude(st, rel, fromModel, toModel);
            } else {
                drawExtend(st, rel, fromModel, toModel);
            }
        }
    }

    /**
     * La VIEW di {@code ucId} sul diagramma di {@code st}: la master view se lo use case e' dell'area; altrimenti una
     * VISTA AUSILIARIA creata sull'{@code IUseCase} ESISTENTE, FUORI dal boundary (non e' parte del sistema di
     * quest'area: e' un riferimento). Qui NON si chiama mai {@code createUseCase()}.
     */
    private IUseCaseUIModel viewOf(AreaState st, String ucId) {
        IUseCaseUIModel view = st.useCaseViews.get(ucId);
        if (view != null) {
            return view;
        }
        IUseCase existing = useCaseElements.get(ucId);           // l'elemento model gia' presente nel DB
        if (existing == null) {
            return null;
        }
        view = (IUseCaseUIModel) diagramManager.createDiagramElement(st.diagram, existing);
        int row = st.auxiliaryViews.size();
        view.setBounds(auxiliaryX(), BOUNDARY_Y + BOUNDARY_TITLE + row * UC_Y_STEP, UC_W, UC_H);
        st.useCaseViews.put(ucId, view);
        st.auxiliaryViews.add(view);
        auxiliaryViews++;
        logSink.accept("[" + ucId + "] referenziato nel diagramma '" + st.area.label() + "' con una vista ausiliaria "
                + "(use case dell'area " + useCaseHomeArea.get(ucId) + ", nessun duplicato nel modello).");
        return view;
    }

    private void drawInclude(AreaState st, PendingRelation rel, IUseCase baseModel, IUseCase includedModel) {
        IUseCaseUIModel baseView = viewOf(st, rel.fromUcId);
        IUseCaseUIModel includedView = viewOf(st, rel.toUcId);       // master view o vista ausiliaria
        if (baseView == null || includedView == null) {
            return;
        }
        // Nel modello COSMIC/UML la freccia <<include>> parte dal base use case verso lo use case incluso.
        IInclude includeModel = IModelElementFactory.instance().createInclude();
        includeModel.setFrom(baseModel);
        includeModel.setTo(includedModel);
        Object connector = diagramManager.createConnector(
                st.diagram, includeModel, (IDiagramElement) baseView, (IDiagramElement) includedView, null);
        if (!(connector instanceof IIncludeUIModel)) {
            logSink.accept("<<include>> " + rel.fromUcId + " -> " + rel.toUcId + ": il connettore creato non e' un IIncludeUIModel.");
        }
    }

    private void drawExtend(AreaState st, PendingRelation rel, IUseCase extensionModel, IUseCase baseModel) {
        IUseCaseUIModel extensionView = viewOf(st, rel.fromUcId);
        IUseCaseUIModel baseView = viewOf(st, rel.toUcId);            // master view o vista ausiliaria
        if (extensionView == null || baseView == null) {
            return;
        }
        // La freccia <<extend>> parte dallo use case "estensione" verso lo use case "base".
        IExtend extendModel = IModelElementFactory.instance().createExtend();
        extendModel.setFrom(extensionModel);
        extendModel.setTo(baseModel);
        if (rel.extensionPoint != null && !rel.extensionPoint.isEmpty()) {
            IExtensionPoint extensionPoint = IModelElementFactory.instance().createExtensionPoint();
            extensionPoint.setName(rel.extensionPoint);
            extendModel.setExtensionPoint(extensionPoint);
        }
        diagramManager.createConnector(
                st.diagram, extendModel, (IDiagramElement) extensionView, (IDiagramElement) baseView, null);
    }

    // ------------------------------------------------------------------
    // Auto-layout di fine generazione
    // ------------------------------------------------------------------

    /**
     * Lancia l'auto-layout di VP sul diagramma completo e poi ne sistema il contorno. Ogni passo e' protetto: se
     * l'edizione di VP non espone il layout (o lancia), il diagramma resta sulla griglia iniziale, gia' ordinata.
     *
     * @return true se un layout automatico e' stato eseguito
     */
    private boolean layoutDiagram(AreaState st) {
        boolean done = false;
        if (layoutStyle != LayoutStyle.NONE) {
            done = runVpLayout(st);
        }
        try {
            enforceBoundary(st);
        } catch (RuntimeException e) {
            logSink.accept("Boundary di '" + st.area.label() + "': riposizionamento non riuscito (" + e + ").");
        }
        return done;
    }

    private boolean runVpLayout(AreaState st) {
        try {
            LayoutOption option;
            if (layoutStyle == LayoutStyle.ORTHOGONAL) {
                LayoutOption.Orthogonal orthogonal = diagramManager.createOrthogonalLayoutOption();
                orthogonal.setLayoutGridSize(100);
                option = orthogonal;
            } else {
                LayoutOption.Hierarchical hierarchical = diagramManager.createHierarchicalLayoutOption();
                hierarchical.setMinimumConnectorDistance(10);
                hierarchical.setMinimumLayerDistance(40);
                hierarchical.setMinimumShapeDistance(40);
                option = hierarchical;
            }
            diagramManager.openAndLayoutDiagram(st.diagram, option);
            return true;
        } catch (RuntimeException | LinkageError first) {
            // Ripiego: layout a costante intera (api piu' vecchia o opzioni non disponibili in questa edizione).
            try {
                diagramManager.layout(st.diagram, layoutStyle == LayoutStyle.ORTHOGONAL
                        ? DiagramManager.LAYOUT_ORTHOGONAL : DiagramManager.LAYOUT_HIERARCHIC);
                return true;
            } catch (RuntimeException | LinkageError second) {
                logSink.accept("Auto-layout di '" + st.area.label() + "' non disponibile (" + first + "); "
                        + "resta la griglia iniziale.");
                return false;
            }
        }
    }

    /**
     * Dopo il layout: il rettangolo del boundary deve racchiudere ESATTAMENTE le viste degli use case propri
     * (con margine e fascia del titolo), e attori e viste ausiliarie devono stare fuori. Se il motore di layout ha
     * gia' fatto tutto bene questo passo non cambia nulla di visibile.
     */
    private void enforceBoundary(AreaState st) {
        if (st.ownedViews.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (IUseCaseUIModel v : st.ownedViews) {
            minX = Math.min(minX, v.getX());
            minY = Math.min(minY, v.getY());
            maxX = Math.max(maxX, v.getX() + v.getWidth());
            maxY = Math.max(maxY, v.getY() + v.getHeight());
        }
        int bx = minX - BOUNDARY_PAD;
        int by = minY - BOUNDARY_TITLE;
        int bw = (maxX - minX) + 2 * BOUNDARY_PAD;
        int bh = (maxY - minY) + BOUNDARY_TITLE + BOUNDARY_PAD;
        st.boundary.setBounds(bx, by, bw, bh);

        // Attori: se toccano il boundary vanno a sinistra; viste ausiliarie: a destra.
        for (IActorUIModel a : st.actorViews.values()) {
            if (overlaps(a, bx, by, bw, bh)) {
                a.setBounds(bx - OUTSIDE_GAP - a.getWidth(), a.getY(), a.getWidth(), a.getHeight());
            }
        }
        for (IUseCaseUIModel a : st.auxiliaryViews) {
            if (overlaps(a, bx, by, bw, bh)) {
                a.setBounds(bx + bw + OUTSIDE_GAP, a.getY(), a.getWidth(), a.getHeight());
            }
        }
    }

    private static boolean overlaps(IDiagramElement e, int x, int y, int w, int h) {
        return e.getX() < x + w && e.getX() + e.getWidth() > x
                && e.getY() < y + h && e.getY() + e.getHeight() > y;
    }
}
