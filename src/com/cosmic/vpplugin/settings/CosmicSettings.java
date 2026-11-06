package com.cosmic.vpplugin.settings;

import java.net.URI;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Impostazioni di connessione all'LLM, salvate con {@link java.util.prefs.Preferences} (per utente del sistema
 * operativo). Sostituisce i vecchi parametri {@code -Dcosmic.llm.*} da passare all'avvio di Visual Paradigm.
 *
 * <ul>
 *   <li>Le impostazioni vengono rilette a OGNI richiesta HTTP ({@link #load()}): una modifica fatta nella tab
 *       "Impostazioni" vale dalla chiamata successiva, senza riavviare l'IDE.</li>
 *   <li>Il nodo e' indicato con un percorso esplicito e non con {@code userNodeForPackage}: non dipende dal
 *       class loader con cui VP carica il plugin.</li>
 *   <li><b>Sicurezza:</b> la API key e' salvata in chiaro nelle preferenze utente del sistema operativo
 *       (registro di Windows / plist di macOS / ~/.java). E' il comportamento standard di {@code Preferences}:
 *       va bene per un LM Studio locale o una chiave personale, non per segreti condivisi.</li>
 * </ul>
 *
 * La classe non dipende da Swing ne' dall'Open API di VP.
 */
public final class CosmicSettings {

    public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:1234/v1/chat/completions";
    public static final String DEFAULT_MODEL = "openai/gpt-oss-20b";
    /** Un modello locale da 20B con prompt few-shot da ~3k token + ragionamento puo' impiegare minuti per chiamata. */
    public static final int DEFAULT_TIMEOUT_SEC = 900;
    public static final int MIN_TIMEOUT_SEC = 5;
    public static final int MAX_TIMEOUT_SEC = 7200;

    private static final String NODE_PATH = "com/cosmic/vpplugin/llm";
    private static final String KEY_ENDPOINT = "endpointUrl";
    private static final String KEY_MODEL = "modelName";
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_TIMEOUT = "timeoutSec";

    private CosmicSettings() { }

    /** Fotografia immutabile delle impostazioni correnti. */
    public static final class Snapshot {
        public final String endpointUrl;
        public final String modelName;
        /** Mai null; stringa vuota = nessuna autenticazione. */
        public final String apiKey;
        public final int timeoutSec;

        public Snapshot(String endpointUrl, String modelName, String apiKey, int timeoutSec) {
            this.endpointUrl = endpointUrl;
            this.modelName = modelName;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.timeoutSec = timeoutSec;
        }

        public boolean hasApiKey() {
            return !apiKey.trim().isEmpty();
        }
    }

    private static Preferences node() {
        return Preferences.userRoot().node(NODE_PATH);
    }

    /** Legge le impostazioni; per ogni valore mancante o non valido usa il default. Non lancia mai eccezioni. */
    public static Snapshot load() {
        String endpoint = DEFAULT_ENDPOINT;
        String model = DEFAULT_MODEL;
        String key = "";
        int timeout = DEFAULT_TIMEOUT_SEC;
        try {
            Preferences p = node();
            endpoint = nonBlankOr(p.get(KEY_ENDPOINT, DEFAULT_ENDPOINT), DEFAULT_ENDPOINT);
            model = nonBlankOr(p.get(KEY_MODEL, DEFAULT_MODEL), DEFAULT_MODEL);
            key = p.get(KEY_API_KEY, "");
            timeout = clampTimeout(p.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_SEC));
        } catch (RuntimeException e) {
            // Preferences non disponibile (ambiente ristretto): si lavora con i default.
            System.out.println("[COSMIC AI] Preferences non leggibili, uso i default: " + e);
        }
        return new Snapshot(endpoint.trim(), model.trim(), key, timeout);
    }

    /**
     * Valida e salva. L'URL viene normalizzato con {@link #normalizeEndpoint(String)}.
     *
     * @return l'URL effettivamente salvato (puo' differire da quello digitato)
     * @throws IllegalArgumentException con un messaggio per l'utente se un campo non e' valido
     */
    public static String save(String endpointUrl, String modelName, String apiKey, int timeoutSec) {
        String endpoint = normalizeEndpoint(endpointUrl);   // lancia IllegalArgumentException se non valido
        String model = modelName == null ? "" : modelName.trim();
        if (model.isEmpty()) {
            throw new IllegalArgumentException("Il nome del modello non puo' essere vuoto.");
        }
        if (timeoutSec < MIN_TIMEOUT_SEC || timeoutSec > MAX_TIMEOUT_SEC) {
            throw new IllegalArgumentException("Il timeout deve essere tra " + MIN_TIMEOUT_SEC + " e " + MAX_TIMEOUT_SEC + " secondi.");
        }
        try {
            Preferences p = node();
            p.put(KEY_ENDPOINT, endpoint);
            p.put(KEY_MODEL, model);
            String key = apiKey == null ? "" : apiKey.trim();
            if (key.isEmpty()) p.remove(KEY_API_KEY); else p.put(KEY_API_KEY, key);
            p.putInt(KEY_TIMEOUT, timeoutSec);
            p.flush();
        } catch (BackingStoreException | RuntimeException e) {
            throw new IllegalArgumentException("Impossibile salvare le preferenze: " + e.getMessage(), e);
        }
        return endpoint;
    }

    /** Ripristina i valori di default (la API key viene cancellata). */
    public static void resetToDefaults() {
        try {
            Preferences p = node();
            p.remove(KEY_ENDPOINT);
            p.remove(KEY_MODEL);
            p.remove(KEY_API_KEY);
            p.remove(KEY_TIMEOUT);
            p.flush();
        } catch (BackingStoreException | RuntimeException e) {
            System.out.println("[COSMIC AI] Reset preferenze non riuscito: " + e);
        }
    }

    /**
     * Controlla che l'URL sia http(s) con host. Se il percorso e' vuoto, "/" o "/v1" completa con
     * {@code /v1/chat/completions} (es. "http://127.0.0.1:1234" -> endpoint OpenAI-compatibile di LM Studio).
     */
    public static String normalizeEndpoint(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("L'URL dell'endpoint non puo' essere vuoto.");
        }
        URI uri;
        try {
            uri = URI.create(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL non valido: " + t);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("L'URL deve iniziare con http:// o https:// e contenere un host (es. " + DEFAULT_ENDPOINT + ").");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String stripped = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (stripped.isEmpty() || stripped.equals("/v1")) {
            String base = t.replaceFirst("[?#].*$", "");
            base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            if (stripped.equals("/v1")) base = base.substring(0, base.length() - 3);
            return base + "/v1/chat/completions";
        }
        return t;
    }

    private static int clampTimeout(int v) {
        return Math.max(MIN_TIMEOUT_SEC, Math.min(MAX_TIMEOUT_SEC, v));
    }

    private static String nonBlankOr(String v, String fallback) {
        return v == null || v.trim().isEmpty() ? fallback : v;
    }
}
