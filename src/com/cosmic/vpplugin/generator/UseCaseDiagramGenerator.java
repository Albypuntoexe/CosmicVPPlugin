package com.cosmic.vpplugin.generator;

import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.ExtendRelation;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalArea;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.DiagramManager;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IUseCaseDiagramUIModel;
import com.vp.plugin.diagram.connector.IIncludeUIModel;
import com.vp.plugin.diagram.shape.IActorUIModel;
import com.vp.plugin.diagram.shape.IUseCaseUIModel;
import com.vp.plugin.model.IActor;
import com.vp.plugin.model.IAssociation;
import com.vp.plugin.model.IExtend;
import com.vp.plugin.model.IExtensionPoint;
import com.vp.plugin.model.IInclude;
import com.vp.plugin.model.IPackage;
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
 * <h3>v5.0 - Separazione MODEL / VIEW (rifattorizzazione dopo la revisione del professore)</h3>
 * In Visual Paradigm ogni cosa ha due facce:
 * <ul>
 *   <li><b>MODEL</b>: l'elemento nel database del progetto ({@code IUseCase}, {@code IActor}, {@code IInclude},
 *       {@code IPackage}), con un id univoco. Si crea con {@code IModelElementFactory}.</li>
 *   <li><b>VIEW</b>: la forma grafica su UN diagramma ({@code IUseCaseUIModel}, {@code IActorUIModel}, connettore).
 *       Si crea con {@code DiagramManager.createDiagramElement(diagram, modelElement)} e PUNTA a un elemento model
 *       esistente: lo stesso elemento puo' avere piu' viste (la prima e' la "master view", le altre "auxiliary view").</li>
 * </ul>
 * Prima di questa versione il generatore creava un diagramma unico (o uno per sottosistema) e, quando una relazione
 * attraversava due diagrammi, rinunciava a disegnarla. Ora:
 * <ul>
 *   <li>per ogni {@link FunctionalArea} si crea un {@code IPackage} (annidato nel package del suo sottosistema, se
 *       il documento ne ha uno) e un {@code IUseCaseDiagramUIModel} messo FISICAMENTE dentro quel package
 *       ({@code package.addSubDiagram(diagram)});</li>
 *   <li>gli attori sono elementi model DELLA PROPRIA AREA (stesso package del diagramma dove operano);</li>
 *   <li>ogni use case ha UN SOLO {@code IUseCase} nel database, creato con {@code createUseCase()} nel package della
 *       sua area. Se {@code UC-A} (area 1) include {@code UC-B} (area 2), per UC-B NON si chiama
 *       {@code createUseCase()}: si cerca l'{@code IUseCase} esistente e si crea soltanto una nuova VIEW
 *       sul diagramma dell'area 1 ({@code createDiagramElement(diagramArea1, ucBEsistente)}); solo dopo si disegna la
 *       freccia {@code <<include>>} tra le due viste di QUEL diagramma. L'{@code IInclude} nel model e' uno solo.</li>
 * </ul>
 * Le viste ausiliarie vanno in una colonna a destra, separata dagli use case propri dell'area. Non toccano
 * {@code UseCase.vpElementId}/{@code diagramId} (restano quelli della master view): la sincronizzazione con VP e i CFP
 * "dello scope del diagramma" non vedono mai uno use case duplicato.
 *
 * <h3>Ciclo di vita incrementale (anti-freeze, invariato dalla v3.0)</h3>
 * <pre>
 *   generator.begin(projectName);                // una volta
 *   generator.appendUseCase(model, uc);          // dopo OGNI use case misurato (pochi elementi, ~ms)
 *   generator.finish();                          // una volta
 * </pre>
 * TUTTI i metodi pubblici vanno invocati sul thread grafico (EDT): l'Open API di VP non e' thread-safe.
 * Le relazioni &lt;&lt;include&gt;&gt;/&lt;&lt;extend&gt;&gt; si disegnano appena ESISTONO i model di entrambi gli use
 * case (il target puo' comparire piu' avanti nel documento, anche in un'altra area).
 *
 * ID tracking (Epic 4): {@code UseCase.vpElementId}/{@code diagramId}, {@code Actor.vpElementId},
 * {@code FunctionalArea.vpPackageId}/{@code diagramId} sono valorizzati subito dopo la creazione.
 */
public final class UseCaseDiagramGenerator {

    private static final int ACTOR_X = 60;
    private static final int ACTOR_Y_START = 60;
    private static final int ACTOR_Y_STEP = 160;
    private static final int ACTOR_W = 60;
    private static final int ACTOR_H = 90;

    private static final int UC_X = 320;
    private static final int UC_Y_START = 60;
    private static final int UC_Y_STEP = 130;
    private static final int UC_COL_STEP = 260;
    /** Colonne della griglia degli use case PROPRI dell'area (riempita per righe); la colonna successiva e' per le viste ausiliarie. */
    private static final int UC_COLS = 3;
    private static final int UC_W = 220;
    private static final int UC_H = 90;

    private final DiagramManager diagramManager = ApplicationManager.instance().getDiagramManager();

    private Consumer<String> logSink = message -> { };

    // ---- stato della sessione incrementale ----
    private String projectName;

    /** Package dei sottosistemi (nome -> IPackage). */
    private final Map<String, IPackage> subsystemPackages = new HashMap<>();
    /** Stato per area funzionale (areaId -> package + diagramma + viste). */
    private final Map<String, AreaState> areas = new LinkedHashMap<>();

    /** MODEL: l'UNICO IUseCase nel database per ogni use case (ucId -> elemento). */
    private final Map<String, IUseCase> useCaseElements = new HashMap<>();
    /** ucId -> id dell'area che lo possiede (dove sta la sua master view). */
    private final Map<String, String> useCaseHomeArea = new HashMap<>();
    /** MODEL: IActor per attore (actorId -> elemento); ogni attore appartiene a una sola area. */
    private final Map<String, IActor> actorElements = new HashMap<>();

    private final List<PendingRelation> pending = new ArrayList<>();
    /** Relazioni gia' disegnate ("include|from|to"): un solo IInclude/IExtend nel model per coppia. */
    private final Set<String> drawnRelations = new HashSet<>();
    private int auxiliaryViews;

    /** Tutto cio' che il generatore sa di UN'area: il suo package, il suo diagramma e le viste che ci ha messo. */
    private static final class AreaState {
        final FunctionalArea area;
        final IPackage pkg;
        final IUseCaseDiagramUIModel diagram;
        /** VIEW: ucId -> forma su QUESTO diagramma (master view se lo use case e' dell'area, auxiliary view altrimenti). */
        final Map<String, IUseCaseUIModel> useCaseViews = new HashMap<>();
        /** VIEW: actorId -> forma su questo diagramma. */
        final Map<String, IActorUIModel> actorViews = new HashMap<>();
        final Set<String> auxiliaryUseCaseIds = new HashSet<>();
        int ownedCount;
        int auxiliaryCount;
        int actorCount;

        AreaState(FunctionalArea area, IPackage pkg, IUseCaseDiagramUIModel diagram) {
            this.area = area;
            this.pkg = pkg;
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

    // ------------------------------------------------------------------
    // Ciclo di vita incrementale
    // ------------------------------------------------------------------

    /** Avvia una nuova sessione di disegno (package e diagrammi si creano al primo use case di ciascuna area). */
    public void begin(String projectName) {
        this.projectName = projectName;
        subsystemPackages.clear();
        areas.clear();
        useCaseElements.clear();
        useCaseHomeArea.clear();
        actorElements.clear();
        pending.clear();
        drawnRelations.clear();
        auxiliaryViews = 0;
    }

    /**
     * Disegna UN use case nel diagramma della SUA area (creando package e diagramma al primo use case dell'area), gli
     * attori propri dell'area che ancora non ci sono, le associazioni e le relazioni di include/extend ora
     * risolvibili (comprese quelle verso use case di altre aree, via vista ausiliaria).
     */
    public void appendUseCase(CosmicJsonModel model, UseCase uc) {
        FunctionalArea area = model.areaOf(uc);
        if (area == null) {
            throw new IllegalStateException("Lo use case [" + uc.id + "] non appartiene ad alcuna area funzionale del modello.");
        }
        AreaState st = ensureArea(area);

        // --- MODEL: l'unico IUseCase, nel package dell'area ---
        IUseCase ucModel = IModelElementFactory.instance().createUseCase();
        ucModel.setName(uc.name);
        ucModel.setDescription(UseCaseDescription.build(uc));
        st.pkg.addChild(ucModel);

        // --- VIEW: la master view sul diagramma dell'area (griglia per righe) ---
        IUseCaseUIModel ucView = (IUseCaseUIModel) diagramManager.createDiagramElement(st.diagram, ucModel);
        int col = st.ownedCount % UC_COLS;
        int row = st.ownedCount / UC_COLS;
        ucView.setBounds(UC_X + col * UC_COL_STEP, UC_Y_START + row * UC_Y_STEP, UC_W, UC_H);
        st.ownedCount++;

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
            drawActorAssociation(st, model, actorId, uc.id, ucModel, ucView);
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

    /** Chiude la sessione: segnala le relazioni mai risolte e riassume cio' che e' stato creato. */
    public void finish() {
        flushPendingRelations(true);
        logSink.accept("Generati " + areas.size() + " package/diagrammi (uno per area funzionale), "
                + useCaseElements.size() + " use case nel modello VP; "
                + auxiliaryViews + " viste ausiliarie per gli use case referenziati da altre aree "
                + "(stesso elemento del database: nessun duplicato).");
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
    // Package e diagramma dell'area
    // ------------------------------------------------------------------

    /**
     * Package + diagramma dell'area, creati al primo bisogno. Struttura nel model explorer di VP:
     * <pre>
     *   PRE - Booking                              (IPackage del sottosistema, se il documento ne ha uno)
     *    └─ F-PRE-04: Commissioned rooms management   (IPackage dell'area)
     *        ├─ UCD - F-PRE-04: ...                   (il diagramma, dentro il package)
     *        ├─ Hotel manager                         (IActor dell'area)
     *        └─ Add commissioned room, ...            (IUseCase dell'area)
     * </pre>
     */
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

        @SuppressWarnings("deprecation")
        String diagramType = DiagramManager.DIAGRAM_TYPE_USE_CASE_DIAGRAM;
        IUseCaseDiagramUIModel diagram = (IUseCaseDiagramUIModel) diagramManager.createDiagram(diagramType);
        diagram.setName("UCD - " + area.label());
        pkg.addSubDiagram(diagram);                 // il diagramma sta FISICAMENTE dentro il package dell'area
        area.diagramId = diagram.getId();

        st = new AreaState(area, pkg, diagram);
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
    // Attori (propri dell'area)
    // ------------------------------------------------------------------

    private void drawActorAssociation(AreaState st, CosmicJsonModel model, String actorId, String ucId,
                                      IUseCase ucModel, IUseCaseUIModel ucView) {
        Actor actorDto = model.findActor(actorId);
        if (actorDto == null) {
            return;
        }

        // MODEL: un IActor per attore, nel package dell'area che LO possiede (di norma quella dello use case).
        IActor actorModel = actorElements.get(actorId);
        if (actorModel == null) {
            FunctionalArea owner = model.areaOf(actorDto);
            AreaState ownerState = (owner == null || owner == st.area) ? st : ensureArea(owner);
            actorModel = IModelElementFactory.instance().createActor();
            actorModel.setName(actorDto.name);
            actorModel.setDescription(actorDto.description == null ? "" : actorDto.description);
            ownerState.pkg.addChild(actorModel);
            actorElements.put(actorId, actorModel);
            actorDto.vpElementId = actorModel.getId();
        }

        // VIEW: la forma sul diagramma di questa area (se l'attore fosse di un'altra area sarebbe una vista ausiliaria).
        IActorUIModel actorView = st.actorViews.get(actorId);
        if (actorView == null) {
            actorView = (IActorUIModel) diagramManager.createDiagramElement(st.diagram, actorModel);
            actorView.setBounds(ACTOR_X, ACTOR_Y_START + st.actorCount * ACTOR_Y_STEP, ACTOR_W, ACTOR_H);
            st.actorCount++;
            st.actorViews.put(actorId, actorView);
        }

        IAssociation associationModel = IModelElementFactory.instance().createAssociation();
        associationModel.setFrom(actorModel);
        associationModel.setTo(ucModel);
        diagramManager.createConnector(
                st.diagram, associationModel, (IDiagramElement) actorView, (IDiagramElement) ucView, null);
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
     * VISTA AUSILIARIA creata sull'{@code IUseCase} ESISTENTE. Qui NON si chiama mai {@code createUseCase()}: e' il
     * punto che garantisce un solo use case nel database.
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
        int row = st.auxiliaryCount++;
        view.setBounds(UC_X + UC_COLS * UC_COL_STEP, UC_Y_START + row * UC_Y_STEP, UC_W, UC_H);
        st.useCaseViews.put(ucId, view);
        st.auxiliaryUseCaseIds.add(ucId);
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
}
