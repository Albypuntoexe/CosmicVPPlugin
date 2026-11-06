package com.cosmic.vpplugin.persistence;

import com.vp.plugin.model.IProject;
import com.vp.plugin.model.factory.IModelElementFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * v4.0 - Persistenza del modello COSMIC (JSON) DENTRO il file .vpp, come Tagged Value del progetto.
 *
 * <h3>Formato</h3>
 * Il JSON viene diviso in blocchi da {@value #CHUNK_CHARS} caratteri (un valore enorme in un solo campo e'
 * fragile) e salvato in:
 * <ul>
 *   <li>{@code cosmic.ai.model} = "v1;chunks=N;len=L;crc=C" (intestazione con checksum CRC32 del JSON intero);</li>
 *   <li>{@code cosmic.ai.model.0 ... cosmic.ai.model.N-1} = i blocchi.</li>
 * </ul>
 * In lettura il checksum viene verificato: un JSON troncato o modificato a mano viene rifiutato (e NON usato) invece
 * di produrre un albero parziale.
 *
 * <h3>Uso dell'Open API (leggere con attenzione)</h3>
 * L'API dei Tagged Value cambia leggermente tra le versioni di VP e non e' presente nello stub con cui e' stato
 * compilato il plugin: per questo qui si usa la riflessione, SOLO per queste chiamate:
 * <pre>
 *   project.getTaggedValues()                       -> ITaggedValueContainer (o null)
 *   project.setTaggedValues(container)              (se il container non esisteva)
 *   IModelElementFactory.instance().createTaggedValueContainer()
 *   container.getTaggedValueByName(String)          (ripiego: taggedValueIterator())
 *   container.createTaggedValue()
 *   taggedValue.setName(String) / setValue(String) / getValueAsString()
 * </pre>
 * Se uno di questi metodi non esiste nella tua versione di VP, {@link StoreException} elenca i metodi "*Tagged*"
 * realmente disponibili sul progetto: basta sostituire la singola chiamata (e togliere la riflessione).
 * Tutti i metodi vanno chiamati sul thread grafico (EDT).
 */
public final class ProjectModelStore {

    static final String KEY_HEADER = "cosmic.ai.model";
    static final String KEY_CHUNK_PREFIX = "cosmic.ai.model.";
    static final int CHUNK_CHARS = 30_000;
    private static final String HEADER_VERSION = "v1";

    private ProjectModelStore() { }

    /** Errore di persistenza con un messaggio leggibile dall'utente. */
    public static final class StoreException extends Exception {
        private static final long serialVersionUID = 1L;
        public StoreException(String message) { super(message); }
        public StoreException(String message, Throwable cause) { super(message, cause); }
    }

    // ======================================================================
    // API pubblica
    // ======================================================================

    /** Scrive il JSON nei Tagged Value del progetto (sovrascrive la versione precedente). */
    public static void save(IProject project, String json) throws StoreException {
        if (project == null) throw new StoreException("nessun progetto aperto");
        Object container = ensureContainer(project);

        List<String> chunks = split(json);
        int previousChunks = readChunkCount(container);

        setValue(container, KEY_HEADER, HEADER_VERSION + ";chunks=" + chunks.size() + ";len=" + json.length() + ";crc=" + crc(json));
        for (int i = 0; i < chunks.size(); i++) setValue(container, KEY_CHUNK_PREFIX + i, chunks.get(i));
        for (int i = chunks.size(); i < previousChunks; i++) setValue(container, KEY_CHUNK_PREFIX + i, "");   // blocchi vecchi: svuotati
    }

    /**
     * Legge il JSON salvato.
     *
     * @return il JSON, oppure {@code null} se il progetto non contiene un modello COSMIC
     * @throws StoreException se i dati ci sono ma sono incompleti o danneggiati (checksum errato)
     */
    public static String load(IProject project) throws StoreException {
        if (project == null) return null;
        Object container = containerOrNull(project);
        if (container == null) return null;

        String header = getValue(container, KEY_HEADER);
        if (header == null || header.isBlank()) return null;

        int chunks = intField(header, "chunks");
        long len = longField(header, "len");
        String crc = stringField(header, "crc");
        if (chunks < 0 || len < 0 || crc == null) throw new StoreException("intestazione del modello COSMIC non valida: " + abbreviate(header));

        StringBuilder sb = new StringBuilder((int) Math.min(len, Integer.MAX_VALUE - 16));
        for (int i = 0; i < chunks; i++) {
            String part = getValue(container, KEY_CHUNK_PREFIX + i);
            if (part == null) throw new StoreException("blocco " + (i + 1) + "/" + chunks + " del modello COSMIC mancante");
            sb.append(part);
        }
        String json = sb.toString();
        if (json.length() != len || !crc(json).equals(crc)) {
            throw new StoreException("il modello COSMIC salvato nel progetto e' incompleto o danneggiato (checksum errato)");
        }
        return json;
    }

    /** Elimina il modello salvato (svuota i valori: l'API non garantisce la rimozione dei tagged value). */
    public static void clear(IProject project) throws StoreException {
        if (project == null) return;
        Object container = containerOrNull(project);
        if (container == null) return;
        int n = readChunkCount(container);
        setValue(container, KEY_HEADER, "");
        for (int i = 0; i < n; i++) setValue(container, KEY_CHUNK_PREFIX + i, "");
    }

