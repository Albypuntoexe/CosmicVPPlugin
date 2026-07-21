package com.cosmic.vpplugin.model;

import com.cosmic.vpplugin.json.MiniJsonParser;

import java.util.List;
import java.util.Map;

/**
 * Converte la struttura generica prodotta da {@link MiniJsonParser}
 * (Map/List/String/Double/Boolean/null) nei POJO tipizzati di
 * {@link CosmicJsonModel}, cosi' che il resto del plugin non debba mai
 * manipolare direttamente Map/List "grezze".
 */
public final class CosmicJsonMapper {

    private CosmicJsonMapper() { }

    public static CosmicJsonModel map(String jsonText) {
        Map<String, Object> root = MiniJsonParser.parseObject(jsonText);
        CosmicJsonModel model = new CosmicJsonModel();

        model.projectName = str(root.get("projectName"), "Progetto COSMIC AI");

        for (Object rawActor : list(root.get("actors"))) {
            Map<String, Object> a = asMap(rawActor);
            CosmicJsonModel.Actor actor = new CosmicJsonModel.Actor();
            actor.id = str(a.get("id"), null);
            actor.name = str(a.get("name"), "Attore");
            actor.description = str(a.get("description"), "");
            model.actors.add(actor);
        }

        for (Object rawUc : list(root.get("useCases"))) {
            Map<String, Object> u = asMap(rawUc);
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

            Map<String, Object> relations = asMap(u.get("relations"));
            if (relations != null) {
                for (Object inc : list(relations.get("includes"))) {
                    uc.includesIds.add(String.valueOf(inc));
                }
                for (Object rawExt : list(relations.get("extends"))) {
                    Map<String, Object> e = asMap(rawExt);
                    CosmicJsonModel.ExtendRelation ext = new CosmicJsonModel.ExtendRelation();
                    ext.targetId = str(e.get("targetId"), null);
                    ext.extensionPoint = str(e.get("extensionPoint"), "");
                    uc.extendsList.add(ext);
                }
            }

            for (Object rawFp : list(u.get("functionalProcesses"))) {
                Map<String, Object> f = asMap(rawFp);
                CosmicJsonModel.FunctionalProcess fp = new CosmicJsonModel.FunctionalProcess();
                fp.fpId = str(f.get("fpId"), null);
                fp.fpName = str(f.get("fpName"), "");
                fp.triggeringEvent = str(f.get("triggeringEvent"), "");
                for (Object rawSp : list(f.get("subProcesses"))) {
                    Map<String, Object> sp = asMap(rawSp);
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
        return o == null ? null : (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o == null ? java.util.Collections.emptyList() : (List<Object>) o;
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static double num(Object o, double fallback) {
        return o instanceof Double ? (Double) o : fallback;
    }
}
