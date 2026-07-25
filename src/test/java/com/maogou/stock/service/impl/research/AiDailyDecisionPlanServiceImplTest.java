package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiDailyDecisionPlan;
import com.maogou.stock.mapper.AiTradeFactorFeedbackMapper;
import com.maogou.stock.mapper.AiTradeRulePerformanceMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionItemMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionPlanMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionPlanReviewMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.service.AiConditionalTradeStrategyService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiDailyDecisionPlanService;
import com.maogou.stock.service.impl.ConditionalTradeRuleEngine;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiDailyDecisionPlanServiceImplTest {

    @Test
    void missingFormalSampleBecomesExplicitUnavailablePlansInsteadOfSyntheticSignals() {
        AiDailyDecisionPlanMapper planMapper = mock(AiDailyDecisionPlanMapper.class);
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        TradingCalendarService calendar = mock(TradingCalendarService.class);
        when(planMapper.selectOne(any())).thenReturn(null);
        when(calendar.isTradingDay(any())).thenReturn(true);

        AiDailyDecisionPlanService service = new AiDailyDecisionPlanServiceImpl(
                planMapper,
                mock(AiDailyDecisionPlanReviewMapper.class),
                mock(AiDailyDecisionItemMapper.class),
                sampleMapper,
                mock(AiTradeRulePerformanceMapper.class),
                mock(AiTradeFactorFeedbackMapper.class),
                mock(AiConditionalTradeStrategyService.class),
                new ConditionalTradeRuleEngine(),
                mock(MarketDataService.class),
                calendar,
                new ObjectMapper().findAndRegisterModules());

        AiDailyDecisionItem item = new AiDailyDecisionItem();
        item.id = 41L;
        item.userId = 5L;
        item.sampleId = 31L;
        item.stockCode = "600519";
        item.finalAction = "WATCH";
        item.inputFingerprint = "decision-input";

        AiDailyDecisionPlanService.PlanBuildResult result = service.initializeDeterministicPlans(
                5L, LocalDate.of(2026, 7, 10), List.of(item));

        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.unavailableCount()).isEqualTo(3);
        assertThat(result.failedCount()).isZero();
        verify(planMapper, times(3)).insert(org.mockito.ArgumentMatchers.<AiDailyDecisionPlan>argThat(plan ->
                "UNAVAILABLE".equals(plan.status)
                        && "DETERMINISTIC_POLICY".equals(plan.planSource)
                        && plan.unavailableReason.contains("不可变研究样本")));
    }
}
