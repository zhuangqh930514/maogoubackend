package com.maogou.stock.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.dto.ai.AiFactorCenterResponse;
import com.maogou.stock.mapper.AiAnalysisDecisionMapper;
import com.maogou.stock.mapper.AiAnalysisFactorHitMapper;
import com.maogou.stock.mapper.AiAnalysisOutcomeMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.AiFactorStatMapper;
import com.maogou.stock.mapper.AiStrategyEvolutionLogMapper;
import com.maogou.stock.mapper.AiStrategyVersionMapper;
import com.maogou.stock.service.MarketDataService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiEvolutionServiceImplReadOnlyTest {

    @Test
    void legacyFactorRefreshIsReadOnly() {
        AiAnalysisReportMapper reportMapper = mock(AiAnalysisReportMapper.class);
        AiAnalysisDecisionMapper decisionMapper = mock(AiAnalysisDecisionMapper.class);
        AiAnalysisOutcomeMapper outcomeMapper = mock(AiAnalysisOutcomeMapper.class);
        AiAnalysisFactorHitMapper factorHitMapper = mock(AiAnalysisFactorHitMapper.class);
        AiFactorStatMapper factorStatMapper = mock(AiFactorStatMapper.class);
        AiStrategyVersionMapper strategyVersionMapper = mock(AiStrategyVersionMapper.class);
        AiStrategyEvolutionLogMapper strategyLogMapper = mock(AiStrategyEvolutionLogMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        when(factorStatMapper.selectList(any())).thenReturn(List.of());

        AiEvolutionServiceImpl service = new AiEvolutionServiceImpl(
                reportMapper,
                decisionMapper,
                outcomeMapper,
                factorHitMapper,
                factorStatMapper,
                strategyVersionMapper,
                strategyLogMapper,
                marketDataService,
                new ObjectMapper()
        );

        AiFactorCenterResponse response = service.refreshFactors();

        assertThat(response.message()).contains("旧版", "只读");
        verify(factorHitMapper, never()).delete(any());
        verify(factorStatMapper, never()).delete(any());
    }
}
