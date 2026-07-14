package com.maogou.stock.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionalTradeRuleEngineTest {

    private final ConditionalTradeRuleEngine engine = new ConditionalTradeRuleEngine();

    @Test
    void emitsConditionalThreeDayPlanAndTriggersBreakoutModel() throws Exception {
        AiConditionalStrategyPayload payload = engine.evaluate(input(
                detail(new BigDecimal("110"), new BigDecimal("3.2"), 2_000L),
                market("BULL", new BigDecimal("0.8"), new BigDecimal("1.2")),
                new AiConditionalStrategyPayload.PositionContext(
                        false, 0, null, new BigDecimal("110"), null)));

        assertThat(payload.tradingPlans()).extracting(AiConditionalStrategyPayload.HorizonPlan::horizonDays)
                .containsExactly(1, 2, 3);
        assertThat(payload.tradingPlans().get(0).currentState()).isEqualTo("强势上涨");
        assertThat(payload.buyModels()).filteredOn(AiConditionalStrategyPayload.SignalModel::triggered)
                .extracting(AiConditionalStrategyPayload.SignalModel::modelCode)
                .contains("BUY_BREAKOUT");
        assertThat(payload.tradingPlans()).flatExtracting(AiConditionalStrategyPayload.HorizonPlan::rules)
                .allSatisfy(rule -> assertThat(rule.ifThen()).startsWith("如果").contains("则执行"));
        assertThat(payload.riskScore().components()).hasSize(4);
    }

    @Test
    void prioritizesWeakStateAndTriggersTechnicalStopForHeldPosition() throws Exception {
        StockDetailResponse detail = detail(new BigDecimal("90"), new BigDecimal("-4.2"), 2_200L);
        AiConditionalStrategyPayload payload = engine.evaluate(input(
                detail,
                market("BEAR", new BigDecimal("-1.5"), new BigDecimal("-2.0")),
                new AiConditionalStrategyPayload.PositionContext(
                        true, 1_000, new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("-10"))));

        assertThat(payload.tradingPlans().get(0).currentState()).isEqualTo("弱势下跌");
        assertThat(payload.tradingPlans().get(0).currentAction()).isEqualTo("REDUCE");
        assertThat(payload.sellModels()).filteredOn(AiConditionalStrategyPayload.SignalModel::triggered)
                .extracting(AiConditionalStrategyPayload.SignalModel::modelCode)
                .contains("SELL_TECHNICAL_STOP");
        assertThat(payload.riskScore().level()).isEqualTo("HIGH");
    }

    @Test
    void keepsUnavailableSectorConditionUnconfirmed() throws Exception {
        AiConditionalStrategyPayload payload = engine.evaluate(input(
                detail(new BigDecimal("110"), new BigDecimal("3.2"), 2_000L),
                market("UNKNOWN", null, null),
                new AiConditionalStrategyPayload.PositionContext(
                        false, 0, null, new BigDecimal("110"), null)));

        AiConditionalStrategyPayload.SignalModel breakout = payload.buyModels().stream()
                .filter(item -> "BUY_BREAKOUT".equals(item.modelCode())).findFirst().orElseThrow();
        assertThat(breakout.triggered()).isFalse();
        assertThat(breakout.triggerConditions()).filteredOn(item -> "SECTOR_SYNC_UP".equals(item.code()))
                .singleElement().extracting(AiConditionalStrategyPayload.Condition::satisfied).isNull();
        assertThat(payload.dataLimitations()).anyMatch(item -> item.contains("板块强弱缺少"));
    }

    private ConditionalTradeRuleEngine.EngineInput input(
            StockDetailResponse detail,
            AiConditionalStrategyPayload.MarketContext market,
            AiConditionalStrategyPayload.PositionContext position
    ) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AiConditionalStrategyPayload.RuleConfiguration configuration = mapper.readValue(
                getClass().getClassLoader().getResourceAsStream("ai/conditional-trade-rules-v1.json"),
                AiConditionalStrategyPayload.RuleConfiguration.class);
        return new ConditionalTradeRuleEngine.EngineInput(
                LocalDate.of(2026, 7, 14),
                LocalDateTime.of(2026, 7, 14, 15, 0),
                detail,
                position,
                market,
                new AiConditionalStrategyPayload.ResearchLineage(
                        1L, 2L, 3L, "S1", "2.0.0", 4L, configuration.version(), "fp",
                        new BigDecimal("90"), new BigDecimal("60")),
                configuration,
                Map.of(),
                Map.of(),
                List.of()
        );
    }

    private static StockDetailResponse detail(BigDecimal price, BigDecimal percent, long latestVolume) {
        List<KlinePointResponse> klines = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 6, 8);
        for (int index = 0; index < 25; index++) {
            BigDecimal close = new BigDecimal("100").add(BigDecimal.valueOf(index).multiply(new BigDecimal("0.10")));
            if (index == 24) {
                close = price;
            }
            klines.add(new KlinePointResponse(
                    start.plusDays(index), close.subtract(new BigDecimal("0.20")), close,
                    close.subtract(new BigDecimal("0.60")), close.add(new BigDecimal("0.50")),
                    index == 24 ? latestVolume : 1_000L, BigDecimal.valueOf(100_000_000L)));
        }
        StockQuoteResponse quote = new StockQuoteResponse(
                "600519", "贵州茅台", price, BigDecimal.ZERO, percent, new BigDecimal("2"),
                "SH", "TEST_REALTIME", LocalDateTime.of(2026, 7, 14, 15, 0));
        return new StockDetailResponse(quote, null, List.of(), klines, null, null);
    }

    private static AiConditionalStrategyPayload.MarketContext market(
            String regime,
            BigDecimal marketChange,
            BigDecimal sectorChange
    ) {
        return new AiConditionalStrategyPayload.MarketContext(
                regime, marketChange, "白酒", sectorChange, "UNAVAILABLE", null, "PARTIAL");
    }
}
