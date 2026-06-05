package com.maogou.stock.service;

import com.maogou.stock.dto.ai.AiLearningPayloads;
import com.maogou.stock.dto.market.StockDetailResponse;

public interface AiLearningService {
    AiLearningPayloads.LearningDashboardResponse dashboard();

    AiLearningPayloads.SampleCenterResponse samples(String stockCode, int limit);

    AiLearningPayloads.SampleDetailResponse sampleDetail(Long sampleId);

    AiLearningPayloads.SampleCenterResponse buildWatchlistSamples(String universeCode, String samplePhase);

    AiLearningPayloads.SampleDetailResponse recomputeSampleFactors(Long sampleId);

    AiLearningPayloads.FactorFactoryResponse factorFactory();

    AiLearningPayloads.PredictionCenterResponse predictions(int limit);

    AiLearningPayloads.PredictionRankResponse rankUniverse(String universeCode, Integer horizonDays, Integer topK);

    AiLearningPayloads.LabelCenterResponse labels(int limit);

    AiLearningPayloads.LabelCenterResponse verifyLabels();

    AiLearningPayloads.ExperimentCenterResponse experiments();

    AiLearningPayloads.ExperimentCenterResponse runExperiment(String title, String universeCode);

    AiLearningPayloads.BacktestCenterResponse backtests();

    AiLearningPayloads.BacktestDetailResponse backtestDetail(Long runId);

    AiLearningPayloads.BacktestDetailResponse runBacktest(String title, String universeCode, Integer horizonDays, Integer topK);

    AiLearningPayloads.ModelEvalCenterResponse modelEvals();

    AiLearningPayloads.ModelEvalCenterResponse runModelEval(String evalType, Integer sampleCount);

    AiLearningPayloads.AnalysisLearningContext prepareAnalysisContext(StockDetailResponse detail, Long promptTemplateId);

    void linkReport(Long predictionId, Long reportId);
}
