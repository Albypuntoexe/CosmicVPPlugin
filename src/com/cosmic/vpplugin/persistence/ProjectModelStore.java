package com.cosmic.vpplugin.persistence;

import com.vp.plugin.model.IProject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * v4.1 - Persistenza del modello COSMIC in un file "sidecar" accanto al progetto:
 * {@code MioProgetto.vpp} -> {@code MioProgetto.cosmic.json}.
 *
 * <h3>Perche' un file e non i Tagged Value</h3>
 * Nella tua edizione di Visual Paradigm {@code IProject} non espone alcun metodo "*Tagged*" (lo ha provato il
 * log: "Metodi '*Tagged*' disponibili: (nessuno)"), quindi non c'e' modo di scrivere dati dentro il .vpp tramite
 * l'Open API. Il sidecar e' il meccanismo verificato sul campo: l'albero COSMIC si ripristina dal file.
 * <b>Conseguenza pratica:</b> se sposti o copi il .vpp, sposta/copia anche il .cosmic.json.
 *
 * <h3>Contenuto</h3>
 * Il JSON e' prodotto da {@code CosmicModelSerializer}: oltre al modello (use case, FP, data movement, correzioni
 * manuali) contiene i REQUISITI ORIGINALI estratti dal documento, necessari a "Valida Modello vs Requisiti".
 *
 * <h3>Robustezza</h3>
 * <ul>
 *   <li>scrittura atomica (file temporaneo + rename): un crash a meta' scrittura non corrompe il salvataggio precedente;</li>
 *   <li>percorso del .vpp ricavato per riflessione da piu' nomi di metodo possibili (cambiano tra le edizioni di VP);</li>
 *   <li>progetto non ancora salvato su disco: {@link #save} lancia {@link NotSavedException} (il Service riprova
 *       appena VP notifica il salvataggio), {@link #load} restituisce null.</li>
 * </ul>
 */
public final class ProjectModelStore {

    public static final String SIDECAR_SUFFIX = ".cosmic.json";

    /** Nomi dei metodi (senza parametri) con cui le varie edizioni dell'Open API espongono il file del progetto. */
    private static final String[] PATH_METHODS = {
            "getFile", "getProjectFile", "getFilePath", "getProjectPath", "getProjectFilePath", "getPath", "getFileName"};

    private ProjectModelStore() { }

    /** Errore di persistenza con un messaggio leggibile dall'utente. */
    public static class StoreException extends Exception {
        private static final long serialVersionUID = 1L;
        public StoreException(String message) { super(message); }
        public StoreException(String message, Throwable cause) { super(message, cause); }
    }

    /** Il progetto non ha ancora un file .vpp su disco (mai salvato): non c'e' dove affiancare il sidecar. */
    public static final class NotSavedException extends StoreException {
        private static final long serialVersionUID = 1L;
        public NotSavedException() {
            super("il progetto Visual Paradigm non e' ancora stato salvato su disco: il modello COSMIC verra' scritto "
                    + "automaticamente al primo salvataggio (File > Save Project).");
        }
    }

    // ======================================================================
    // API pubblica
    // ======================================================================

    /** Scrive il JSON nel sidecar del progetto (scrittura atomica). */
    public static void save(IProject project, String json) throws StoreException {
        if (json == null) throw new StoreException("Nessun contenuto da salvare.");
        Path target = sidecarOf(project).toPath();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            throw new StoreException("scrittura di '" + target + "' non riuscita: " + e.getMessage(), e);
        }
    }

    /**
     * @return il JSON salvato, oppure null se non c'e' nulla da ripristinare (nessun sidecar, o progetto mai salvato)
     * @throws StoreException se il file esiste ma non e' leggibile
     */
    public static String load(IProject project) throws StoreException {
        File sidecar;
        try {
            sidecar = sidecarOf(project);
        } catch (NotSavedException e) {
            return null;
        }
        if (!sidecar.isFile()) return null;
        try {
            return new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StoreException("lettura di '" + sidecar + "' non riuscita: " + e.getMessage(), e);
        }
    }

    /** Elimina il sidecar (se esiste). */
    public static void clear(IProject project) throws StoreException {
        try {
            Files.deleteIfExists(sidecarOf(project).toPath());
        } catch (NotSavedException ignored) {
            // niente file, niente da cancellare
        } catch (IOException e) {
            throw new StoreException("cancellazione del sidecar non riuscita: " + e.getMessage(), e);
        }
    }

    /** Percorso del sidecar, per i messaggi all'utente. */
    public static String locationOf(IProject project) {
        try {
            return sidecarOf(project).getAbsolutePath();
        } catch (StoreException e) {
            return null;
        }
    }

    // ======================================================================
    // Percorso del sidecar
    // ======================================================================

    static File sidecarOf(IProject project) throws StoreException {
        if (project == null) throw new StoreException("nessun progetto aperto in Visual Paradigm.");
        File vpp = projectFileOf(project);
        if (vpp == null) throw new NotSavedException();
        return sidecarFor(vpp);
    }

    /** "dir/Nome.vpp" -> "dir/Nome.cosmic.json" (qualunque altra estensione viene sostituita, assente = aggiunta). */
    static File sidecarFor(File vpp) {
        String name = vpp.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(vpp.getAbsoluteFile().getParentFile(), base + SIDECAR_SUFFIX);
    }

    /**
     * File .vpp del progetto, o null se non e' (ancora) su disco. Prova i metodi di {@link #PATH_METHODS}, che possono
     * restituire File o String, cercandoli prima nelle interfacce pubbliche (la classe concreta di VP e' offuscata e
     * non pubblica: invocare dal Method della classe darebbe IllegalAccessException).
     */
    static File projectFileOf(IProject project) throws StoreException {
        boolean anyMethod = false;
        for (String name : PATH_METHODS) {
            Method m = findNoArgMethod(project.getClass(), name);
            if (m == null) continue;
            anyMethod = true;
            Object value;
            try {
                try { m.setAccessible(true); } catch (RuntimeException ignored) { /* modulo chiuso: si prova comunque */ }
                value = m.invoke(project);
            } catch (InvocationTargetException | IllegalAccessException | RuntimeException e) {
                continue;
            }
            File f = null;
            if (value instanceof File) f = (File) value;
            else if (value instanceof String && !((String) value).trim().isEmpty()) f = new File(((String) value).trim());
            if (f != null && !f.getName().isEmpty() && !f.isDirectory()) return f;
        }
        if (!anyMethod) {
            throw new StoreException("l'Open API di Visual Paradigm non espone il percorso del progetto (provati: "
                    + String.join(", ", PATH_METHODS) + ").");
        }
        return null;   // metodo presente ma restituisce null/vuoto: progetto non ancora salvato
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(type);
        Method fallback = null;
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (c == null || !seen.add(c)) continue;
            try {
                Method m = c.getMethod(name);
                if (java.lang.reflect.Modifier.isPublic(m.getDeclaringClass().getModifiers())) return m;   // metodo dichiarato in un tipo pubblico: invocabile
                if (fallback == null) fallback = m;
            } catch (NoSuchMethodException ignored) { /* si cerca nel supertipo */ }
            if (c.getSuperclass() != null) queue.add(c.getSuperclass());
            for (Class<?> i : c.getInterfaces()) queue.add(i);
        }
        return fallback;
    }
}
