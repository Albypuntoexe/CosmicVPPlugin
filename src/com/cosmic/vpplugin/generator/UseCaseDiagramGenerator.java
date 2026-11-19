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
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.diagram.IUseCaseDiagramUIModel;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
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
 * <h3>Separazione MODEL / VIEW (v5.0–v5.4)</h3>
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
 * <h3>Correzioni di rendering v5.4 (solo grafica/layout, nessuna modifica alla logica COSMIC)</h3>
 * <ul>
 *   <li><b>System Boundary con colore blu di default di VP</b>: nessun override del colore di riempimento.</li>
 *   <li><b>Fix visibilita' degli UC (es. F-SOG-07 Checkout)</b>: il diagramma viene aperto solo dopo aver inserito
 *       il primo use case nel boundary, lo Z-order garantisce il boundary in fondo e gli use case in primo piano,
 *       e a fine generazione il focus torna sul primo diagramma per forzare il flush grafico dell'ultimo tab.</li>
 *   <li><b>Caption di Attori e relazioni {@code <<include>>}/{@code <<extend>>}</b>: invoca {@code resetCaption()}
 *       e abilita {@code ICaptionUIModel} cosi' i nomi sotto gli omini e sulle frecce sono sempre visibili.</li>
 *   <li><b>Auto-layout nativo senza rotture post-layout</b>: usa {@code openAndLayoutDiagram(option)} via reflection
 *       (compatibile con tutte le edizioni VP, zero errori di compilazione su {@code LayoutOption}) e non sovrascrive
 *       il boundary con {@code enforceBoundary} quando il layout di VP e' gia' andato a buon fine.</li>
 * </ul>
 *
 * TUTTI i metodi pubblici vanno invocati sul thread grafico (EDT): l'Open API di VP non e' thread-safe.
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

    // ---- griglia iniziale (usata durante il disegno incrementale e come ripiego se il layout non e' disponibile) ----
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
        boolean opened;
        /** VIEW: ucId -> forma su QUESTO diagramma (master view se lo use case e' dell'area, auxiliary view altrimenti). */
        final Map<String, IUseCaseUIModel> useCaseViews = new HashMap<>();
        /** Le sole viste degli use case PROPRI dell'area: quelle contenute nel boundary. */
        final List<IUseCaseUIModel> ownedViews = new ArrayList<>();
        /** VIEW: chiave attore -> forma su questo diagramma (fuori dal boundary). */
        final Map<String, IActorUIModel> actorViews = new LinkedHashMap<>();
        /** Viste ausiliarie di use case di altre aree (fuori dal boundary). */
        final List<IUseCaseUIModel> auxiliaryViews = new ArrayList<>();
        /** Connettori <<include>> e <<extend>> su questo diagramma (per ripristinare le etichette dopo il layout). */
        final List<IDiagramElement> relationConnectors = new ArrayList<>();

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

        // Apriamo il diagramma SOLO dopo aver inserito il primo use case e i suoi attori: evita il glitch
        // di rendering del boundary vuoto / figlio nascosto sul tab attivo.
        if (!st.opened) {
            st.opened = true;
            diagramManager.openDiagram(st.diagram);
            for (IActorUIModel a : st.actorViews.values()) {
                refreshCaption(a);
            }
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
     * Chiude la sessione: segnala le relazioni mai risolte, applica auto-layout, Z-order dei figli e caption
     * su OGNI diagramma ormai completo, e riporta il focus sul primo diagramma.
     */
    public void finish() {
        flushPendingRelations(true);
        int laidOut = 0;
        String lastLayoutMode = "";
        IUseCaseDiagramUIModel firstDiagram = null;
        for (AreaState st : areas.values()) {
            if (firstDiagram == null) {
                firstDiagram = st.diagram;
            }
            String mode = layoutAndPolishDiagram(st);
            if (mode != null) {
                laidOut++;
                lastLayoutMode = mode;
            }
        }
        // Riporta il focus sul primo diagramma: forza il flush grafico dell'ultimo tab (es. F-SOG-07)
        // e lascia l'utente sulla prima area funzionale del progetto.
        if (firstDiagram != null && areas.size() > 1) {
            try {
                diagramManager.openDiagram(firstDiagram);
            } catch (RuntimeException ignored) { }
        }
        logSink.accept("Generati " + areas.size() + " package/diagrammi con System Boundary (uno per area funzionale), "
                + useCaseElements.size() + " use case nel modello VP; "
                + auxiliaryViews + " viste ausiliarie per gli use case referenziati da altre aree. "
                + "Attori: " + actorsCreated + " creati nel package '" + ACTORS_PACKAGE_NAME + "', "
                + actorsReused + " riusati dal database, " + actorAuxiliaryViews + " viste ausiliarie aggiuntive. "
                + "Auto-layout: " + laidOut + "/" + areas.size() + " diagrammi (" + layoutStyle
                + (lastLayoutMode.isEmpty() ? "" : ", " + lastLayoutMode) + ").");
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

    /** Package + System Boundary + diagramma dell'area, creati al primo bisogno. */
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

        // VIEW del System Boundary: mantiene il colore blu di default di Visual Paradigm.
        ISystemUIModel boundary = (ISystemUIModel) diagramManager.createDiagramElement(diagram, systemModel);
        boundary.setBounds(BOUNDARY_X, BOUNDARY_Y, boundaryWidth(1), boundaryHeight(1));

        st = new AreaState(area, pkg, systemModel, boundary, diagram);
        areas.put(area.id, st);
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

    /** Ridimensiona il boundary sulla griglia degli use case propri (mantiene l'origine fissa per non spostare i figli). */
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
            refreshCaption(actorView);                      // rende visibile il nome sotto l'omino
            st.actorViews.put(key, actorView);              // fuori dal boundary: NON si aggiunge come figlio
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
     * attori disegnati a mano) per nome, preferendo quelli gia' nel package "Actors".
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
        if (connector instanceof IDiagramElement) {
            IDiagramElement connEl = (IDiagramElement) connector;
            st.relationConnectors.add(connEl);
            refreshCaption(connEl);
        }
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
        Object connector = diagramManager.createConnector(
                st.diagram, extendModel, (IDiagramElement) extensionView, (IDiagramElement) baseView, null);
        if (connector instanceof IDiagramElement) {
            IDiagramElement connEl = (IDiagramElement) connector;
            st.relationConnectors.add(connEl);
            refreshCaption(connEl);
        }
    }

    // ------------------------------------------------------------------
    // Auto-layout, Z-order e caption di fine generazione
    // ------------------------------------------------------------------

    /**
     * Apre il diagramma, esegue l'auto-layout di VP (senza sovrascrivere il boundary se il layout riesce),
     * garantisce lo Z-order dei figli dentro il boundary blu e rigenera le caption di attori e relazioni.
     *
     * @return nome del meccanismo di layout eseguito, oppure null se nessun layout automatico e' stato eseguito
     */
    private String layoutAndPolishDiagram(AreaState st) {
        try {
            diagramManager.openDiagram(st.diagram);
        } catch (RuntimeException ignored) { }

        String mode = null;
        if (layoutStyle != LayoutStyle.NONE) {
            mode = runVpLayoutOn(diagramManager, st.diagram, layoutStyle, st.area.label(), logSink);
        }

        // Se il layout di VP e' andato a buon fine, VP ha gia' dimensionato il contenitore ISystemUIModel
        // attorno ai figli e instradato i connettori. enforceBoundary resta solo come ripiego di sicurezza.
        if (mode == null || hasBrokenGeometry(st)) {
            try {
                enforceBoundary(st);
            } catch (RuntimeException e) {
                logSink.accept("Boundary di '" + st.area.label() + "': riposizionamento non riuscito (" + e + ").");
            }
        }

        // Z-order esplicito: boundary blu sempre sullo sfondo, ovali UC sempre davanti
        sendToBack(st.diagram, st.boundary);
        for (IUseCaseUIModel ucView : st.ownedViews) {
            bringToFront(st.diagram, ucView);
        }

        // Ripristina le etichette di attori e frecce <<include>>/<<extend>> dopo il posizionamento finale
        for (IActorUIModel actorView : st.actorViews.values()) {
            refreshCaption(actorView);
        }
        for (IDiagramElement conn : st.relationConnectors) {
            refreshCaption(conn);
        }
        return mode;
    }

    /**
     * Lancia l'auto-layout di Visual Paradigm nel modo piu' compatibile con l'edizione corrente (zero dipendenze
     * a compile-time da classi interne di {@code LayoutOption}).
     */
    private static String runVpLayoutOn(DiagramManager dm, IUseCaseDiagramUIModel diagram,
                                        LayoutStyle style, String label, Consumer<String> log) {
        // 1) openAndLayoutDiagram(diagram, option) via reflection
        try {
            Object option = createLayoutOptionReflective(dm, style);
            if (option != null && invokePublic(dm, "openAndLayoutDiagram", diagram, option) != InvocationResult.NOT_FOUND) {
                return "openAndLayoutDiagram(option)";
            }
        } catch (RuntimeException | LinkageError ignored) {
            // prosegue con i ripieghi
        }

        // 2) openAndLayoutDiagram(diagram) a 1 parametro
        try {
            if (invokePublic(dm, "openAndLayoutDiagram", diagram) != InvocationResult.NOT_FOUND) {
                return "openAndLayoutDiagram";
            }
        } catch (RuntimeException | LinkageError ignored) {
            // prosegue con il ripiego base
        }

        // 3) layout(diagram, int) - garantito a compile-time su tutte le edizioni VP
        try {
            dm.layout(diagram, style == LayoutStyle.ORTHOGONAL
                    ? DiagramManager.LAYOUT_ORTHOGONAL : DiagramManager.LAYOUT_HIERARCHIC);
            return "layout(int)";
        } catch (RuntimeException | LinkageError e) {
            log.accept("Auto-layout di '" + label + "' non disponibile (" + e + "); resta la griglia iniziale.");
            return null;
        }
    }

    private static Object createLayoutOptionReflective(DiagramManager dm, LayoutStyle style) {
        if (style == LayoutStyle.ORTHOGONAL) {
            Object opt = invokeValue(dm, "createOrthogonalLayoutOption");
            if (opt != null) {
                invokePublic(opt, "setLayoutGridSize", 80);
            }
            return opt;
        }
        Object opt = invokeValue(dm, "createHierarchicalLayoutOption");
        if (opt == null) {
            opt = invokeValue(dm, "createHierarchicLayoutOption");
        }
        if (opt != null) {
            invokePublic(opt, "setMinimumConnectorDistance", 20);
            invokePublic(opt, "setMinimumLayerDistance", 50);
            invokePublic(opt, "setMinimumShapeDistance", 40);
        }
        return opt;
    }

    private static boolean hasBrokenGeometry(AreaState st) {
        if (st.ownedViews.isEmpty()) {
            return false;
        }
        int bx = st.boundary.getX();
        int by = st.boundary.getY();
        int bw = st.boundary.getWidth();
        int bh = st.boundary.getHeight();
        if (bw <= 0 || bh <= 0) {
            return true;
        }
        for (IUseCaseUIModel v : st.ownedViews) {
            if (v.getX() < bx || v.getY() < by
                    || v.getX() + v.getWidth() > bx + bw
                    || v.getY() + v.getHeight() > by + bh) {
                return true;
            }
        }
        for (IActorUIModel a : st.actorViews.values()) {
            if (overlaps(a, bx, by, bw, bh)) {
                return true;
            }
        }
        for (IUseCaseUIModel a : st.auxiliaryViews) {
            if (overlaps(a, bx, by, bw, bh)) {
                return true;
            }
        }
        return false;
    }

    private void enforceBoundary(AreaState st) {
        if (st.ownedViews.isEmpty()) {
            return;
        }
        int n = st.ownedViews.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] ws = new int[n];
        int[] hs = new int[n];
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            IUseCaseUIModel v = st.ownedViews.get(i);
            xs[i] = v.getX();
            ys[i] = v.getY();
            ws[i] = v.getWidth();
            hs[i] = v.getHeight();
            minX = Math.min(minX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxX = Math.max(maxX, xs[i] + ws[i]);
            maxY = Math.max(maxY, ys[i] + hs[i]);
        }
        int bx = minX - BOUNDARY_PAD;
        int by = minY - BOUNDARY_TITLE;
        int bw = (maxX - minX) + 2 * BOUNDARY_PAD;
        int bh = (maxY - minY) + BOUNDARY_TITLE + BOUNDARY_PAD;
        st.boundary.setBounds(bx, by, bw, bh);

        for (int i = 0; i < n; i++) {
            IUseCaseUIModel v = st.ownedViews.get(i);
            if (v.getX() != xs[i] || v.getY() != ys[i]) {
                v.setBounds(xs[i], ys[i], ws[i], hs[i]);
            }
        }

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

    // ------------------------------------------------------------------
    // Helper di caption, Z-order e reflection sicura su VP
    // ------------------------------------------------------------------

    private static void refreshCaption(IDiagramElement element) {
        if (element == null) {
            return;
        }
        try {
            invokePublic(element, "resetCaption");
            invokePublic(element, "setShowCaption", Boolean.TRUE);
            invokePublic(element, "setCaptionVisible", Boolean.TRUE);
            Object caption = invokeValue(element, "getCaptionUIModel");
            if (caption != null) {
                invokePublic(caption, "setVisible", Boolean.TRUE);
                invokePublic(caption, "fitSize");
            }
        } catch (RuntimeException ignored) { }
    }

    private static void sendToBack(IDiagramUIModel diagram, IDiagramElement element) {
        try {
            if (invokePublic(diagram, "moveToBack", element) == InvocationResult.NOT_FOUND) {
                invokePublic(element, "toBack");
            }
        } catch (RuntimeException ignored) { }
    }

    private static void bringToFront(IDiagramUIModel diagram, IDiagramElement element) {
        try {
            if (invokePublic(diagram, "moveToFront", element) == InvocationResult.NOT_FOUND) {
                invokePublic(element, "toFront");
            }
        } catch (RuntimeException ignored) { }
    }

    private enum InvocationResult { NOT_FOUND, INVOKED_NULL }

    private static Object invokePublic(Object target, String methodName, Object... args) {
        if (target == null) {
            return InvocationResult.NOT_FOUND;
        }
        Method m = findCompatiblePublicMethod(target.getClass(), methodName, args);
        if (m == null) {
            return InvocationResult.NOT_FOUND;
        }
        try {
            Object res = m.invoke(target, args);
            return res == null ? InvocationResult.INVOKED_NULL : res;
        } catch (IllegalAccessException | InvocationTargetException e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof LinkageError) {
                throw (LinkageError) cause;
            }
            return InvocationResult.NOT_FOUND;
        }
    }

    private static Object invokeValue(Object target, String methodName, Object... args) {
        Object res = invokePublic(target, methodName, args);
        return (res instanceof InvocationResult) ? null : res;
    }

    private static Method findCompatiblePublicMethod(Class<?> start, String name, Object[] args) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Class<?> k = queue.poll();
            if (k == null || !seen.add(k)) {
                continue;
            }
            if (Modifier.isPublic(k.getModifiers())) {
                for (Method m : k.getMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != args.length) {
                        continue;
                    }
                    if (!Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
                        continue;
                    }
                    if (paramsMatch(m.getParameterTypes(), args)) {
                        return m;
                    }
                }
            }
            if (k.getSuperclass() != null) {
                queue.add(k.getSuperclass());
            }
            queue.addAll(Arrays.asList(k.getInterfaces()));
        }
        return null;
    }

    private static boolean paramsMatch(Class<?>[] paramTypes, Object[] args) {
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> p = paramTypes[i];
            Object a = args[i];
            if (a == null) {
                if (p.isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> ac = a.getClass();
            if (p.isAssignableFrom(ac)) {
                continue;
            }
            if (p == int.class && ac == Integer.class) continue;
            if (p == boolean.class && ac == Boolean.class) continue;
            if (p == double.class && ac == Double.class) continue;
            if (p == float.class && ac == Float.class) continue;
            if (p == long.class && ac == Long.class) continue;
            return false;
        }
        return true;
    }
}
