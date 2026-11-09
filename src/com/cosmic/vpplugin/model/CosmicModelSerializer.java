package com.cosmic.vpplugin.model;

import com.cosmic.vpplugin.document.UseCaseChunk;
import com.cosmic.vpplugin.json.MiniJsonParser;
import com.cosmic.vpplugin.model.CosmicJsonModel.Actor;
import com.cosmic.vpplugin.model.CosmicJsonModel.ExtendRelation;
import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * v4.0 - Serializzazione COMPLETA (round-trip senza perdite) di {@link CosmicJsonModel}, usata per salvare il
 * modello COSMIC dentro il file .vpp.
 *
 * Non si riusa {@link CosmicJsonMapper}: quello e' il lettore del JSON prodotto dall'LLM e ignora volutamente i
 * campi "di sessione" (vpElementId, diagramId, coActorIds, violation, flag di misura...) che invece qui servono
 * per ripopolare l'albero e riagganciare ogni use case al suo elemento su Visual Paradigm.
 *
 * Scritto a mano (come {@link MiniJsonParser}) per non dipendere da librerie JSON assenti nel classpath di VP.
 * La lettura e' tollerante: campi mancanti = valore di default, versioni future con campi in piu' = ignorati.
 */
public final class CosmicModelSerializer {

    /**
     * Versione dello schema di persistenza (da incrementare se un campo cambia significato).
     * v2 (plugin 4.1): aggiunge la sezione "requirements" (requisiti originali per "Valida Modello vs Requisiti").
     * I file v1 si leggono senza problemi: semplicemente non hanno requisiti.
     */
    public static final int SCHEMA_VERSION = 2;

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

        sb.append("  \"actors\": [");
        for (int i = 0; i < m.actors.size(); i++) {
            Actor a = m.actors.get(i);
            sb.append(i == 0 ? "\n" : ",\n").append("    {");
            inline(sb, "id", a.id).append(", ");
            inline(sb, "name", a.name).append(", ");
            inline(sb, "description", a.description).append(", ");
            inline(sb, "vpElementId", a.vpElementId).append("}");
        }
        sb.append(m.actors.isEmpty() ? "],\n" : "\n  ],\n");

        sb.append("  \"requirements\": [");
        List<UseCaseChunk> reqs = requirements == null ? Collections.<UseCaseChunk>emptyList() : requirements;
        for (int i = 0; i < reqs.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            writeChunk(sb, reqs.get(i));
        }
        sb.append(reqs.isEmpty() ? "],\n" : "\n  ],\n");

