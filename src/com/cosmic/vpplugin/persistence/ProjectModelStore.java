package com.cosmic.vpplugin.persistence;

import com.vp.plugin.model.IProject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Gestisce la persistenza del modello COSMIC in un file JSON parallelo (Sidecar) al progetto .vpp.
 *
 * Sostituisce l'uso dei TaggedValues (non sempre disponibili nella Community Edition di VP).
 * Genera automaticamente un file es. "MioProgetto.cosmic.json" nella stessa cartella di "MioProgetto.vpp".
 */
public class ProjectModelStore {

    public static class StoreException extends Exception {
        private static final long serialVersionUID = 1L;
        public StoreException(String message) { super(message); }
        public StoreException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Determina il percorso del file .cosmic.json affiancato al file .vpp corrente.
     */
    private static File getSidecarFile(IProject project) throws StoreException {
        if (project == null) {
            throw new StoreException("Nessun progetto aperto in Visual Paradigm.");
        }

        File projectFile = null;
        try {
            // Usiamo la reflection per chiamare "getFile" o "getProjectFile" in modo sicuro
            // e aggirare le differenze di firma nelle varie edizioni dell'openapi.jar
            try {
                java.lang.reflect.Method getFileMethod = project.getClass().getMethod("getFile");
                projectFile = (File) getFileMethod.invoke(project);
            } catch (Exception e) {
                try {
                    java.lang.reflect.Method getProjectFileMethod = project.getClass().getMethod("getProjectFile");
                    projectFile = (File) getProjectFileMethod.invoke(project);
                } catch (Exception e2) {
                    throw new StoreException("Impossibile determinare il percorso del file .vpp tramite le API di Visual Paradigm.");
                }
            }
        } catch (Exception ex) {
            throw new StoreException("Errore nel recupero del percorso del progetto: " + ex.getMessage(), ex);
        }

        if (projectFile == null) {
            throw new StoreException("Il progetto VP non è ancora stato salvato. Esegui File -> Save Project per abilitare la persistenza.");
        }

        String path = projectFile.getAbsolutePath();
        if (path.toLowerCase().endsWith(".vpp")) {
            path = path.substring(0, path.length() - 4);
        }
        return new File(path + ".cosmic.json");
    }

    /**
     * Salva l'intera stringa JSON del modello sul disco.
     */
    public static void save(IProject project, String json) throws StoreException {
        try {
            File sidecar = getSidecarFile(project);
            Files.write(sidecar.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (StoreException se) {
            throw se; // Rilancia senza imbustare se è un errore custom noto
        } catch (Exception e) {
            throw new StoreException("Errore di I/O nel salvataggio del file .cosmic.json: " + e.getMessage(), e);
        }
    }

    /**
     * Carica e restituisce la stringa JSON dal file su disco, se esiste.
     */
    public static String load(IProject project) throws StoreException {
        try {
            File sidecar = getSidecarFile(project);
            if (!sidecar.exists()) {
                return null; // Nessun salvataggio precedente trovato, stato pulito
            }
            return new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8);
        } catch (StoreException se) {
            // Se stiamo caricando ma il progetto VP è appena stato creato e non ancora salvato, ignoriamo silenziosamente
            if (se.getMessage().contains("non è ancora stato salvato")) {
                return null;
            }
            throw se;
        } catch (Exception e) {
            throw new StoreException("Errore di I/O nella lettura del file .cosmic.json: " + e.getMessage(), e);
        }
    }
}