package com.cosmic.vpplugin.service;

import com.cosmic.vpplugin.calculator.CosmicCalculator.CosmicReport;
import com.cosmic.vpplugin.model.CosmicJsonModel;

public interface CosmicAnalysisListener {

    void onLog(String message);

    void onBusyStateChanged(boolean busy);

    /** Nuovo metodo per l'avanzamento dell'analisi a chunk */
    default void onProgress(int current, int total, String stepDescription) {}

    void onAnalysisCompleted(CosmicJsonModel model, CosmicReport report);

    void onAnalysisFailed(Throwable error);

    default void onScopeChanged(String diagramName, CosmicReport scopedReport, CosmicReport projectTotalReport) {}
}