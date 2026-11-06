package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.calculator.CosmicCalculator.UseCaseReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;
import com.cosmic.vpplugin.model.CosmicJsonModel.UseCase;

/**
 * Canale Service -> UI. CONTRATTO DI THREADING (v3.0): il Service invoca TUTTI i metodi sul thread grafico
 * (Event Dispatch Thread), quindi le implementazioni possono toccare direttamente i componenti Swing.
 */
public interface CosmicAnalysisListener {

    void onLog(String message);

    /**
     * v4.0 - Chiamato PRIMA di {@link #onBusyStateChanged(boolean) onBusyStateChanged(true)} quando parte una
     * misura. {@code incremental == true} per "Rimisura da VP": l'albero esistente va conservato e aggiornato use
     * case per use case; {@code false} per una nuova analisi del documento (albero da azzerare).
     */
    default void onRunStarted(boolean incremental) {}

    void onBusyStateChanged(boolean busy);

    /**
     * Avanzamento dell'analisi.
     *
     * @param current         numero di use case GIA' completati
     * @param total           numero totale di use case individuati
     * @param stepDescription testo descrittivo (use case corrente, step 1/2 o 2/2, tempo stimato)
     */
    default void onProgress(int current, int total, String stepDescription) {}

    /**
     * v3.0 - Rendering incrementale: chiamato subito dopo che UN use case e' stato misurato dall'LLM, aggiunto al
     * modello, disegnato sul diagramma di Visual Paradigm e conteggiato nel report di progetto.
     */
    default void onUseCaseProcessed(UseCase useCase, UseCaseReport useCaseReport, CosmicReport projectReport,
                                    int processed, int total) {}

    /**
     * v3.0 - Il modello e' cambiato dopo l'analisi (rename/aggiunta/rimozione fatta dall'utente su VP) e i CFP sono
     * stati ricalcolati. Di default logga il motivo e ripubblica il modello come se l'analisi fosse completata.
     */
    default void onModelSynchronized(CosmicJsonModel model, CosmicReport report, String reason) {
        onLog(reason);
        onAnalysisCompleted(model, report);
    }

    /**
     * v4.0 - Il progetto attivo e' cambiato e quello nuovo non contiene un modello COSMIC salvato: l'albero e le
     * tabelle vanno svuotati (mai mostrare dati di un altro progetto).
     */
    default void onModelCleared() {}

    void onAnalysisCompleted(CosmicJsonModel model, CosmicReport report);

    void onAnalysisFailed(Throwable error);

    default void onScopeChanged(String diagramName, CosmicReport scopedReport, CosmicReport projectTotalReport) {}
}