        sb.append("  \"useCases\": [");
        for (int i = 0; i < m.useCases.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            writeUseCase(sb, m.useCases.get(i));
        }
        sb.append(m.useCases.isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void writeChunk(StringBuilder sb, UseCaseChunk c) {
        sb.append("    {\n");
        field(sb, 3, "index", c.index).append(",\n");
        field(sb, 3, "id", c.id).append(",\n");
        field(sb, 3, "name", c.name).append(",\n");
        field(sb, 3, "reference", c.reference).append(",\n");
        field(sb, 3, "subsystem", c.subsystem).append(",\n");
        stringArray(sb, 3, "primaryActors", c.primaryActors).append(",\n");
        stringArray(sb, 3, "includedIds", c.includedIds).append(",\n");
        stringArray(sb, 3, "scenarioLines", c.scenarioLines).append("\n");
        sb.append("    }");
    }

    private static void writeUseCase(StringBuilder sb, UseCase uc) {
        sb.append("    {\n");
        field(sb, 3, "id", uc.id).append(",\n");
        field(sb, 3, "name", uc.name).append(",\n");
        field(sb, 3, "subsystem", uc.subsystem).append(",\n");
        field(sb, 3, "primaryActorId", uc.primaryActorId).append(",\n");
        stringArray(sb, 3, "coActorIds", uc.coActorIds).append(",\n");
        field(sb, 3, "specification", uc.specification).append(",\n");
        stringArray(sb, 3, "mainScenario", uc.mainScenario).append(",\n");
        stringArray(sb, 3, "exceptions", uc.exceptions).append(",\n");
        stringArray(sb, 3, "includesIds", uc.includesIds).append(",\n");

        sb.append("      \"extendsList\": [");
        for (int i = 0; i < uc.extendsList.size(); i++) {
            ExtendRelation e = uc.extendsList.get(i);
            sb.append(i == 0 ? "" : ", ").append("{");
            inline(sb, "targetId", e.targetId).append(", ");
            inline(sb, "extensionPoint", e.extensionPoint).append("}");
        }
        sb.append("],\n");

        field(sb, 3, "vpElementId", uc.vpElementId).append(",\n");
        field(sb, 3, "diagramId", uc.diagramId).append(",\n");
        field(sb, 3, "violation", uc.violation).append(",\n");
        field(sb, 3, "specFingerprint", uc.specFingerprint).append(",\n");

        sb.append("      \"functionalProcesses\": [");
        for (int i = 0; i < uc.functionalProcesses.size(); i++) {
            FunctionalProcess fp = uc.functionalProcesses.get(i);
            sb.append(i == 0 ? "\n" : ",\n").append("        {\n");
            field(sb, 5, "fpId", fp.fpId).append(",\n");
            field(sb, 5, "fpName", fp.fpName).append(",\n");
            field(sb, 5, "triggeringEvent", fp.triggeringEvent).append(",\n");
            sb.append("          \"subProcesses\": [");
            for (int j = 0; j < fp.subProcesses.size(); j++) {
                SubProcess sp = fp.subProcesses.get(j);
                sb.append(j == 0 ? "\n" : ",\n").append("            {");
                inline(sb, "step", sp.step).append(", ");
                inline(sb, "description", sp.description).append(", ");
                inline(sb, "functionalUser", sp.functionalUser).append(", ");
                inline(sb, "dataMovementType", sp.dataMovementType).append(", ");
                inline(sb, "dataGroup", sp.dataGroup).append(", ");
                inline(sb, "objectOfInterest", sp.objectOfInterest).append(", ");
                inline(sb, "unmeasured", sp.unmeasured).append(", ");
                inline(sb, "manuallyEdited", sp.manuallyEdited).append("}");
            }
            sb.append(fp.subProcesses.isEmpty() ? "]\n" : "\n          ]\n").append("        }");
        }
        sb.append(uc.functionalProcesses.isEmpty() ? "]\n" : "\n      ]\n").append("    }");
    }

    // ======================================================================
    // Lettura
    // ======================================================================

    /** @throws IllegalArgumentException se il testo non e' un JSON valido o non e' un oggetto radice */
    public static CosmicJsonModel fromJson(String json) {
        Map<String, Object> root = MiniJsonParser.parseObject(json);
        CosmicJsonModel m = new CosmicJsonModel();
        m.projectName = str(root.get("projectName"), "Progetto COSMIC AI");

        for (Object o : list(root.get("actors"))) {
            Map<String, Object> a = map(o);
            if (a == null) continue;
            Actor actor = new Actor();
            actor.id = str(a.get("id"), null);
            actor.name = str(a.get("name"), "Attore");
            actor.description = str(a.get("description"), "");
            actor.vpElementId = str(a.get("vpElementId"), null);
            m.actors.add(actor);
        }

        for (Object o : list(root.get("useCases"))) {
            Map<String, Object> u = map(o);
            if (u == null) continue;
            UseCase uc = new UseCase();
            uc.id = str(u.get("id"), null);
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
            if (uc.id != null) m.useCases.add(uc);     // senza id lo use case non e' riagganciabile: scartato
        }
        return m;
    }

    /**
     * Requisiti originali salvati insieme al modello; lista vuota se il file e' di una versione precedente o l'analisi
     * non e' mai partita da un documento. Un chunk non ricostruibile viene scartato, non fa fallire il ripristino.
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
                        str(c.get("reference"), null), str(c.get("subsystem"), null), actors, includes, lines));
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
        return (o instanceof List) ? (List<Object>) o : java.util.Collections.emptyList();
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
