package com.cosmic.vpplugin.model;

import com.cosmic.vpplugin.document.UseCaseChunk;
import com.cosmic.vpplugin.json.MiniJsonParser;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.ExtendRelation;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalArea;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serializzazione COMPLETA (round-trip senza perdite) di {@link CosmicJsonModel}, usata per salvare il modello
 * COSMIC nel sidecar del progetto VP.
 *
 * Non si riusa {@link CosmicJsonMapper}: quello e' il lettore del JSON prodotto dall'LLM e ignora volutamente i
 * campi "di sessione" (vpElementId, diagramId, coActorIds, violation, flag di misura...) che invece qui servono
 * per ripopolare l'albero e riagganciare ogni use case al suo elemento su Visual Paradigm.
 *
 * Scritto a mano (come {@link MiniJsonParser}) per non dipendere da librerie JSON assenti nel classpath di VP.
 * La lettura e' tollerante: campi mancanti = valore di default, versioni future con campi in piu' = ignorati.
 *
 * <h3>Schema v3 (plugin 5.0) - gerarchia Project -> FunctionalArea -> UseCase/Actor</h3>
 * <pre>
 * {
 *   "schemaVersion": 3,
 *   "projectName": "...",
 *   "functionalAreas": [
 *     { "id": "F-PRE-04", "name": "...", "subsystem": "...", "vpPackageId": "...", "vpSystemId": "...", "diagramId": "...",
 *       "actors":   [ { "id", "name", "description", "vpElementId" } ],
 *       "useCases": [ { ...use case completo con functionalProcesses... } ] }
 *   ],
 *   "requirements": [ { ...chunk originale, con functionalAreaRef/functionalAreaName... } ]
 * }
 * </pre>
 * {@code toJson} itera sulle aree e scrive, DENTRO ciascuna, i suoi attori e i suoi use case; {@code fromJson}
 * ricostruisce la stessa gerarchia (le liste dell'area si riempiono solo tramite {@code addActor}/{@code addUseCase}, che
 * rimettono a posto {@code areaId}).
 *
 * <h3>Compatibilita' con i file precedenti</h3>
 * Gli schemi 1-2 (v4.x) hanno {@code actors} e {@code useCases} piatti sulla radice e NESSUN dato d'area. Si leggono
 * ancora: gli use case vengono raggruppati in una area per sottosistema e gli attori copiati nelle aree in cui sono
 * usati (il primo uso conserva id e {@code vpElementId}; le copie successive prendono un nuovo id e restano senza
 * elemento VP). {@link CosmicJsonModel#migratedFromFlatSchema} lo segnala; rieseguire l'analisi dal documento da'
 * le aree funzionali vere. I requisiti dei file vecchi si leggono senza area (null).
 */
public final class CosmicModelSerializer {

    /**
     * Versione dello schema di persistenza (da incrementare se un campo cambia significato).
     * v2 (plugin 4.1): aggiunge la sezione "requirements".
     * v3 (plugin 5.0): gerarchia "functionalAreas" (attori e use case annidati nell'area) e area nei requisiti.
     */
    public static final int SCHEMA_VERSION = 3;

    private CosmicModelSerializer() { }

    // ======================================================================
    // Scrittura
    // ======================================================================

    public static String toJson(CosmicJsonModel m) {
        return toJson(m, Collections.<UseCaseChunk>emptyList());
    }

    /**
     * Serializza modello E requisiti originali (i chunk estratti dal documento). I requisiti vivono nel sidecar e non
     * dentro {@link CosmicJsonModel}: il modello resta un contenitore di sola misura e il parsing del documento
     * ({@code UseCaseChunk}) non viene toccato.
     */
    public static String toJson(CosmicJsonModel m, List<UseCaseChunk> requirements) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("{\n");
        field(sb, 1, "schemaVersion", SCHEMA_VERSION).append(",\n");
        field(sb, 1, "projectName", m.projectName).append(",\n");

        // --- Project -> FunctionalArea[] ---
        List<FunctionalArea> areas = m.functionalAreas();
        indent(sb, 1).append("\"functionalAreas\": [");
        for (int i = 0; i < areas.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            writeArea(sb, areas.get(i));
        }
        sb.append(areas.isEmpty() ? "],\n" : "\n  ],\n");

        // --- requisiti originali ---
        indent(sb, 1).append("\"requirements\": [");
        List<UseCaseChunk> reqs = requirements == null ? Collections.<UseCaseChunk>emptyList() : requirements;
        for (int i = 0; i < reqs.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            writeChunk(sb, reqs.get(i));
        }
        sb.append(reqs.isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Un'area con i SUOI attori e i SUOI use case (livello 2 = apertura dell'oggetto area). */
    private static void writeArea(StringBuilder sb, FunctionalArea area) {
        indent(sb, 2).append("{\n");
        field(sb, 3, "id", area.id).append(",\n");
        field(sb, 3, "name", area.name).append(",\n");
        field(sb, 3, "subsystem", area.subsystem).append(",\n");
        field(sb, 3, "vpPackageId", area.vpPackageId).append(",\n");
        field(sb, 3, "vpSystemId", area.vpSystemId).append(",\n");
        field(sb, 3, "diagramId", area.diagramId).append(",\n");

        List<Actor> actors = area.actors();
        indent(sb, 3).append("\"actors\": [");
        for (int i = 0; i < actors.size(); i++) {
            Actor a = actors.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            indent(sb, 4).append("{");
            inline(sb, "id", a.id).append(", ");
            inline(sb, "name", a.name).append(", ");
            inline(sb, "description", a.description).append(", ");
            inline(sb, "vpElementId", a.vpElementId).append("}");
        }
        sb.append(actors.isEmpty() ? "],\n" : "\n").append(actors.isEmpty() ? "" : "      ],\n");

        List<UseCase> useCases = area.useCases();
        indent(sb, 3).append("\"useCases\": [");
        for (int i = 0; i < useCases.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            writeUseCase(sb, useCases.get(i));
        }
        sb.append(useCases.isEmpty() ? "]\n" : "\n      ]\n");
        indent(sb, 2).append("}");
    }

    private static void writeChunk(StringBuilder sb, UseCaseChunk c) {
        indent(sb, 2).append("{\n");
        field(sb, 3, "index", c.index).append(",\n");
        field(sb, 3, "id", c.id).append(",\n");
        field(sb, 3, "name", c.name).append(",\n");
        field(sb, 3, "reference", c.reference).append(",\n");
        field(sb, 3, "subsystem", c.subsystem).append(",\n");
        field(sb, 3, "functionalAreaRef", c.functionalAreaRef).append(",\n");
        field(sb, 3, "functionalAreaName", c.functionalAreaName).append(",\n");
        stringArray(sb, 3, "primaryActors", c.primaryActors).append(",\n");
        stringArray(sb, 3, "includedIds", c.includedIds).append(",\n");
        stringArray(sb, 3, "scenarioLines", c.scenarioLines).append("\n");
        indent(sb, 2).append("}");
    }

    /** Uno use case (livello 4 = apertura dell'oggetto, 5 = campi). */
    private static void writeUseCase(StringBuilder sb, UseCase uc) {
        indent(sb, 4).append("{\n");
        field(sb, 5, "id", uc.id).append(",\n");
        field(sb, 5, "name", uc.name).append(",\n");
        field(sb, 5, "subsystem", uc.subsystem).append(",\n");
        field(sb, 5, "primaryActorId", uc.primaryActorId).append(",\n");
        stringArray(sb, 5, "coActorIds", uc.coActorIds).append(",\n");
        field(sb, 5, "specification", uc.specification).append(",\n");
        stringArray(sb, 5, "mainScenario", uc.mainScenario).append(",\n");
        stringArray(sb, 5, "exceptions", uc.exceptions).append(",\n");
        stringArray(sb, 5, "includesIds", uc.includesIds).append(",\n");

        indent(sb, 5).append("\"extendsList\": [");
        for (int i = 0; i < uc.extendsList.size(); i++) {
            ExtendRelation e = uc.extendsList.get(i);
            sb.append(i == 0 ? "" : ", ").append("{");
            inline(sb, "targetId", e.targetId).append(", ");
            inline(sb, "extensionPoint", e.extensionPoint).append("}");
        }
        sb.append("],\n");

        field(sb, 5, "vpElementId", uc.vpElementId).append(",\n");
        field(sb, 5, "diagramId", uc.diagramId).append(",\n");
        field(sb, 5, "violation", uc.violation).append(",\n");
        field(sb, 5, "specFingerprint", uc.specFingerprint).append(",\n");

        indent(sb, 5).append("\"functionalProcesses\": [");
        for (int i = 0; i < uc.functionalProcesses.size(); i++) {
            FunctionalProcess fp = uc.functionalProcesses.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            indent(sb, 6).append("{\n");
            field(sb, 7, "fpId", fp.fpId).append(",\n");
            field(sb, 7, "fpName", fp.fpName).append(",\n");
            field(sb, 7, "triggeringEvent", fp.triggeringEvent).append(",\n");
            indent(sb, 7).append("\"subProcesses\": [");
            for (int j = 0; j < fp.subProcesses.size(); j++) {
                SubProcess sp = fp.subProcesses.get(j);
                sb.append(j == 0 ? "\n" : ",\n");
                indent(sb, 8).append("{");
                inline(sb, "step", sp.step).append(", ");
                inline(sb, "description", sp.description).append(", ");
                inline(sb, "functionalUser", sp.functionalUser).append(", ");
                inline(sb, "dataMovementType", sp.dataMovementType).append(", ");
                inline(sb, "dataGroup", sp.dataGroup).append(", ");
                inline(sb, "objectOfInterest", sp.objectOfInterest).append(", ");
                inline(sb, "unmeasured", sp.unmeasured).append(", ");
                inline(sb, "manuallyEdited", sp.manuallyEdited).append("}");
            }
            sb.append(fp.subProcesses.isEmpty() ? "]\n" : "\n").append(fp.subProcesses.isEmpty() ? "" : "        ]\n");
            indent(sb, 6).append("}");
        }
        sb.append(uc.functionalProcesses.isEmpty() ? "]\n" : "\n").append(uc.functionalProcesses.isEmpty() ? "" : "      ]\n");
        indent(sb, 4).append("}");
    }

    // ======================================================================
    // Lettura
    // ======================================================================

    /** @throws IllegalArgumentException se il testo non e' un JSON valido o non e' un oggetto radice */
    public static CosmicJsonModel fromJson(String json) {
        Map<String, Object> root = MiniJsonParser.parseObject(json);
        CosmicJsonModel m = new CosmicJsonModel();
        m.projectName = str(root.get("projectName"), "Progetto COSMIC AI");

        if (root.containsKey("functionalAreas")) {
            readAreas(root, m);
        } else {
            readFlatLegacy(root, m);
        }
        return m;
    }

    /** Schema v3: ricostruisce Project -> FunctionalArea -> (Actor, UseCase). */
    private static void readAreas(Map<String, Object> root, CosmicJsonModel m) {
        for (Object ao : list(root.get("functionalAreas"))) {
            Map<String, Object> am = map(ao);
            if (am == null) continue;
            String id = str(am.get("id"), null);
            if (id == null || id.trim().isEmpty()) continue;            // area non riagganciabile: scartata
            FunctionalArea area = m.findOrCreateArea(id, str(am.get("name"), null), str(am.get("subsystem"), null));
            area.vpPackageId = str(am.get("vpPackageId"), null);
            area.vpSystemId = str(am.get("vpSystemId"), null);
            area.diagramId = str(am.get("diagramId"), null);

            for (Object o : list(am.get("actors"))) {
                Actor actor = readActor(map(o));
                if (actor == null || m.findActor(actor.id) != null) continue;   // id attore: univoco nel progetto
                area.addActor(actor);
            }
            for (Object o : list(am.get("useCases"))) {
                UseCase uc = readUseCase(map(o));
                if (uc == null || m.findUseCase(uc.id) != null) continue;       // id use case: univoco nel progetto
                area.addUseCase(uc);
            }
        }
    }

    /**
     * Schemi 1-2: liste piatte sulla radice. Gli use case vanno in un'area per sottosistema (area di default se non ne
     * hanno); ogni area riceve la propria copia degli attori che i suoi use case referenziano.
     */
    private static void readFlatLegacy(Map<String, Object> root, CosmicJsonModel m) {
        m.migratedFromFlatSchema = true;

        Map<String, Actor> legacyActors = new LinkedHashMap<>();
        for (Object o : list(root.get("actors"))) {
            Actor a = readActor(map(o));
            if (a != null) legacyActors.putIfAbsent(a.id, a);
        }

        Set<String> placed = new HashSet<>();                              // id originali gia' piazzati almeno una volta
        Map<String, String> copyId = new HashMap<>();                      // "areaId|idOriginale" -> id dell'attore nell'area

        for (Object o : list(root.get("useCases"))) {
            UseCase uc = readUseCase(map(o));
            if (uc == null || m.findUseCase(uc.id) != null) continue;
            FunctionalArea area = (uc.subsystem == null || uc.subsystem.trim().isEmpty())
                    ? m.defaultArea()
                    : m.findOrCreateArea(null, uc.subsystem, null);
            area.addUseCase(uc);

            uc.primaryActorId = placeActor(m, area, legacyActors, placed, copyId, uc.primaryActorId);
            List<String> co = new ArrayList<>();
            for (String id : uc.coActorIds) {
                String mapped = placeActor(m, area, legacyActors, placed, copyId, id);
                if (mapped != null && !co.contains(mapped)) co.add(mapped);
            }
            uc.coActorIds = co;
        }
        // attori mai referenziati: nessuna perdita di dati, finiscono nell'area di default
        for (Actor a : legacyActors.values()) {
            if (!placed.contains(a.id)) m.defaultArea().addActor(a);
        }
    }

    /** Porta l'attore legacy {@code legacyId} nell'area (copiandolo se serve) e restituisce l'id da usare nell'area. */
    private static String placeActor(CosmicJsonModel m, FunctionalArea area, Map<String, Actor> legacy, Set<String> placed,
                                     Map<String, String> copyId, String legacyId) {
        if (legacyId == null) return null;
        Actor src = legacy.get(legacyId);
        if (src == null) return legacyId;                                  // riferimento orfano: lo si conserva com'e'
        String key = area.id + "|" + legacyId;
        String existing = copyId.get(key);
        if (existing != null) return existing;
        Actor target;
        if (placed.add(legacyId)) {
            target = src;                                                  // primo uso: stesso id, stesso elemento VP
        } else {
            target = new Actor();
            target.id = m.nextActorId();
            target.name = src.name;
            target.description = src.description;                          // vpElementId resta null: nessun elemento VP per la copia
        }
        area.addActor(target);
        copyId.put(key, target.id);
        return target.id;
    }

    private static Actor readActor(Map<String, Object> a) {
        if (a == null) return null;
        Actor actor = new Actor();
        actor.id = str(a.get("id"), null);
        if (actor.id == null) return null;
        actor.name = str(a.get("name"), "Attore");
        actor.description = str(a.get("description"), "");
        actor.vpElementId = str(a.get("vpElementId"), null);
        return actor;
    }

    /** @return lo use case, oppure null se non ha id (senza id non e' riagganciabile: scartato) */
    private static UseCase readUseCase(Map<String, Object> u) {
        if (u == null) return null;
        UseCase uc = new UseCase();
        uc.id = str(u.get("id"), null);
        if (uc.id == null) return null;
        uc.name = str(u.get("name"), "Use Case");
        uc.subsystem = str(u.get("subsystem"), null);
        uc.primaryActorId = str(u.get("primaryActorId"), null);
        strings(u.get("coActorIds"), uc.coActorIds);
        uc.specification = str(u.get("specification"), null);
        strings(u.get("mainScenario"), uc.mainScenario);
        strings(u.get("exceptions"), uc.exceptions);
        strings(u.get("includesIds"), uc.includesIds);
        for (Object eo : list(u.get("extendsList"))) {
            Map<String, Object> e = map(eo);
            if (e == null) continue;
            ExtendRelation rel = new ExtendRelation();
            rel.targetId = str(e.get("targetId"), null);
            rel.extensionPoint = str(e.get("extensionPoint"), "");
            uc.extendsList.add(rel);
        }
        uc.vpElementId = str(u.get("vpElementId"), null);
        uc.diagramId = str(u.get("diagramId"), null);
        uc.violation = str(u.get("violation"), null);
        uc.specFingerprint = str(u.get("specFingerprint"), null);

        for (Object fo : list(u.get("functionalProcesses"))) {
            Map<String, Object> f = map(fo);
            if (f == null) continue;
            FunctionalProcess fp = new FunctionalProcess();
            fp.fpId = str(f.get("fpId"), null);
            fp.fpName = str(f.get("fpName"), "");
            fp.triggeringEvent = str(f.get("triggeringEvent"), "");
            for (Object so : list(f.get("subProcesses"))) {
                Map<String, Object> s = map(so);
                if (s == null) continue;
                SubProcess sp = new SubProcess();
                sp.step = (int) num(s.get("step"), 0);
                sp.description = str(s.get("description"), "");
                sp.functionalUser = str(s.get("functionalUser"), null);
                sp.dataMovementType = str(s.get("dataMovementType"), null);
                sp.dataGroup = str(s.get("dataGroup"), null);
                sp.objectOfInterest = str(s.get("objectOfInterest"), null);
                sp.unmeasured = bool(s.get("unmeasured"));
                sp.manuallyEdited = bool(s.get("manuallyEdited"));
                fp.subProcesses.add(sp);
            }
            uc.functionalProcesses.add(fp);
        }
        return uc;
    }

    /**
     * Requisiti originali salvati insieme al modello; lista vuota se il file e' di una versione precedente o l'analisi
     * non e' mai partita da un documento. Un chunk non ricostruibile viene scartato, non fa fallire il ripristino.
     * I file precedenti alla v5.0 non hanno l'area: arriva null.
     */
    public static List<UseCaseChunk> chunksFromJson(String json) {
        Map<String, Object> root = MiniJsonParser.parseObject(json);
        List<UseCaseChunk> out = new ArrayList<>();
        for (Object o : list(root.get("requirements"))) {
            Map<String, Object> c = map(o);
            if (c == null) continue;
            String id = str(c.get("id"), null);
            if (id == null) continue;
            List<String> actors = new ArrayList<>(), includes = new ArrayList<>(), lines = new ArrayList<>();
            strings(c.get("primaryActors"), actors);
            strings(c.get("includedIds"), includes);
            strings(c.get("scenarioLines"), lines);
            try {
                out.add(UseCaseChunk.restore((int) num(c.get("index"), out.size() + 1), id, str(c.get("name"), ""),
                        str(c.get("reference"), null), str(c.get("subsystem"), null),
                        str(c.get("functionalAreaRef"), null), str(c.get("functionalAreaName"), null),
                        actors, includes, lines));
            } catch (RuntimeException ignored) { /* chunk malformato: scartato */ }
        }
        return out;
    }

    // ======================================================================
    // Helper di scrittura
    // ======================================================================

    private static StringBuilder indent(StringBuilder sb, int level) {
        for (int i = 0; i < level * 2; i++) sb.append(' ');
        return sb;
    }

    private static StringBuilder field(StringBuilder sb, int level, String key, String value) {
        indent(sb, level).append('"').append(key).append("\": ");
        return value == null ? sb.append("null") : quote(sb, value);
    }

    private static StringBuilder field(StringBuilder sb, int level, String key, int value) {
        return indent(sb, level).append('"').append(key).append("\": ").append(value);
    }

    private static StringBuilder inline(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\": ");
        return value == null ? sb.append("null") : quote(sb, value);
    }

    private static StringBuilder inline(StringBuilder sb, String key, int value) {
        return sb.append('"').append(key).append("\": ").append(value);
    }

    private static StringBuilder inline(StringBuilder sb, String key, boolean value) {
        return sb.append('"').append(key).append("\": ").append(value);
    }

    private static StringBuilder stringArray(StringBuilder sb, int level, String key, List<String> values) {
        indent(sb, level).append('"').append(key).append("\": [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            String v = values.get(i);
            if (v == null) sb.append("null"); else quote(sb, v);
        }
        return sb.append(']');
    }

    private static StringBuilder quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"');
    }

    // ======================================================================
    // Helper di lettura
    // ======================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (o instanceof List) ? (List<Object>) o : Collections.emptyList();
    }

    private static void strings(Object o, List<String> into) {
        for (Object v : list(o)) if (v != null) into.add(String.valueOf(v));
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static double num(Object o, double fallback) {
        return (o instanceof Double) ? (Double) o : fallback;
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o);
    }
}
