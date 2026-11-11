package com.cosmic.vpplugin.service;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.model.IProject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * v5.1 - Ricerca nel database del progetto VP gli elementi MODEL di un certo tipo, a qualunque livello di package.
 *
 * Serve al generatore per riusare un elemento che esiste gia' (es. l'{@code IActor} "Hotel manager" creato in una
 * generazione precedente o a mano) invece di duplicarlo. Come in {@code VpProjectReader} l'enumerazione passa da
 * reflection ({@code allLevelModelElementIterator} con ripiego su {@code modelElementIterator}): la firma esatta
 * varia tra le edizioni di VP, e se nessuna e' disponibile la lista e' semplicemente vuota (il chiamante allora
 * crea l'elemento: comportamento sicuro, al peggio un duplicato come nella v5.0).
 */
public final class VpModelLookup {

    private VpModelLookup() { }

    /** Tutti gli elementi del tipo dato ({@code IModelElementFactory.MODEL_TYPE_*}); lista vuota se l'API non risponde. */
    public static List<Object> elementsOfType(String modelType) {
        List<Object> out = new ArrayList<>();
        IProject project;
        try {
            project = ApplicationManager.instance().getProjectManager().getProject();
        } catch (RuntimeException e) {
            return out;
        }
        if (project == null) {
            return out;
        }
        for (String method : new String[]{"allLevelModelElementIterator", "modelElementIterator"}) {
            try {
                Method m = VpProjectReader.findPublicMethod(project.getClass(), method, String.class);   // risale alle interfacce pubbliche
                if (m == null) {
                    continue;
                }
                Object it = m.invoke(project, modelType);
                if (it instanceof Iterator) {
                    Iterator<?> iterator = (Iterator<?>) it;
                    while (iterator.hasNext()) {
                        out.add(iterator.next());
                    }
                    return out;
                }
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                System.out.println("[COSMIC AI] " + method + "(" + modelType + ") non riuscito: " + e);
            }
        }
        return out;
    }
}