    // ======================================================================
    // Container dei tagged value
    // ======================================================================

    private static Object containerOrNull(IProject project) throws StoreException {
        return call(project, "getTaggedValues", "il progetto");
    }

    private static Object ensureContainer(IProject project) throws StoreException {
        Object container = containerOrNull(project);
        if (container != null) return container;
        Object created = call(IModelElementFactory.instance(), "createTaggedValueContainer", "IModelElementFactory");
        if (created == null) throw new StoreException("createTaggedValueContainer() ha restituito null");
        call(project, "setTaggedValues", "il progetto", created);
        Object again = containerOrNull(project);
        return again != null ? again : created;
    }

    private static int readChunkCount(Object container) {
        try {
            String header = getValue(container, KEY_HEADER);
            return header == null ? 0 : Math.max(0, intField(header, "chunks"));
        } catch (StoreException e) {
            return 0;
        }
    }

    /** Valore del tagged value {@code name}, null se non esiste. */
    private static String getValue(Object container, String name) throws StoreException {
        Object tv = findTaggedValue(container, name);
        if (tv == null) return null;
        Object v = call(tv, "getValueAsString", "il tagged value");
        return v == null ? null : String.valueOf(v);
    }

    /** Crea o aggiorna il tagged value {@code name}. */
    private static void setValue(Object container, String name, String value) throws StoreException {
        Object tv = findTaggedValue(container, name);
        if (tv == null) {
            tv = call(container, "createTaggedValue", "il container dei tagged value");
            if (tv == null) throw new StoreException("createTaggedValue() ha restituito null");
            call(tv, "setName", "il tagged value", name);
        }
        call(tv, "setValue", "il tagged value", value);
    }

    private static Object findTaggedValue(Object container, String name) throws StoreException {
        if (hasMethod(container, "getTaggedValueByName", 1)) {
            return call(container, "getTaggedValueByName", "il container dei tagged value", name);
        }
        // Ripiego: scansione con l'iteratore.
        Object it = call(container, "taggedValueIterator", "il container dei tagged value");
        if (it instanceof Iterator) {
            Iterator<?> iterator = (Iterator<?>) it;
            while (iterator.hasNext()) {
                Object tv = iterator.next();
                Object n = call(tv, "getName", "il tagged value");
                if (name.equals(n)) return tv;
            }
        }
        return null;
    }

    // ======================================================================
    // Intestazione e checksum
    // ======================================================================

    static List<String> split(String json) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < json.length(); i += CHUNK_CHARS) {
            out.add(json.substring(i, Math.min(json.length(), i + CHUNK_CHARS)));
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    static String crc(String s) {
        CRC32 crc = new CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    private static String stringField(String header, String key) {
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(key)) return part.substring(eq + 1).trim();
        }
        return null;
    }

    private static int intField(String header, String key) {
        try {
            String v = stringField(header, key);
            return v == null ? -1 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long longField(String header, String key) {
        try {
            String v = stringField(header, key);
            return v == null ? -1 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }

    // ======================================================================
    // Riflessione
    // ======================================================================

    private static boolean hasMethod(Object target, String name, int paramCount) {
        return findMethod(target.getClass(), name, paramCount) != null;
    }

    /**
     * Invoca {@code target.name(args)}. Se il metodo non esiste lancia una {@link StoreException} che elenca i metodi
     * "*Tagged*" davvero presenti, per correggere in un attimo la singola chiamata.
     */
    private static Object call(Object target, String name, String what, Object... args) throws StoreException {
        Method m = findMethod(target.getClass(), name, args.length);
        if (m == null) {
            throw new StoreException("API Tagged Value non disponibile: " + what + " (" + target.getClass().getName()
                    + ") non ha il metodo " + name + "(" + args.length + " parametri). Metodi '*Tagged*' disponibili: "
                    + taggedMethodsOf(target.getClass()));
        }
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new StoreException(name + "() ha fallito: " + c, c);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new StoreException(name + "() non invocabile: " + e, e);
        }
    }

    /** Cerca un metodo pubblico (anche ereditato da un'interfaccia) con quel nome e numero di parametri. */
    private static Method findMethod(Class<?> start, String name, int paramCount) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Class<?> k = queue.poll();
            if (k == null || !seen.add(k)) continue;
            if (Modifier.isPublic(k.getModifiers())) {
                // Si preferisce il metodo con un parametro String se ce ne sono piu' (es. setValue(String) / setValue(Object)).
                Method best = null;
                for (Method m : k.getMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != paramCount) continue;
                    if (best == null || (paramCount == 1 && m.getParameterTypes()[0] == String.class)) best = m;
                }
                if (best != null) return best;
            }
            if (k.getSuperclass() != null) queue.add(k.getSuperclass());
            for (Class<?> i : k.getInterfaces()) queue.add(i);
        }
        return null;
    }

    private static String taggedMethodsOf(Class<?> start) {
        Set<String> names = new TreeSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Class<?> k = queue.poll();
            if (k == null || !seen.add(k)) continue;
            for (Method m : k.getMethods()) {
                if (m.getName().toLowerCase().contains("tagged")) names.add(m.getName());
            }
            if (k.getSuperclass() != null) queue.add(k.getSuperclass());
            for (Class<?> i : k.getInterfaces()) queue.add(i);
        }
        return names.isEmpty() ? "(nessuno)" : names.toString();
    }
}
