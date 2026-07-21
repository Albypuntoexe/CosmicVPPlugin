package com.cosmic.vpplugin.model;

import com.cosmic.vpplugin.json.MiniJsonParser;

import java.util.List;
import java.util.Map;

/**
 * Convert la struttura generica prodotta da {@link MiniJsonParser}
 * (Map/List/String/Double/Boolean/null) nei POJO tipizzati di
 * {@link CosmicJsonModel}.
 *
 * FIX (bug segnalato: <<include>>/<<extend>> mai generate):
 * la versione precedente leggeva "includesIds" ed "extendsList" SOLO se
 * annidati dentro un oggetto "relations": { "includes": [...], "extends":
 * [...] }. Il JSON realmente in uso li espone invece come chiavi dirette
 * dell'oggetto useCase:
 *
 *   "includesIds": ["UC2"],
 *   "extendsList": [ { "targetId": "UC1", "extensionPoint": "..." } ]
 *
 * Con la struttura precedente, u.get("relations") restituiva null, il blocco
 * di lettura non veniva mai eseguito, e le liste rimanevano vuote
 * (inizializzate ma non popolate: nessuna NullPointerException, ma nessun
 * dato — per questo il bug era "silenzioso"). Ora si legge prima la forma
 * piatta (quella corrente); se assente, si tenta come fallback la vecchia
 * forma annidata, per non rompere eventuali JSON di test gia' scritti con lo
 * schema precedente.
 *
 * Ho anche irrobustito {@link #asMap(Object)} e {@link #list(Object)}: prima
 * facevano un cast diretto assumendo il tipo corretto (rischio di
 * ClassCastException silenziosa se un campo del JSON non e' del tipo
 * atteso); ora controllano il tipo con {@code instanceof} e restituiscono
 * rispettivamente {@code null}/lista vuota in ogni altro caso.
 */
public final class CosmicJsonMapper {

    private CosmicJsonMapper() { }

    public static CosmicJsonModel map(String jsonText) {
        Map<String, Object> root = MiniJsonParser.parseObject(jsonText);
        CosmicJsonModel model = new CosmicJsonModel();

        model.projectName = str(root.get("projectName"), "Progetto COSMIC AI");

        for (Object rawActor : list(root.get("actors"))) {
            Map<String, Object> a = asMap(rawActor);
            if (a == null) continue;
            CosmicJsonModel.Actor actor = new CosmicJsonModel.Actor();
            actor.id = str(a.get("id"), null);
            actor.name = str(a.get("name"), "Attore");
            actor.description = str(a.get("description"), "");
            model.actors.add(actor);
        }

        for (Object rawUc : list(root.get("useCases"))) {
            Map<String, Object> u = asMap(rawUc);
            if (u == null) continue;

            CosmicJsonModel.UseCase uc = new CosmicJsonModel.UseCase();
            uc.id = str(u.get("id"), null);
            uc.name = str(u.get("name"), "Use Case");
            uc.primaryActorId = str(u.get("primaryActorId"), null);
            uc.specification = str(u.get("specification"), "");

            for (Object s : list(u.get("mainScenario"))) {
                uc.mainScenario.add(String.valueOf(s));
            }
            for (Object s : list(u.get("exceptions"))) {
                uc.exceptions.add(String.valueOf(s));
            }

            // ---- includesIds: prima la forma piatta (attuale), poi fallback annidato ----
            List<Object> includesRaw = list(u.get("includesIds"));
            if (includesRaw.isEmpty()) {
                Map<String, Object> relations = asMap(u.get("relations"));
                if (relations != null) {
                    includesRaw = list(relations.get("includes"));
                }
            }
            for (Object inc : includesRaw) {
                if (inc != null) {
                    uc.includesIds.add(String.valueOf(inc));
                }
            }

            // ---- extendsList: prima la forma piatta (attuale), poi fallback annidato ----
            List<Object> extendsRaw = list(u.get("extendsList"));
            if (extendsRaw.isEmpty()) {
                Map<String, Object> relations = asMap(u.get("relations"));
                if (relations != null) {
                    extendsRaw = list(relations.get("extends"));
                }
            }
            for (Object rawExt : extendsRaw) {
                Map<String, Object> e = asMap(rawExt);
                if (e == null) continue;
                CosmicJsonModel.ExtendRelation ext = new CosmicJsonModel.ExtendRelation();
                ext.targetId = str(e.get("targetId"), null);
                ext.extensionPoint = str(e.get("extensionPoint"), "");
                uc.extendsList.add(ext);
            }

            for (Object rawFp : list(u.get("functionalProcesses"))) {
                Map<String, Object> f = asMap(rawFp);
                if (f == null) continue;
                CosmicJsonModel.FunctionalProcess fp = new CosmicJsonModel.FunctionalProcess();
                fp.fpId = str(f.get("fpId"), null);
                fp.fpName = str(f.get("fpName"), "");
                fp.triggeringEvent = str(f.get("triggeringEvent"), "");
                for (Object rawSp : list(f.get("subProcesses"))) {
                    Map<String, Object> sp = asMap(rawSp);
                    if (sp == null) continue;
                    CosmicJsonModel.SubProcess sub = new CosmicJsonModel.SubProcess();
                    sub.step = (int) num(sp.get("step"), 0);
                    sub.description = str(sp.get("description"), "");
                    sub.functionalUser = str(sp.get("functionalUser"), "");
                    sub.dataMovementType = str(sp.get("dataMovementType"), null);
                    sub.dataGroup = str(sp.get("dataGroup"), null);
                    sub.objectOfInterest = str(sp.get("objectOfInterest"), null);
                    fp.subProcesses.add(sub);
                }
                uc.functionalProcesses.add(fp);
            }

            model.useCases.add(uc);
        }

        return model;
    }

    // ----------------------- helpers -----------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (o instanceof List) ? (List<Object>) o : java.util.Collections.emptyList();
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static double num(Object o, double fallback) {
        return (o instanceof Double) ? (Double) o : fallback;
    }
}
