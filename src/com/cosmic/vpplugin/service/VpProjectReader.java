package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.generator.UseCaseDescription;

import com.vp.plugin.model.IActor;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IUseCase;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.factory.IModelElementFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * v4.0 - Legge dal progetto Visual Paradigm lo stato corrente del modello use case ("Source of Truth"):
 * nome e testo di ogni use case, attori, associazioni attore-use case, relazioni include/extend.
 *
 * Restituisce una fotografia di oggetti Java semplici ({@link Snapshot}); non modifica nulla su VP.
 * Va invocata sul thread grafico (EDT): l'Open API non e' thread-safe.
 *
 * Open API usata: {@code IProject.allLevelModelElementIterator(String)} (ripiego: {@code modelElementIterator(String)})
 * con i tipi {@code IModelElementFactory.MODEL_TYPE_*}; {@code IUseCase.getName()/getDescription()}. Il metodo di
 * enumerazione e' invocato per riflessione perche' ha cambiato nome tra versioni dell'Open API.
 */
final class VpProjectReader {

    static final class UseCaseSnap {
        final String vpId;
        final String name;
        /** Descrizione grezza cosi' come sta su VP. */
        final String description;
        /** v5.0 - Id dell'elemento che CONTIENE lo use case (il package dell'area funzionale), null se non disponibile. */
        final String parentVpId;

        UseCaseSnap(String vpId, String name, String description, String parentVpId) {
            this.vpId = vpId;
            this.name = name;
            this.description = description;
            this.parentVpId = parentVpId;
        }
    }

    static final class ActorSnap {
        final String vpId;
        final String name;

        ActorSnap(String vpId, String name) {
            this.vpId = vpId;
            this.name = name;
        }
    }

    /** Relazione orientata fra due elementi VP (per include/extend: da -> a; per le associazioni: attore -> use case). */
    static final class LinkSnap {
        final String fromVpId;
        final String toVpId;

        LinkSnap(String fromVpId, String toVpId) {
            this.fromVpId = fromVpId;
            this.toVpId = toVpId;
        }
    }

    static final class Snapshot {
        final List<UseCaseSnap> useCases = new ArrayList<>();
        final List<ActorSnap> actors = new ArrayList<>();
        final List<LinkSnap> includes = new ArrayList<>();
        final List<LinkSnap> extendsLinks = new ArrayList<>();
        /** from = id dell'attore, to = id dello use case. */
        final List<LinkSnap> associations = new ArrayList<>();
    }

    private VpProjectReader() { }

    static Snapshot read(IProject project) {
        Snapshot snap = new Snapshot();
        if (project == null) return snap;

        for (Object o : elementsOfType(project, IModelElementFactory.MODEL_TYPE_USE_CASE)) {
            if (!(o instanceof IUseCase)) continue;
            IUseCase uc = (IUseCase) o;
            String desc = uc.getDescription();
            String parentId = null;
            try {
                IModelElement parent = uc.getParent();
                parentId = parent == null ? null : parent.getId();
            } catch (Exception ignored) { /* edizione di VP senza getParent: lo use case finira' nell'area del diagramma attivo */ }
            snap.useCases.add(new UseCaseSnap(uc.getId(), safe(uc.getName()), desc == null ? "" : desc, parentId));
        }
        for (Object o : elementsOfType(project, IModelElementFactory.MODEL_TYPE_ACTOR)) {
            if (o instanceof IActor) snap.actors.add(new ActorSnap(((IActor) o).getId(), safe(((IActor) o).getName())));
        }
        for (Object o : elementsOfType(project, IModelElementFactory.MODEL_TYPE_INCLUDE)) {
            LinkSnap l = link(o);
            if (l != null) snap.includes.add(l);
        }
        for (Object o : elementsOfType(project, IModelElementFactory.MODEL_TYPE_EXTEND)) {
            LinkSnap l = link(o);
            if (l != null) snap.extendsLinks.add(l);
        }
        for (Object o : elementsOfType(project, IModelElementFactory.MODEL_TYPE_ASSOCIATION)) {
            Object from = endOf(o, "getFrom");
            Object to = endOf(o, "getTo");
            if (from instanceof IActor && to instanceof IUseCase) {
                snap.associations.add(new LinkSnap(((IActor) from).getId(), ((IUseCase) to).getId()));
            } else if (from instanceof IUseCase && to instanceof IActor) {
                snap.associations.add(new LinkSnap(((IActor) to).getId(), ((IUseCase) from).getId()));
            }
        }
        return snap;
    }

    private static LinkSnap link(Object relationship) {
        Object from = endOf(relationship, "getFrom");
        Object to = endOf(relationship, "getTo");
        if (from instanceof IUseCase && to instanceof IUseCase) {
            return new LinkSnap(((IUseCase) from).getId(), ((IUseCase) to).getId());
        }
        return null;
    }

    /** Tutti gli elementi del tipo dato, a qualunque livello di package. Lista vuota se l'API non e' disponibile. */
    private static List<Object> elementsOfType(IProject project, String modelType) {
        List<Object> out = new ArrayList<>();
        for (String method : new String[]{"allLevelModelElementIterator", "modelElementIterator"}) {
            Method m = findPublicMethod(project.getClass(), method, String.class);
            if (m == null) continue;
            try {
                Object it = m.invoke(project, modelType);
                if (it instanceof Iterator) {
                    Iterator<?> iterator = (Iterator<?>) it;
                    while (iterator.hasNext()) out.add(iterator.next());
                    return out;
                }
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                System.out.println("[COSMIC AI] " + method + "(" + modelType + ") non riuscito: " + e);
            }
        }
        return out;
    }

    // ----------------------------------------------------------------------
    // Utility condivise (usate anche dal Service)
    // ----------------------------------------------------------------------

    /** Legge un estremo (getFrom/getTo) di una relazione VP senza dipendere dalla firma esatta dell'interfaccia. */
    static Object endOf(Object relationship, String getter) {
        if (relationship == null) return null;
        try {
            Method m = findPublicMethod(relationship.getClass(), getter);
            return m == null ? null : m.invoke(relationship);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            return null;
        }
    }

    static Method findPublicMethod(Class<?> start, String name, Class<?>... params) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Class<?> k = queue.poll();
            if (k == null || !seen.add(k)) continue;
            if (Modifier.isPublic(k.getModifiers())) {
                try {
                    return k.getMethod(name, params);
                } catch (NoSuchMethodException ignored) { }
            }
            if (k.getSuperclass() != null) queue.add(k.getSuperclass());
            queue.addAll(Arrays.asList(k.getInterfaces()));
        }
        return null;
    }

    /** Testo della specifica di uno use case letto da VP (senza il blocco COSMIC generato). */
    static String specificationOf(UseCaseSnap snap, String logicalId) {
        return UseCaseDescription.specificationOf(snap.description, logicalId);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
