package com.cosmic.vpplugin.document;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Estrazione testo da PDF tramite Apache PDFBox, caricato in modo
 * DINAMICO e ISOLATO a runtime (nessuna dipendenza a compile-time, nessuna
 * voce aggiunta al classpath dichiarato del plugin in plugin.xml, come
 * richiesto).
 *
 * PERCHE' NON POSSIAMO FARE COME PER IL DOCX (Epic 1, vedi {@link DocxDocumentParser}):
 * un PDF non e' un contenitore XML in chiaro. Il testo e' quasi sempre
 * dentro stream compressi (tipicamente FlateDecode/zlib) all'interno di un
 * grafo di oggetti con offset e cross-reference table: implementarne un
 * parser minimale "a mano" (come MiniJsonParser fa per il JSON) richiederebbe
 * di reimplementare in pratica un sotto-insieme non banale della spec PDF
 * (inflate, tokenizzazione dei content stream, mappatura font/encoding per
 * i glifi -> caratteri Unicode). Il rischio di introdurre bug di
 * misurazione (testo troncato/mancante) e' troppo alto per un tool che poi
 * userà quel testo come input di misura COSMIC: qui la scelta corretta e'
 * appoggiarsi a una libreria matura, non reinventarla.
 *
 * COME INCLUDERE PDFBOX IN SICUREZZA (senza toccare plugin.xml):
 * 1) Scarica il jar "app" (self-contained, un solo file, tutte le
 *    dipendenze incluse) da https://pdfbox.apache.org/download.cgi,
 *    es. "pdfbox-app-3.0.3.jar".
 * 2) Copialo nella cartella "lib" accanto al jar del plugin, cioe':
 *    <install-VP>/plugins/COSMIC_AI_VP_Plugin/lib/pdfbox-app-3.0.3.jar
 *    (la sotto-cartella "lib" va creata una sola volta; il nome del jar
 *    puo' contenere qualunque versione, viene individuato per prefisso).
 * 3) Non serve editare plugin.xml: questa classe, alla prima richiesta di
 *    parsing di un PDF, cerca da sola quel jar e lo carica con un
 *    {@link URLClassLoader} figlio dedicato, isolato dal resto del plugin
 *    e da qualunque libreria che Visual Paradigm stesso usi internamente
 *    (evita quindi conflitti di versione, che erano il problema originale
 *    discusso nel Javadoc di MiniJsonParser). Le classi PDFBox vengono
 *    invocate solo via reflection: se il jar non c'e', il metodo fallisce
 *    con un IOException leggibile invece di un NoClassDefFoundError
 *    silenzioso a runtime.
 *
 * Se preferisci evitare del tutto la dipendenza runtime, l'alternativa e'
 * chiedere all'utente di esportare/convertire il PDF in .docx o .txt prima
 * di caricarlo (il file chooser del dialog accetta comunque entrambi): in
 * tal caso questa classe semplicemente non trovera' mai il jar e il dialog
 * mostrera' l'errore descritto sopra con le istruzioni di installazione.
 */
public final class PdfDocumentParser implements DocumentParser {

    private static final String PDFBOX_JAR_PREFIX = "pdfbox-app-";
    private static volatile ClassLoader cachedPdfBoxLoader;

    @Override
    public boolean supports(File file) {
        return file.getName().toLowerCase().endsWith(".pdf");
    }

    @Override
    public String extractText(File file) throws IOException {
        ClassLoader loader = resolvePdfBoxClassLoader();
        try {
            Class<?> loaderClass = Class.forName("org.apache.pdfbox.Loader", true, loader);
            Class<?> documentClass = Class.forName("org.apache.pdfbox.pdmodel.PDDocument", true, loader);
            Class<?> stripperClass = Class.forName("org.apache.pdfbox.text.PDFTextStripper", true, loader);

            Method loadPdf = loaderClass.getMethod("loadPDF", File.class);
            Object document = loadPdf.invoke(null, file);
            try {
                Object stripper = stripperClass.getDeclaredConstructor().newInstance();
                Method getText = stripperClass.getMethod("getText", documentClass);
                String text = (String) getText.invoke(stripper, document);
                if (text == null || text.isBlank()) {
                    throw new IOException("PDFBox non ha estratto testo da '" + file.getName()
                            + "' (probabilmente e' un PDF scansionato/immagine: servirebbe OCR, non supportato).");
                }
                return text.trim();
            } finally {
                Method close = documentClass.getMethod("close");
                close.invoke(document);
            }
        } catch (IOException io) {
            throw io;
        } catch (ReflectiveOperationException reflectiveEx) {
            throw new IOException("Errore durante l'invocazione di PDFBox (versione del jar incompatibile?): "
                    + reflectiveEx, reflectiveEx);
        }
    }

    /**
     * Individua e carica una sola volta (cache statica) il jar
     * pdfbox-app-*.jar dalla cartella lib/ accanto al jar del plugin
     * corrente. La cartella del plugin viene derivata dalla posizione del
     * .jar in esecuzione (protection domain di questa stessa classe): e'
     * l'unico modo per trovarla senza dipendere da percorsi assoluti
     * hardcoded o da modifiche a plugin.xml.
     */
    private static synchronized ClassLoader resolvePdfBoxClassLoader() throws IOException {
        if (cachedPdfBoxLoader != null) {
            return cachedPdfBoxLoader;
        }
        File pluginDir = locatePluginDirectory();
        File libDir = new File(pluginDir, "lib");
        File jar = findPdfBoxJar(libDir);
        if (jar == null) {
            throw new IOException("Apache PDFBox non trovato. Copia il jar 'pdfbox-app-*.jar' "
                    + "(scaricabile da https://pdfbox.apache.org/download.cgi) nella cartella:\n"
                    + libDir.getAbsolutePath()
                    + "\ne riprova (non serve riavviare Visual Paradigm, viene caricato al volo).");
        }
        try {
            URL jarUrl = jar.toURI().toURL();
            // Parent = null: classloader isolato, non "eredita" nulla dal
            // classloader del plugin, per evitare collisioni di versione.
            cachedPdfBoxLoader = new URLClassLoader(new URL[]{jarUrl}, null);
            return cachedPdfBoxLoader;
        } catch (MalformedURLException e) {
            throw new IOException("Percorso del jar PDFBox non valido: " + jar, e);
        }
    }

    private static File findPdfBoxJar(File libDir) {
        if (!libDir.isDirectory()) {
            return null;
        }
        try (Stream<Path> files = Files.list(libDir.toPath())) {
            return files
                    .map(Path::toFile)
                    .filter(f -> f.getName().toLowerCase().startsWith(PDFBOX_JAR_PREFIX)
                            && f.getName().toLowerCase().endsWith(".jar"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static File locatePluginDirectory() {
        try {
            File thisJarOrClassesDir = new File(
                    PdfDocumentParser.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // thisJarOrClassesDir e' il .jar del plugin: la sua cartella
            // padre e' la "plugin directory" in cui vive anche lib/.
            return thisJarOrClassesDir.isFile() ? thisJarOrClassesDir.getParentFile() : thisJarOrClassesDir;
        } catch (Exception e) {
            // Fallback ragionevole: working directory del processo VP.
            return new File(".");
        }
    }
}
