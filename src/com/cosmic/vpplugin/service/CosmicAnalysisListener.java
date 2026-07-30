package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;

/**
 * Contratto tra {@link CosmicAiService} (che NON conosce alcuna classe Swing)
 * e la UI che di volta in volta desidera osservare l'analisi in corso.
 *
 * Motivazione architetturale: separare il "motore" (chiamata LLM, parsing,
 * generazione diagramma, calcolo COSMIC) dalla sua presentazione permette,
 * senza toccare {@link CosmicAiService}, di:
 *  - sostituire il dialog attuale con un altro tipo di vista in futuro;
 *  - aggiungere un secondo osservatore (es. il futuro pannello di chat, o un
 *    log persistente su file) senza duplicare la logica di business.
 *
 * Tutti i metodi vengono invocati DA {@link CosmicAiService} SEMPRE
 * sull'Event Dispatch Thread (e' compito del Service, non dell'implementore
 * di questa interfaccia, garantirlo): chi implementa questa interfaccia puo'
 * quindi aggiornare componenti Swing direttamente, senza ulteriori
 * SwingUtilities.invokeLater.
 *
 * AGGIORNAMENTO v2.0 (Epic 3): {@link #onScopeChanged} e' un metodo
 * DEFAULT (no-op) apposta, per non rompere binariamente eventuali
 * implementazioni esistenti dell'interfaccia scritte prima di questa
 * revisione (compatibilita' additiva).
 */
public interface CosmicAnalysisListener {

    /** Una riga di log testuale (progress, esiti intermedi, errori non fatali). */
    void onLog(String message);

    /** Chiamato quando una richiesta (LLM o locale) e' in corso/terminata: utile per abilitare/disabilitare i controlli e mostrare/nascondere un indicatore di progresso. */
    void onBusyStateChanged(boolean busy);

    /** Analisi completata con successo: diagramma generato e CFP calcolati. */
    void onAnalysisCompleted(CosmicJsonModel model, CosmicReport report);

    /** Analisi fallita (errore di rete, JSON non valido, errore Open API...). */
    void onAnalysisFailed(Throwable error);

    /**
     * Epic 3 - Multi-Diagramma e Context-Awareness: notificato quando il
     * diagramma attivo in Visual Paradigm cambia (o quando il suo contenuto
     * viene ricalcolato). {@code diagramName} e' null se nessun diagramma
     * e' a fuoco. {@code scopedReport} contiene i CFP dei soli Use Case
     * disegnati su quel diagramma; {@code projectTotalReport} e' sempre il
     * Totale Progetto completo, cosi' la UI puo' mostrare entrambi senza
     * mai "perdere di vista" il totale complessivo.
     */
    default void onScopeChanged(String diagramName, CosmicReport scopedReport, CosmicReport projectTotalReport) {
        // no-op di default: implementazioni pre-v2.0 continuano a compilare/funzionare inalterate.
    }
}
