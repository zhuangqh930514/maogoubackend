package com.maogou.stock.service.impl;

import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ConditionalTradeRuleEngine {

    public static final String SCHEMA_VERSION = "CONDITIONAL_TRADE_STRATEGY_V1";
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public AiConditionalStrategyPayload evaluate(EngineInput input) {
        require(input);
        Metrics metrics = Metrics.from(
                input.detail(),
                threshold(input, "nearMovingAveragePct"),
                threshold(input, "nearSupportPct"),
                threshold(input, "longLowerShadowRatio"),
                threshold(input, "stopFallMaxChangePct"));
        List<AiConditionalStrategyPayload.HorizonPlan> plans = List.of(
                t1Plan(input, metrics),
                t2Plan(input, metrics),
                t3Plan(input, metrics)
        );
        List<AiConditionalStrategyPayload.SignalModel> buyModels = buyModels(input, metrics);
        List<AiConditionalStrategyPayload.SignalModel> sellModels = sellModels(input, metrics);
        AiConditionalStrategyPayload.RiskScore riskScore = riskScore(input, metrics);
        List<String> limitations = new ArrayList<>(input.dataLimitations() == null ? List.of() : input.dataLimitations());
        if (metrics.klines.size() < 20) {
            limitations.add("日K数据不足20个交易日，20日突破、压力位和风险判断降级为不可触发");
        }
        if (input.market().marketChangePct() == null || input.market().sectorChangePct() == null) {
            limitations.add("大盘或板块强弱缺少可验证时点数据，相关条件保持未确认，不会被包装为已触发");
        }
        if (input.market().fundFlowValue() == null) {
            limitations.add("个股资金流向未接入可靠时点源，资金条件仅展示为待确认，不参与自动触发");
        }
        return new AiConditionalStrategyPayload(
                SCHEMA_VERSION,
                input.tradeDate(),
                input.dataAsOf(),
                input.lineage(),
                input.position(),
                input.market(),
                plans,
                buyModels,
                sellModels,
                riskScore,
                input.configuration(),
                limitations.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().toList()
        );
    }

    private AiConditionalStrategyPayload.HorizonPlan t1Plan(EngineInput input, Metrics m) {
        List<AiConditionalStrategyPayload.Condition> strong = List.of(
                percentCondition("DAY_CHANGE", "当日涨幅不低于阈值", ">=", threshold(input, "strongRisePct"), m.dayChangePct, "实时行情/日K"),
                ratioCondition("VOLUME_EXPAND_5D", "成交量达到5日均量倍数", ">=", threshold(input, "strongVolumeRatio5"), m.volumeRatio5, "日K成交量"),
                priceCondition("BREAK_PRESSURE", "股价突破20日压力位", ">", m.pressure20, m.price, "日K高点"),
                percentCondition("SECTOR_STRONG", "所属板块不弱于配置阈值", ">=", threshold(input, "sectorStrongPct"), input.market().sectorChangePct(), "V2板块时点数据")
        );
        List<AiConditionalStrategyPayload.Condition> sideways = List.of(
                betweenCondition("DAY_SIDEWAYS", "涨跌幅位于震荡区间", threshold(input, "sidewaysAbsPct").negate(), threshold(input, "sidewaysAbsPct"), m.dayChangePct, "实时行情/日K"),
                ratioCondition("VOLUME_SHRINK_5D", "成交量低于5日均量", "<", BigDecimal.ONE, m.volumeRatio5, "日K成交量"),
                priceCondition("NOT_BREAK_PRESSURE", "尚未有效突破压力位", "<=", m.pressure20, m.price, "日K高点")
        );
        List<AiConditionalStrategyPayload.Condition> weak = List.of(
                percentCondition("DAY_FALL", "当日跌幅达到弱势阈值", "<=", threshold(input, "weakFallPct"), m.dayChangePct, "实时行情/日K"),
                ratioCondition("DOWN_VOLUME_EXPAND", "下跌同时放量", ">=", threshold(input, "weakVolumeRatio5"), m.volumeRatio5, "日K成交量"),
                priceCondition("BREAK_SUPPORT", "股价跌破第一支撑", "<", m.support1, m.price, "均线/日K低点"),
                percentCondition("SECTOR_WEAK", "所属板块同步走弱", "<=", threshold(input, "sectorWeakPct"), input.market().sectorChangePct(), "V2板块时点数据")
        );
        List<AiConditionalStrategyPayload.ConditionalRule> rules = List.of(
                rule(input, "T1_STRONG", "强势上涨", strong,
                        input.position().holding() ? "HOLD" : "WATCH",
                        input.position().holding() ? "保持现有仓位，不追涨；若回踩MA5不破再评估" : "不追高，等待回踩MA5或突破确认",
                        "若放量上涨但无法站稳压力位，按突破失败处理"),
                rule(input, "T1_SIDEWAYS", "震荡整理", sideways, "WATCH",
                        "支撑附近仅在缩量止跌后考虑小仓位，其他位置保持观察",
                        "震荡区间内不提前押注方向"),
                rule(input, "T1_WEAK", "弱势下跌", weak, "REDUCE",
                        m.secondSupportBroken ? position(input, "secondSupportReduce") : position(input, "firstSupportReduce"),
                        "跌破第一支撑执行减仓；继续跌破第二支撑时扩大减仓或退出")
        );
        return plan(1, "T+1 操作计划", "识别次日实际状态后执行对应动作", rules,
                priorityMatch(rules, List.of("T1_WEAK", "T1_STRONG", "T1_SIDEWAYS")));
    }

    private AiConditionalStrategyPayload.HorizonPlan t2Plan(EngineInput input, Metrics m) {
        List<AiConditionalStrategyPayload.Condition> strengthen = List.of(
                booleanCondition("TWO_DAYS_UP", "连续两个交易日收涨", m.twoDaysUp, "日K"),
                priceCondition("ABOVE_MA5", "股价站稳MA5", ">=", m.ma5, m.price, "日K均线"),
                booleanCondition("VOLUME_CONTINUE_UP", "成交量连续增加", m.volumeIncreasing, "日K成交量"),
                percentCondition("SECTOR_CONTINUE_STRONG", "板块继续强势", ">=", threshold(input, "sectorStrongPct"), input.market().sectorChangePct(), "V2板块时点数据")
        );
        List<AiConditionalStrategyPayload.Condition> unclear = List.of(
                betweenCondition("TWO_DAY_SIDEWAYS", "两日累计涨跌仍在震荡区间", threshold(input, "twoDaySidewaysPct").negate(), threshold(input, "twoDaySidewaysPct"), m.return2d, "日K"),
                booleanCondition("VOLUME_DECREASE", "成交量下降", m.volumeDecreasing, "日K成交量"),
                booleanCondition("SECTOR_DIVERGENCE", "板块分化或强弱未确认", input.market().sectorChangePct() == null ? null : input.market().sectorChangePct().abs().compareTo(threshold(input, "sectorStrongPct")) < 0, "V2板块时点数据")
        );
        List<AiConditionalStrategyPayload.Condition> failed = List.of(
                priceCondition("KEY_SUPPORT_FAILED", "跌破关键支撑", "<", m.support1, m.price, "日K均线/低点"),
                textCondition("FUND_OUTFLOW", "资金持续流出", "OUTFLOW", input.market().fundFlowStatus(), "资金流向"),
                percentCondition("SECTOR_RETREAT", "板块退潮", "<=", threshold(input, "sectorWeakPct"), input.market().sectorChangePct(), "V2板块时点数据")
        );
        List<AiConditionalStrategyPayload.ConditionalRule> rules = List.of(
                rule(input, "T2_TREND_STRENGTHEN", "趋势强化", strengthen, "ADD",
                        "满足条件后分次增加仓位，组合总仓位上限 " + position(input, "trendMax"),
                        "未同时获得量能和板块确认时不得加仓"),
                rule(input, "T2_UNCLEAR", "趋势不明确", unclear, "WATCH",
                        "保持观察，不增加仓位",
                        "等待价格、量能和板块重新同向"),
                rule(input, "T2_LOGIC_FAILED", "交易逻辑失败", failed, "REDUCE",
                        "降低仓位并退出原交易逻辑",
                        "失效后不得用原买入理由继续持有")
        );
        return plan(2, "T+2 趋势确认计划", "用两日真实走势验证交易逻辑是否强化", rules,
                priorityMatch(rules, List.of("T2_LOGIC_FAILED", "T2_TREND_STRENGTHEN", "T2_UNCLEAR")));
    }

    private AiConditionalStrategyPayload.HorizonPlan t3Plan(EngineInput input, Metrics m) {
        List<AiConditionalStrategyPayload.Condition> trend = List.of(
                booleanCondition("ABOVE_MA5_MA10", "股价同时高于MA5和MA10", compare(m.price, m.ma5, ">=") && compare(m.price, m.ma10, ">="), "日K均线"),
                ratioCondition("VOLUME_MAINTAIN", "成交量维持5日均量附近", ">=", threshold(input, "volumeMaintainRatio"), m.volumeRatio5, "日K成交量"),
                percentCondition("SECTOR_RANK_STRONG", "板块保持强势", ">=", threshold(input, "sectorStrongPct"), input.market().sectorChangePct(), "V2板块时点数据")
        );
        List<AiConditionalStrategyPayload.Condition> rebound = List.of(
                percentCondition("SHORT_REBOUND", "短期价格上涨", ">", BigDecimal.ZERO, m.return3d, "日K"),
                ratioCondition("REBOUND_VOLUME_WEAK", "反弹量能不足", "<", BigDecimal.ONE, m.volumeRatio5, "日K成交量"),
                priceCondition("LONG_PRESSURE_NOT_BROKEN", "仍未突破20日压力位", "<=", m.pressure20, m.price, "日K高点")
        );
        List<AiConditionalStrategyPayload.Condition> failed = List.of(
                priceCondition("TREND_LINE_FAILED", "跌破MA20趋势线", "<", m.ma20, m.price, "日K均线"),
                percentCondition("MARKET_WORSEN", "市场环境恶化", "<=", threshold(input, "marketWeakPct"), input.market().marketChangePct(), "市场时点数据")
        );
        List<AiConditionalStrategyPayload.ConditionalRule> rules = List.of(
                rule(input, "T3_TREND", "趋势行情", trend, "HOLD", "继续持有；只在条件持续成立时保留趋势仓位", "任一核心趋势条件失效时重新评估"),
                rule(input, "T3_REBOUND", "反弹行情", rebound, "REDUCE", "逢高降低仓位，不把弱量反弹当成趋势反转", "长期压力未突破前控制仓位"),
                rule(input, "T3_FAILED", "失败行情", failed, "SELL", "退出本次交易计划", "市场与个股趋势同时恶化时优先控制损失")
        );
        return plan(3, "T+3 趋势确认计划", "区分趋势、反弹和失败行情", rules,
                priorityMatch(rules, List.of("T3_FAILED", "T3_TREND", "T3_REBOUND")));
    }

    private List<AiConditionalStrategyPayload.SignalModel> buyModels(EngineInput input, Metrics m) {
        List<AiConditionalStrategyPayload.Condition> breakout = List.of(
                priceCondition("BREAK_20D_HIGH", "突破20日新高", ">", m.pressure20, m.price, "日K高点"),
                ratioCondition("BREAKOUT_VOLUME", "成交量达到20日均量倍数", ">=", threshold(input, "breakoutVolumeRatio20"), m.volumeRatio20, "日K成交量"),
                percentCondition("SECTOR_SYNC_UP", "行业板块同步上涨", ">=", threshold(input, "sectorStrongPct"), input.market().sectorChangePct(), "V2板块时点数据")
        );
        List<AiConditionalStrategyPayload.Condition> pullback = List.of(
                booleanCondition("PULLBACK_MA5_MA10", "价格回踩MA5或MA10附近", m.nearMa5Or10, "日K均线"),
                ratioCondition("PULLBACK_VOLUME_SHRINK", "回踩时成交量缩小", "<", BigDecimal.ONE, m.volumeRatio5, "日K成交量"),
                booleanCondition("STOP_FALL_SIGNAL", "出现长下影、阳包阴或缩量企稳", m.stopFallSignal, "日K形态")
        );
        List<AiConditionalStrategyPayload.Condition> confirm = List.of(
                priceCondition("PRESSURE_BROKEN", "突破压力位", ">", m.pressure20, m.price, "日K高点"),
                booleanCondition("TWO_DAYS_STABLE", "连续两日站稳突破位", m.twoDaysAbovePressure, "日K"),
                textCondition("FUND_CONTINUE_IN", "资金持续流入", "INFLOW", input.market().fundFlowStatus(), "资金流向")
        );
        return List.of(
                model(input, "BUY_BREAKOUT", "突破买入", breakout, m.pressure20, position(input, "breakout"), "BUY", "突破后跌回压力位下方则信号失效"),
                model(input, "BUY_PULLBACK", "回踩买入", pullback, m.ma5, position(input, "pullback"), "BUY", "支撑位失守或止跌形态被反包则停止买入"),
                model(input, "BUY_TREND_CONFIRM", "趋势确认买入", confirm, m.pressure20, position(input, "trendConfirm"), "BUY", "没有连续站稳和资金确认时不得提高仓位")
        );
    }

    private List<AiConditionalStrategyPayload.SignalModel> sellModels(EngineInput input, Metrics m) {
        BigDecimal firstTarget = threshold(input, "targetProfitFirstPct");
        BigDecimal secondTarget = threshold(input, "targetProfitSecondPct");
        List<AiConditionalStrategyPayload.Condition> target = List.of(
                percentCondition("PROFIT_TARGET", "持仓收益达到分批止盈阈值", ">=", firstTarget, input.position().holding() ? input.position().profitRate() : null, "用户持仓"),
                booleanCondition("NEAR_HISTORY_PRESSURE", "价格接近历史压力位", m.nearPressure, "日K高点")
        );
        List<AiConditionalStrategyPayload.Condition> technicalStop = List.of(
                percentCondition("COST_STOP", "持仓收益跌破技术止损阈值", "<=", threshold(input, "technicalStopLossPct"), input.position().holding() ? input.position().profitRate() : null, "用户持仓"),
                priceCondition("MA20_STOP", "股价跌破MA20", "<", m.ma20, m.price, "日K均线")
        );
        List<AiConditionalStrategyPayload.Condition> logicStop = List.of(
                percentCondition("SECTOR_LOGIC_CHANGED", "行业板块明显转弱", "<=", threshold(input, "sectorWeakPct"), input.market().sectorChangePct(), "V2板块时点数据"),
                textCondition("MAJOR_NEGATIVE", "公司出现可验证重大负面事件", "NEGATIVE", null, "实时资讯"),
                textCondition("FUND_CONTINUE_OUT", "主力资金持续流出", "OUTFLOW", input.market().fundFlowStatus(), "资金流向")
        );
        String targetPosition = input.position().profitRate() != null && input.position().profitRate().compareTo(secondTarget) >= 0
                ? position(input, "secondProfitReduce") : position(input, "firstProfitReduce");
        return List.of(
                model(input, "SELL_TARGET_PROFIT", "目标止盈", target, m.pressure20, targetPosition, "TAKE_PROFIT", "分批执行，剩余仓位继续服从趋势条件"),
                model(input, "SELL_TECHNICAL_STOP", "技术止损", technicalStop, m.ma20, "退出或降至风险可承受仓位", "STOP_LOSS", "止损条件触发后不得等待主观解套"),
                model(input, "SELL_LOGIC_STOP", "逻辑止损", logicStop, null, "立即重新评估并冻结加仓", "REASSESS", "行业、公司或资金数据缺失时不自动判定逻辑变化")
        );
    }

    private AiConditionalStrategyPayload.RiskScore riskScore(EngineInput input, Metrics m) {
        BigDecimal unknown = threshold(input, "riskUnknownScore");
        BigDecimal low = threshold(input, "riskLowScore");
        BigDecimal high = threshold(input, "riskHighScore");
        BigDecimal marketScore = switch (safeUpper(input.market().marketRegime())) {
            case "BULL", "STRONG", "UP" -> low;
            case "BEAR", "WEAK", "DOWN" -> high;
            default -> input.market().marketChangePct() == null ? unknown
                    : input.market().marketChangePct().compareTo(threshold(input, "marketWeakPct")) <= 0 ? high : low;
        };
        BigDecimal sectorScore = input.market().sectorChangePct() == null ? unknown
                : input.market().sectorChangePct().compareTo(threshold(input, "sectorWeakPct")) <= 0 ? high
                : input.market().sectorChangePct().compareTo(threshold(input, "sectorStrongPct")) >= 0 ? low : unknown;
        BigDecimal technicalScore = m.price == null || m.ma20 == null ? unknown
                : m.price.compareTo(m.ma20) < 0 || m.volatilityPct != null && m.volatilityPct.compareTo(threshold(input, "highVolatilityPct")) >= 0
                ? high : low;
        BigDecimal fundScore = input.market().fundFlowValue() == null ? unknown
                : "OUTFLOW".equals(safeUpper(input.market().fundFlowStatus())) ? high
                : "INFLOW".equals(safeUpper(input.market().fundFlowStatus())) ? low : unknown;
        List<AiConditionalStrategyPayload.RiskComponent> components = List.of(
                riskComponent(input, "MARKET", "市场风险", marketScore, "market", input.market().marketRegime(), input.market().marketChangePct() == null ? "MISSING" : "AVAILABLE"),
                riskComponent(input, "SECTOR", "行业风险", sectorScore, "sector", input.market().sectorName(), input.market().sectorChangePct() == null ? "MISSING" : "AVAILABLE"),
                riskComponent(input, "TECHNICAL", "个股技术风险", technicalScore, "technical", "价格、MA20与波动率", m.ma20 == null ? "MISSING" : "AVAILABLE"),
                riskComponent(input, "FUND", "资金风险", fundScore, "fund", input.market().fundFlowStatus(), input.market().fundFlowValue() == null ? "MISSING" : "AVAILABLE")
        );
        BigDecimal total = components.stream()
                .map(item -> item.score().multiply(item.weight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(1, RoundingMode.HALF_UP);
        String level = total.compareTo(threshold(input, "riskLowUpperBound")) < 0 ? "LOW"
                : total.compareTo(threshold(input, "riskMediumUpperBound")) < 0 ? "MEDIUM" : "HIGH";
        String advice = switch (level) {
            case "LOW" -> "风险较低，但仍只在条件触发后执行，不提前下注";
            case "HIGH" -> "风险较高，优先减仓、止损和等待数据确认";
            default -> "风险中等，控制单次仓位并严格执行失效条件";
        };
        return new AiConditionalStrategyPayload.RiskScore(total, level, components, advice);
    }

    private AiConditionalStrategyPayload.RiskComponent riskComponent(
            EngineInput input,
            String code,
            String name,
            BigDecimal score,
            String weightKey,
            String evidence,
            String dataStatus
    ) {
        BigDecimal weight = input.configuration().riskWeights().get(weightKey);
        if (weight == null) {
            throw new IllegalArgumentException("条件策略缺少风险权重：" + weightKey);
        }
        return new AiConditionalStrategyPayload.RiskComponent(code, name, score, weight, emptyAsDash(evidence), dataStatus);
    }

    private AiConditionalStrategyPayload.HorizonPlan plan(
            int horizon,
            String title,
            String objective,
            List<AiConditionalStrategyPayload.ConditionalRule> rules,
            AiConditionalStrategyPayload.ConditionalRule matched
    ) {
        return new AiConditionalStrategyPayload.HorizonPlan(
                horizon,
                title,
                objective,
                matched == null ? "待条件确认" : matched.state(),
                matched == null ? "WATCH" : matched.action(),
                rules
        );
    }

    private AiConditionalStrategyPayload.ConditionalRule rule(
            EngineInput input,
            String code,
            String state,
            List<AiConditionalStrategyPayload.Condition> conditions,
            String action,
            String position,
            String riskWarning
    ) {
        boolean matched = matched(input, code, conditions);
        return new AiConditionalStrategyPayload.ConditionalRule(
                code,
                state,
                ifThen(conditions, action, position),
                conditions,
                matched,
                action,
                position,
                riskWarning,
                signalStrength(input, code, conditions, matched),
                factorEvidence(input, code)
        );
    }

    private AiConditionalStrategyPayload.SignalModel model(
            EngineInput input,
            String code,
            String type,
            List<AiConditionalStrategyPayload.Condition> conditions,
            BigDecimal referencePrice,
            String position,
            String action,
            String riskWarning
    ) {
        boolean triggered = input.position().holding() || !code.startsWith("SELL_")
                ? matched(input, code, conditions) : false;
        return new AiConditionalStrategyPayload.SignalModel(
                code,
                type,
                ifThen(conditions, action, position),
                conditions,
                triggered,
                referencePrice == null ? "待数据确认" : money(referencePrice),
                signalStrength(input, code, conditions, triggered),
                position,
                action,
                riskWarning,
                factorEvidence(input, code)
        );
    }

    private boolean matched(EngineInput input, String code, List<AiConditionalStrategyPayload.Condition> conditions) {
        Integer minimum = input.configuration().minimumConditions().get(code);
        if (minimum == null) {
            throw new IllegalArgumentException("条件策略缺少最小触发数：" + code);
        }
        long satisfied = conditions.stream().filter(item -> Boolean.TRUE.equals(item.satisfied())).count();
        return satisfied >= minimum;
    }

    private BigDecimal signalStrength(
            EngineInput input,
            String code,
            List<AiConditionalStrategyPayload.Condition> conditions,
            boolean matched
    ) {
        long known = conditions.stream().filter(item -> item.satisfied() != null).count();
        long hits = conditions.stream().filter(item -> Boolean.TRUE.equals(item.satisfied())).count();
        BigDecimal conditionScore = known == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(hits).multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(known), 4, RoundingMode.HALF_UP);
        List<AiConditionalStrategyPayload.FactorEvidence> factors = factorEvidence(input, code);
        BigDecimal factorScore = factors.stream()
                .map(item -> item.historicalSuccessRate() == null ? new BigDecimal("50") : item.historicalSuccessRate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        factorScore = factors.isEmpty() ? new BigDecimal("50")
                : factorScore.divide(BigDecimal.valueOf(factors.size()), 4, RoundingMode.HALF_UP);
        BigDecimal ruleScore = input.ruleLearnedWeights().getOrDefault(code, new BigDecimal("50"));
        BigDecimal validationScore = input.lineage().strategyValidationScore() == null
                ? new BigDecimal("50") : input.lineage().strategyValidationScore();
        BigDecimal score = conditionScore.multiply(threshold(input, "signalConditionWeight"))
                .add(factorScore.multiply(threshold(input, "signalFactorWeight")))
                .add(ruleScore.multiply(threshold(input, "signalRuleWeight")))
                .add(validationScore.multiply(threshold(input, "signalValidationWeight")));
        if (!matched) {
            score = score.min(new BigDecimal("59.9"));
        }
        return clamp(score, BigDecimal.ZERO, ONE_HUNDRED).setScale(1, RoundingMode.HALF_UP);
    }

    private List<AiConditionalStrategyPayload.FactorEvidence> factorEvidence(EngineInput input, String ruleCode) {
        return input.configuration().factorMappings().getOrDefault(ruleCode, List.of()).stream()
                .map(input.factorEvidence()::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private static AiConditionalStrategyPayload.ConditionalRule priorityMatch(
            List<AiConditionalStrategyPayload.ConditionalRule> rules,
            List<String> priorities
    ) {
        Map<String, AiConditionalStrategyPayload.ConditionalRule> byCode = new LinkedHashMap<>();
        rules.forEach(rule -> byCode.put(rule.ruleCode(), rule));
        return priorities.stream().map(byCode::get).filter(Objects::nonNull)
                .filter(AiConditionalStrategyPayload.ConditionalRule::matched).findFirst().orElse(null);
    }

    private static String ifThen(List<AiConditionalStrategyPayload.Condition> conditions, String action, String position) {
        String trigger = conditions.stream().map(AiConditionalStrategyPayload.Condition::label)
                .reduce((left, right) -> left + "、" + right).orElse("条件成立");
        return "如果" + trigger + "，则执行“" + actionLabel(action) + "”；仓位：" + position;
    }

    private static String actionLabel(String action) {
        return switch (action) {
            case "BUY" -> "买入";
            case "ADD" -> "增加仓位";
            case "HOLD" -> "持有";
            case "WATCH" -> "观察";
            case "REDUCE" -> "减仓";
            case "SELL" -> "退出";
            case "TAKE_PROFIT" -> "分批止盈";
            case "STOP_LOSS" -> "止损";
            case "REASSESS" -> "重新评估";
            default -> action;
        };
    }

    private static AiConditionalStrategyPayload.Condition percentCondition(
            String code, String label, String operator, BigDecimal threshold, BigDecimal actual, String source
    ) {
        return numericCondition(code, label, "PERCENT", operator, threshold, actual, source, "%");
    }

    private static AiConditionalStrategyPayload.Condition ratioCondition(
            String code, String label, String operator, BigDecimal threshold, BigDecimal actual, String source
    ) {
        return numericCondition(code, label, "RATIO", operator, threshold, actual, source, "x");
    }

    private static AiConditionalStrategyPayload.Condition priceCondition(
            String code, String label, String operator, BigDecimal threshold, BigDecimal actual, String source
    ) {
        return numericCondition(code, label, "PRICE", operator, threshold, actual, source, "");
    }

    private static AiConditionalStrategyPayload.Condition numericCondition(
            String code, String label, String metric, String operator, BigDecimal threshold,
            BigDecimal actual, String source, String suffix
    ) {
        Boolean satisfied = actual == null || threshold == null ? null : compare(actual, threshold, operator);
        return new AiConditionalStrategyPayload.Condition(
                code, label, metric, operator,
                threshold == null ? "数据缺失" : decimal(threshold) + suffix,
                actual == null ? "数据缺失" : decimal(actual) + suffix,
                satisfied, source
        );
    }

    private static AiConditionalStrategyPayload.Condition betweenCondition(
            String code, String label, BigDecimal lower, BigDecimal upper, BigDecimal actual, String source
    ) {
        Boolean satisfied = actual == null ? null : actual.compareTo(lower) >= 0 && actual.compareTo(upper) <= 0;
        return new AiConditionalStrategyPayload.Condition(
                code, label, "PERCENT", "BETWEEN",
                decimal(lower) + "% ~ " + decimal(upper) + "%",
                actual == null ? "数据缺失" : decimal(actual) + "%",
                satisfied, source
        );
    }

    private static AiConditionalStrategyPayload.Condition booleanCondition(
            String code, String label, Boolean actual, String source
    ) {
        return new AiConditionalStrategyPayload.Condition(
                code, label, "BOOLEAN", "IS_TRUE", "成立",
                actual == null ? "数据缺失" : actual ? "成立" : "未成立",
                actual, source
        );
    }

    private static AiConditionalStrategyPayload.Condition textCondition(
            String code, String label, String expected, String actual, String source
    ) {
        Boolean satisfied = actual == null || actual.isBlank() ? null : expected.equalsIgnoreCase(actual.trim());
        return new AiConditionalStrategyPayload.Condition(
                code, label, "STATUS", "EQUALS", expected,
                actual == null || actual.isBlank() ? "数据缺失" : actual,
                satisfied, source
        );
    }

    private static boolean compare(BigDecimal actual, BigDecimal threshold, String operator) {
        if (actual == null || threshold == null) {
            return false;
        }
        int comparison = actual.compareTo(threshold);
        return switch (operator) {
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case "=" -> comparison == 0;
            default -> throw new IllegalArgumentException("不支持的条件运算符：" + operator);
        };
    }

    private static BigDecimal threshold(EngineInput input, String key) {
        BigDecimal value = input.configuration().thresholds().get(key);
        if (value == null) {
            throw new IllegalArgumentException("条件策略缺少阈值：" + key);
        }
        return value;
    }

    private static String position(EngineInput input, String key) {
        String value = input.configuration().positions().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("条件策略缺少仓位配置：" + key);
        }
        return value;
    }

    private static void require(EngineInput input) {
        if (input == null || input.tradeDate() == null || input.dataAsOf() == null
                || input.detail() == null || input.detail().quote() == null
                || input.position() == null || input.market() == null
                || input.lineage() == null || input.configuration() == null) {
            throw new IllegalArgumentException("条件策略输入不完整");
        }
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.max(min).min(max);
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "-" : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal value) {
        return value == null ? "待数据确认" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String safeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String emptyAsDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    public record EngineInput(
            LocalDate tradeDate,
            LocalDateTime dataAsOf,
            StockDetailResponse detail,
            AiConditionalStrategyPayload.PositionContext position,
            AiConditionalStrategyPayload.MarketContext market,
            AiConditionalStrategyPayload.ResearchLineage lineage,
            AiConditionalStrategyPayload.RuleConfiguration configuration,
            Map<String, AiConditionalStrategyPayload.FactorEvidence> factorEvidence,
            Map<String, BigDecimal> ruleLearnedWeights,
            List<String> dataLimitations
    ) {
        public EngineInput {
            factorEvidence = factorEvidence == null ? Map.of() : Map.copyOf(factorEvidence);
            ruleLearnedWeights = ruleLearnedWeights == null ? Map.of() : Map.copyOf(ruleLearnedWeights);
            dataLimitations = dataLimitations == null ? List.of() : List.copyOf(dataLimitations);
        }
    }

    private static final class Metrics {
        private final List<KlinePointResponse> klines;
        private final BigDecimal price;
        private final BigDecimal dayChangePct;
        private final BigDecimal return2d;
        private final BigDecimal return3d;
        private final BigDecimal volumeRatio5;
        private final BigDecimal volumeRatio20;
        private final BigDecimal ma5;
        private final BigDecimal ma10;
        private final BigDecimal ma20;
        private final BigDecimal pressure20;
        private final BigDecimal support1;
        private final BigDecimal support2;
        private final BigDecimal volatilityPct;
        private final boolean twoDaysUp;
        private final boolean volumeIncreasing;
        private final boolean volumeDecreasing;
        private final boolean nearMa5Or10;
        private final boolean stopFallSignal;
        private final boolean twoDaysAbovePressure;
        private final boolean nearPressure;
        private final boolean secondSupportBroken;

        private Metrics(
                List<KlinePointResponse> klines,
                BigDecimal price,
                BigDecimal dayChangePct,
                BigDecimal return2d,
                BigDecimal return3d,
                BigDecimal volumeRatio5,
                BigDecimal volumeRatio20,
                BigDecimal ma5,
                BigDecimal ma10,
                BigDecimal ma20,
                BigDecimal pressure20,
                BigDecimal support1,
                BigDecimal support2,
                BigDecimal volatilityPct,
                boolean twoDaysUp,
                boolean volumeIncreasing,
                boolean volumeDecreasing,
                boolean nearMa5Or10,
                boolean stopFallSignal,
                boolean twoDaysAbovePressure,
                boolean nearPressure,
                boolean secondSupportBroken
        ) {
            this.klines = klines;
            this.price = price;
            this.dayChangePct = dayChangePct;
            this.return2d = return2d;
            this.return3d = return3d;
            this.volumeRatio5 = volumeRatio5;
            this.volumeRatio20 = volumeRatio20;
            this.ma5 = ma5;
            this.ma10 = ma10;
            this.ma20 = ma20;
            this.pressure20 = pressure20;
            this.support1 = support1;
            this.support2 = support2;
            this.volatilityPct = volatilityPct;
            this.twoDaysUp = twoDaysUp;
            this.volumeIncreasing = volumeIncreasing;
            this.volumeDecreasing = volumeDecreasing;
            this.nearMa5Or10 = nearMa5Or10;
            this.stopFallSignal = stopFallSignal;
            this.twoDaysAbovePressure = twoDaysAbovePressure;
            this.nearPressure = nearPressure;
            this.secondSupportBroken = secondSupportBroken;
        }

        private static Metrics from(
                StockDetailResponse detail,
                BigDecimal nearMovingAveragePct,
                BigDecimal nearPressurePct,
                BigDecimal longLowerShadowRatio,
                BigDecimal stopFallMaxChangePct
        ) {
            List<KlinePointResponse> values = detail.kline() == null ? List.of() : detail.kline().stream()
                    .filter(item -> item != null && item.tradeDate() != null && item.close() != null)
                    .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                    .toList();
            KlinePointResponse latest = last(values, 0);
            KlinePointResponse previous = last(values, 1);
            KlinePointResponse beforePrevious = last(values, 2);
            BigDecimal price = detail.quote().price() != null && detail.quote().price().signum() > 0
                    ? detail.quote().price() : latest == null ? null : latest.close();
            BigDecimal dayChange = detail.quote().percent() != null ? detail.quote().percent()
                    : returnPct(previous == null ? null : previous.close(), price);
            BigDecimal return2d = returnPct(beforePrevious == null ? null : beforePrevious.close(), price);
            BigDecimal return3d = returnPct(last(values, 3) == null ? null : last(values, 3).close(), price);
            BigDecimal avgVolume5 = averageVolumeBeforeLatest(values, 5);
            BigDecimal avgVolume20 = averageVolumeBeforeLatest(values, 20);
            BigDecimal latestVolume = latest == null || latest.volume() == null ? null : BigDecimal.valueOf(latest.volume());
            BigDecimal volumeRatio5 = ratio(latestVolume, avgVolume5);
            BigDecimal volumeRatio20 = ratio(latestVolume, avgVolume20);
            BigDecimal ma5 = averageClose(values, 5);
            BigDecimal ma10 = averageClose(values, 10);
            BigDecimal ma20 = averageClose(values, 20);
            BigDecimal pressure20 = highBeforeLatest(values, 20);
            BigDecimal support2 = lowBeforeLatest(values, 20);
            BigDecimal support1 = ma10 == null ? support2 : support2 == null ? ma10 : ma10.max(support2);
            boolean twoDaysUp = latest != null && previous != null && beforePrevious != null
                    && latest.close().compareTo(previous.close()) > 0 && previous.close().compareTo(beforePrevious.close()) > 0;
            boolean volumeIncreasing = latestVolume != null && previous != null && previous.volume() != null
                    && latestVolume.compareTo(BigDecimal.valueOf(previous.volume())) > 0;
            boolean volumeDecreasing = latestVolume != null && previous != null && previous.volume() != null
                    && latestVolume.compareTo(BigDecimal.valueOf(previous.volume())) < 0;
            boolean nearMa5Or10 = near(price, ma5, nearMovingAveragePct) || near(price, ma10, nearMovingAveragePct);
            boolean longLowerShadow = latest != null && latest.open() != null && latest.low() != null && latest.high() != null
                    && latest.close() != null && latest.high().compareTo(latest.low()) > 0
                    && latest.open().min(latest.close()).subtract(latest.low())
                    .divide(latest.high().subtract(latest.low()), 4, RoundingMode.HALF_UP)
                    .compareTo(longLowerShadowRatio) >= 0;
            boolean bullishEngulf = latest != null && previous != null && latest.open() != null && previous.open() != null
                    && latest.close().compareTo(latest.open()) > 0 && previous.close().compareTo(previous.open()) < 0
                    && latest.close().compareTo(previous.open()) >= 0 && latest.open().compareTo(previous.close()) <= 0;
            boolean shrinkStable = volumeRatio5 != null && volumeRatio5.compareTo(BigDecimal.ONE) < 0
                    && dayChange != null && dayChange.abs().compareTo(stopFallMaxChangePct) <= 0;
            BigDecimal priorPressure = highBeforeIndex(values, Math.max(0, values.size() - 2), 20);
            boolean twoDaysAbovePressure = price != null && pressure20 != null && previous != null && priorPressure != null
                    && price.compareTo(pressure20) > 0 && previous.close().compareTo(priorPressure) > 0;
            BigDecimal volatility = volatilityPct(values, 10);
            return new Metrics(
                    values, price, dayChange, return2d, return3d, volumeRatio5, volumeRatio20,
                    ma5, ma10, ma20, pressure20, support1, support2, volatility,
                    twoDaysUp, volumeIncreasing, volumeDecreasing, nearMa5Or10,
                    longLowerShadow || bullishEngulf || shrinkStable,
                    twoDaysAbovePressure,
                    near(price, pressure20, nearPressurePct),
                    price != null && support2 != null && price.compareTo(support2) < 0
            );
        }

        private static KlinePointResponse last(List<KlinePointResponse> values, int offset) {
            int index = values.size() - 1 - offset;
            return index < 0 ? null : values.get(index);
        }

        private static BigDecimal averageClose(List<KlinePointResponse> values, int count) {
            if (values.size() < count) {
                return null;
            }
            List<KlinePointResponse> window = values.subList(values.size() - count, values.size());
            return window.stream().map(KlinePointResponse::close).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
        }

        private static BigDecimal averageVolumeBeforeLatest(List<KlinePointResponse> values, int count) {
            if (values.size() < count + 1) {
                return null;
            }
            List<KlinePointResponse> window = values.subList(values.size() - count - 1, values.size() - 1);
            if (window.stream().anyMatch(item -> item.volume() == null)) {
                return null;
            }
            return window.stream().map(item -> BigDecimal.valueOf(item.volume())).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
        }

        private static BigDecimal highBeforeLatest(List<KlinePointResponse> values, int count) {
            return highBeforeIndex(values, Math.max(0, values.size() - 1), count);
        }

        private static BigDecimal highBeforeIndex(List<KlinePointResponse> values, int exclusiveEnd, int count) {
            if (exclusiveEnd <= 0 || values.size() < 2) {
                return null;
            }
            int from = Math.max(0, exclusiveEnd - count);
            return values.subList(from, exclusiveEnd).stream().map(KlinePointResponse::high)
                    .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        }

        private static BigDecimal lowBeforeLatest(List<KlinePointResponse> values, int count) {
            if (values.size() < 2) {
                return null;
            }
            int end = values.size() - 1;
            int from = Math.max(0, end - count);
            return values.subList(from, end).stream().map(KlinePointResponse::low)
                    .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        }

        private static BigDecimal volatilityPct(List<KlinePointResponse> values, int count) {
            if (values.size() < count + 1) {
                return null;
            }
            List<BigDecimal> returns = new ArrayList<>();
            for (int index = values.size() - count; index < values.size(); index++) {
                BigDecimal value = returnPct(values.get(index - 1).close(), values.get(index).close());
                if (value != null) {
                    returns.add(value);
                }
            }
            if (returns.isEmpty()) {
                return null;
            }
            BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(returns.size()), 8, RoundingMode.HALF_UP);
            double variance = returns.stream().mapToDouble(value -> {
                double delta = value.subtract(mean).doubleValue();
                return delta * delta;
            }).average().orElse(0d);
            return BigDecimal.valueOf(Math.sqrt(variance)).setScale(4, RoundingMode.HALF_UP);
        }

        private static BigDecimal ratio(BigDecimal value, BigDecimal base) {
            return value == null || base == null || base.signum() == 0 ? null
                    : value.divide(base, 4, RoundingMode.HALF_UP);
        }

        private static BigDecimal returnPct(BigDecimal base, BigDecimal current) {
            return base == null || current == null || base.signum() == 0 ? null
                    : current.subtract(base).multiply(ONE_HUNDRED).divide(base, 4, RoundingMode.HALF_UP);
        }

        private static boolean near(BigDecimal value, BigDecimal target, BigDecimal pct) {
            return value != null && target != null && target.signum() != 0
                    && value.subtract(target).abs().multiply(ONE_HUNDRED)
                    .divide(target.abs(), 4, RoundingMode.HALF_UP).compareTo(pct) <= 0;
        }
    }
}
