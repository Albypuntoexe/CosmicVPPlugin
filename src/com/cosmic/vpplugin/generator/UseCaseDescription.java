package com.cosmic.vpplugin.generator;

import com.cosmic.vpplugin.model.CosmicJsonModel.FunctionalProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.SubProcess;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * v4.0 - Struttura del campo Description/Specification di uno use case su Visual Paradigm.
 *
 * <pre>
 *   [UC-ID] Nome                       <- intestazione (solo use case generati dal documento)
 *   Riferimento nel documento: ...     <- idem
 *
 *   MAIN SCENARIO: 1) ... 2) ...       <- SPECIFICA: e' il testo che l'utente puo' modificare e che
 *   ALTERNATIVE FLOWS / EXCEPTIONS: ...   "Rimisura da VP" invia alla pipeline Micro-Loop
 *
 *   --- COSMIC Functional Processes (n) ---     <- da qui in poi il testo e' GENERATO dal plugin
 *   * FP [FP1] ...                                 e riscritto a ogni ricalcolo (non va modificato a mano)
 *   [COSMIC AI - VIOLAZIONE] ...                <- nota di violazione, anch'essa generata
 * </pre>
 *
 * Tenere separati i due blocchi e' cio' che permette alla Source of Truth di essere VP: quando si rimisura si legge
 * SOLO la specifica (mai l'output COSMIC generato in precedenza, che falserebbe la misura), e quando l'utente
 * corregge un data movement a mano il blocco generato viene riallineato senza toccare il suo testo.
 */
public final class UseCaseDescription {

    public static final String COSMIC_MARKER = "--- COSMIC Functional Processes";
    public static final String VIOLATION_MARKER = "[COSMIC AI - VIOLAZIONE]";
    private static final String REFERENCE_PREFIX = "Riferimento nel documento:";

    private UseCaseDescription() { }

    /** Descrizione completa alla creazione dello use case: specifica + blocco COSMIC. */
    public static String build(UseCase uc) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(uc.id).append("] ").append(uc.name).append("\n");
        if (uc.specification != null && !uc.specification.isBlank()) {
            sb.append(uc.specification).append("\n");
        }
        sb.append("\n");

        if (!uc.mainScenario.isEmpty()) {
            sb.append("MAIN SCENARIO:\n");
            int step = 1;
            for (String s : uc.mainScenario) {
                sb.append(step++).append(") ").append(s).append("\n");
            }
        }
        if (!uc.exceptions.isEmpty()) {
            sb.append("\nALTERNATIVE FLOWS / EXCEPTIONS:\n");
            for (String e : uc.exceptions) {
                sb.append("- ").append(e).append("\n");
            }
        }
        String block = cosmicBlock(uc);
        if (!block.isEmpty()) {
            sb.append("\n").append(block);
        }
        return sb.toString();
    }

    /** Il blocco generato ("--- COSMIC Functional Processes ..."), "" se lo use case non ha sotto-processi. */
    public static String cosmicBlock(UseCase uc) {
        boolean any = false;
        for (FunctionalProcess fp : uc.functionalProcesses) {
            if (!fp.subProcesses.isEmpty()) any = true;
        }
        if (!any) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(COSMIC_MARKER).append(" (").append(uc.functionalProcesses.size()).append(") ---\n");
        for (FunctionalProcess fp : uc.functionalProcesses) {
            sb.append("* FP [").append(fp.fpId).append("] ").append(fp.fpName).append("\n");
            sb.append("  Triggering Event: ").append(fp.triggeringEvent).append("\n");
            for (SubProcess sp : fp.subProcesses) {
                sb.append("    ").append(sp.step).append(". ").append(sp.description);
                if (sp.unmeasured) {
                    sb.append(" [NON MISURATA]");
                } else if (sp.dataMovementType != null) {
                    sb.append(" [").append(sp.dataMovementType)
                      .append(" - DG:").append(sp.dataGroup)
                      .append(" - OOI:").append(sp.objectOfInterest).append("]");
                } else {
                    sb.append(" [No data movement]");
                }
                if (sp.manuallyEdited) sb.append(" (modificato a mano)");
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Sostituisce tutto cio' che segue la specifica (vecchio blocco COSMIC e nota di violazione) con il blocco
     * aggiornato di {@code uc}. Il testo della specifica resta identico, carattere per carattere.
     */
    public static String withCosmicBlock(String description, UseCase uc) {
        String head = headOf(description);
        String block = cosmicBlock(uc);
        if (block.isEmpty()) return head;
        return (head.isEmpty() ? "" : head + "\n\n") + block.stripTrailing();
    }

    /** Aggiunge la nota di violazione in coda (da usare dopo {@link #withCosmicBlock}). */
    public static String withViolationNote(String description, String violationMessage) {
        return (description == null ? "" : description.stripTrailing())
                + "\n\n" + VIOLATION_MARKER + " " + violationMessage;
    }

    /**
     * Testo della specifica da misurare: la descrizione senza blocco COSMIC, senza nota di violazione e senza le due
     * righe di intestazione generate dal plugin ("[ucId] Nome" e "Riferimento nel documento: ...").
     *
     * @param ucId id logico dello use case (per riconoscere l'intestazione generata); null se sconosciuto
     */
    public static String specificationOf(String description, String ucId) {
        String head = headOf(description).replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder();
        String[] lines = head.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == 0 && ucId != null && line.trim().startsWith("[" + ucId + "]")) continue;
            if (line.trim().startsWith(REFERENCE_PREFIX)) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }

    /**
     * Impronta di (nome, specifica) con whitespace normalizzato: due letture dello stesso testo danno la stessa impronta
     * anche se VP cambia i ritorni a capo. Serve a "Rimisura da VP" per sapere quali use case sono cambiati.
     */
    public static String fingerprint(String name, String specification) {
        String normalized = (name == null ? "" : name.strip()) + "\u0000"
                + (specification == null ? "" : specification.strip().replaceAll("\\s+", " "));
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 10; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(normalized.hashCode());      // non puo' accadere: SHA-1 e' garantito dalla JVM
        }
    }

    /** Parte della descrizione prima del primo marcatore generato (spazi finali rimossi). */
    private static String headOf(String description) {
        if (description == null) return "";
        int cut = description.length();
        int a = description.indexOf(COSMIC_MARKER);
        int b = description.indexOf(VIOLATION_MARKER);
        if (a >= 0) cut = Math.min(cut, a);
        if (b >= 0) cut = Math.min(cut, b);
        return description.substring(0, cut).stripTrailing();
    }
}
