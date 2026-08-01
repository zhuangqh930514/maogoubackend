package com.maogou.stock.service.research;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Versioned, auditable parameters for a decision policy.  The defaults are
 * only a bootstrap value; production runs should load the matching SHADOW
 * release row created by the migration.
 */
public record DecisionPolicyConfig(
        String version,
        BigDecimal t1Weight,
        BigDecimal t2Weight,
        BigDecimal t3Weight,
        BigDecimal horizonWeight,
        BigDecimal factorWeight,
        BigDecimal strategyWeight,
        int minimumEvaluatedSamples,
        int fullEvaluatedSamples,
        BigDecimal randomSignal,
        BigDecimal wilsonBaseline,
        BigDecimal wilsonSpan,
        BigDecimal recommendScore,
        BigDecimal riskLimit,
        BigDecimal minimumDataQuality,
        BigDecimal stockScopePenalty,
        BigDecimal strategyScopePenalty,
        BigDecimal marketRegimeScopePenalty,
        BigDecimal defaultScopePenalty
) {
    public DecisionPolicyConfig {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("决策策略版本不能为空");
        }
        if (minimumEvaluatedSamples < 0 || fullEvaluatedSamples < minimumEvaluatedSamples) {
            throw new IllegalArgumentException("决策策略样本门槛无效");
        }
        requireUnit("randomSignal", randomSignal);
        requireUnit("wilsonBaseline", wilsonBaseline);
        requireUnit("wilsonSpan", wilsonSpan);
        requireScore("recommendScore", recommendScore);
        requireScore("riskLimit", riskLimit);
        requireUnit("minimumDataQuality", minimumDataQuality);
        requireUnit("t1Weight", t1Weight);
        requireUnit("t2Weight", t2Weight);
        requireUnit("t3Weight", t3Weight);
        requireUnit("horizonWeight", horizonWeight);
        requireUnit("factorWeight", factorWeight);
        requireUnit("strategyWeight", strategyWeight);
        requireUnit("stockScopePenalty", stockScopePenalty);
        requireUnit("strategyScopePenalty", strategyScopePenalty);
        requireUnit("marketRegimeScopePenalty", marketRegimeScopePenalty);
        requireUnit("defaultScopePenalty", defaultScopePenalty);
        if (t1Weight.add(t2Weight).add(t3Weight).compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("T+1/T+2/T+3 权重之和必须为 1");
        }
        if (horizonWeight.add(factorWeight).add(strategyWeight).compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("决策核心权重之和必须为 1");
        }
    }

    public static DecisionPolicyConfig defaults() {
        return new DecisionPolicyConfig(
                "DECISION/2.0.0",
                new BigDecimal("0.20"), new BigDecimal("0.30"), new BigDecimal("0.50"),
                new BigDecimal("0.65"), new BigDecimal("0.20"), new BigDecimal("0.15"),
                30, 200, new BigDecimal("0.50"), new BigDecimal("0.50"), new BigDecimal("0.15"),
                new BigDecimal("70"), new BigDecimal("60"), new BigDecimal("0.90"),
                BigDecimal.ONE, new BigDecimal("0.75"), new BigDecimal("0.60"), new BigDecimal("0.50"));
    }

    public static DecisionPolicyConfig fromJson(JsonNode json) {
        DecisionPolicyConfig defaults = defaults();
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("决策策略配置不是 JSON 对象");
        }
        return new DecisionPolicyConfig(
                text(json, "version", defaults.version()),
                decimal(json, "t1Weight", defaults.t1Weight()),
                decimal(json, "t2Weight", defaults.t2Weight()),
                decimal(json, "t3Weight", defaults.t3Weight()),
                decimal(json, "horizonWeight", defaults.horizonWeight()),
                decimal(json, "factorWeight", defaults.factorWeight()),
                decimal(json, "strategyWeight", defaults.strategyWeight()),
                integer(json, "minimumEvaluatedSamples", defaults.minimumEvaluatedSamples()),
                integer(json, "fullEvaluatedSamples", defaults.fullEvaluatedSamples()),
                decimal(json, "randomSignal", defaults.randomSignal()),
                decimal(json, "wilsonBaseline", defaults.wilsonBaseline()),
                decimal(json, "wilsonSpan", defaults.wilsonSpan()),
                decimal(json, "recommendScore", defaults.recommendScore()),
                decimal(json, "riskLimit", defaults.riskLimit()),
                decimal(json, "minimumDataQuality", defaults.minimumDataQuality()),
                decimal(json, "stockScopePenalty", defaults.stockScopePenalty()),
                decimal(json, "strategyScopePenalty", defaults.strategyScopePenalty()),
                decimal(json, "marketRegimeScopePenalty", defaults.marketRegimeScopePenalty()),
                decimal(json, "defaultScopePenalty", defaults.defaultScopePenalty()));
    }

    private static String text(JsonNode json, String key, String fallback) {
        JsonNode value = json.get(key);
        return value == null || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    private static BigDecimal decimal(JsonNode json, String key, BigDecimal fallback) {
        JsonNode value = json.get(key);
        if (value == null || value.isNull()) return fallback;
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("决策策略参数无效：" + key, exception);
        }
    }

    private static int integer(JsonNode json, String key, int fallback) {
        JsonNode value = json.get(key);
        return value == null || value.isNull() ? fallback : value.asInt(fallback);
    }

    private static void requireUnit(String name, BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("决策策略参数超出 0~1：" + name);
        }
    }

    private static void requireScore(String name, BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("决策策略分数超出 0~100：" + name);
        }
    }
}
