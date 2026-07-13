package com.maogou.stock.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.AiBacktestRunMapper;
import com.maogou.stock.mapper.AiBacktestTradeMapper;
import com.maogou.stock.mapper.AiFactorDefinitionMapper;
import com.maogou.stock.mapper.AiFactorStatMapper;
import com.maogou.stock.mapper.AiFactorValueMapper;
import com.maogou.stock.mapper.AiLearningJobLogMapper;
import com.maogou.stock.mapper.AiModelEvalRunMapper;
import com.maogou.stock.mapper.AiPredictionLabelMapper;
import com.maogou.stock.mapper.AiPredictionResultMapper;
import com.maogou.stock.mapper.AiPredictionSampleMapper;
import com.maogou.stock.mapper.AiStrategyExperimentMapper;
import com.maogou.stock.mapper.AiStrategyVersionMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.ModelConfigService;
import com.maogou.stock.service.WatchlistService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AiLearningRefreshPolicyTest {

    @Test
    void runningExperimentDoesNotImplicitlyReverifyLabels() {
        AiPredictionResultMapper predictionMapper = mock(AiPredictionResultMapper.class);
        AiLearningServiceImpl service = new AiLearningServiceImpl(
                mock(AiPredictionSampleMapper.class),
                mock(AiFactorDefinitionMapper.class),
                mock(AiFactorValueMapper.class),
                predictionMapper,
                mock(AiPredictionLabelMapper.class),
                mock(AiStrategyExperimentMapper.class),
                mock(AiBacktestRunMapper.class),
                mock(AiBacktestTradeMapper.class),
                mock(AiLearningJobLogMapper.class),
                mock(AiModelEvalRunMapper.class),
                mock(AiFactorStatMapper.class),
                mock(AiStrategyVersionMapper.class),
                mock(AiAnalysisReportMapper.class),
                mock(MarketDataService.class),
                mock(WatchlistService.class),
                mock(ModelConfigService.class),
                new ObjectMapper()
        );

        service.runExperiment("test", "WATCHLIST");

        verify(predictionMapper, never()).selectOne(any());
    }
}
