package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiHistoricalReadinessFeatureMetric;
import com.maogou.stock.domain.entity.research.AiHistoricalReadinessSummary;
import com.maogou.stock.mapper.research.AiHistoricalReadinessMapper;
import com.maogou.stock.service.research.HistoricalReadinessEvaluator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calculates readiness from persisted facts, never from a requested target. */
@Service
public class HistoricalReadinessEvaluatorImpl implements HistoricalReadinessEvaluator {

    private final AiHistoricalReadinessMapper mapper;
    private final AiTrainingReadinessGate gate;
    private final ObjectMapper objectMapper;

    public HistoricalReadinessEvaluatorImpl(
            AiHistoricalReadinessMapper mapper,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.gate = new AiTrainingReadinessGate();
        this.objectMapper = objectMapper;
    }

    @Override
    public Evaluation evaluate(Request request) {
        validate(request);
        AiHistoricalReadinessSummary summary = mapper.selectSummary(
                request.runId(), request.featureVersion(), request.labelVersion(), request.startDate(),
                request.endDate(), request.asOfTime());
        if (summary == null) {
            summary = new AiHistoricalReadinessSummary();
        }
        Map<Integer, Integer> horizonCounts = dimensions(mapper.selectHorizonCounts(
                request.runId(), request.featureVersion(), request.labelVersion(), request.startDate(),
                request.endDate(), request.asOfTime()));
        Map<String, Integer> regimeDays = dimensionsText(mapper.selectRegimeDays(
                request.runId(), request.featureVersion(), request.startDate(), request.endDate(), request.asOfTime()));
        Map<String, Integer> classDistribution = dimensionsText(mapper.selectClassDistribution(
                request.runId(), request.featureVersion(), request.labelVersion(), request.startDate(),
                request.endDate(), request.asOfTime()));
        Map<String, Map<String, Integer>> featureCoverage = featureCoverage(mapper.selectFeatureCoverage(
                request.runId(), request.featureVersion(), request.factorVersion(), request.startDate(),
                request.endDate(), request.asOfTime()));

        int pitViolations = safe(mapper.countPointInTimeViolations(
                request.runId(), request.featureVersion(), request.startDate(), request.endDate(), request.asOfTime()));
        int duplicateLabels = safe(mapper.countDuplicateLabels(
                request.runId(), request.featureVersion(), request.labelVersion(), request.startDate(), request.endDate()));
        int mockSources = safe(mapper.countMockSources(
                request.runId(), request.featureVersion(), request.startDate(), request.endDate(), request.asOfTime()));
        int staleSources = safe(mapper.countStaleSources(
                request.runId(), request.featureVersion(), request.startDate(), request.endDate(), request.asOfTime()));
        int inferredFacts = safe(mapper.countInferredFacts(
                request.runId(), request.featureVersion(), request.startDate(), request.endDate(), request.asOfTime()));

        AiTrainingReadinessGate.Readiness base = gate.evaluate(new AiTrainingReadinessGate.Evidence(
                positive(summary.tradingDays), positive(summary.stockCount), horizonCounts, regimeDays,
                positive(summary.tradabilityEligible), positive(summary.tradabilityReady),
                positive(summary.universeEligible), positive(summary.universeReady),
                positive(summary.sectorEligible), positive(summary.sectorReady)));
        List<String> blocking = new ArrayList<>();
        if (!"READY".equals(base.status())) {
            if (base.remainingTradingDays() > 0) {
                blocking.add("TRADING_DAYS_BELOW_MINIMUM:" + base.remainingTradingDays());
            }
            if (base.remainingStocks() > 0) {
                blocking.add("STOCKS_BELOW_MINIMUM:" + base.remainingStocks());
            }
            base.remainingLabels().forEach((horizon, remaining) -> {
                if (remaining > 0) {
                    blocking.add("HORIZON_LABELS_BELOW_MINIMUM:T+" + horizon + ":" + remaining);
                }
            });
            base.missingRegimes().forEach(regime -> blocking.add("MARKET_REGIME_MISSING:" + regime));
            if (base.remainingTradabilityCoverage() > 0) {
                blocking.add("TRADABILITY_COVERAGE_BELOW_98PCT");
            }
            if (base.remainingUniverseCoverage() > 0) {
                blocking.add("UNIVERSE_COVERAGE_BELOW_98PCT");
            }
            if (base.remainingSectorEvidenceCoverage() > 0) {
                blocking.add("SECTOR_EVIDENCE_COVERAGE_BELOW_98PCT");
            }
        }
        if (pitViolations > 0) {
            blocking.add("POINT_IN_TIME_VIOLATIONS:" + pitViolations);
        }
        if (duplicateLabels > 0) {
            blocking.add("DUPLICATE_LABEL_BUSINESS_KEYS:" + duplicateLabels);
        }
        if (mockSources > 0) {
            blocking.add("MOCK_SOURCE_ROWS:" + mockSources);
        }
        if (staleSources > 0) {
            blocking.add("STALE_SOURCE_ROWS:" + staleSources);
        }
        if (inferredFacts > 0) {
            blocking.add("INFERRED_FACT_ROWS:" + inferredFacts);
        }
        if (featureCoverage.isEmpty()) {
            blocking.add("FACTOR_EVIDENCE_MISSING");
        } else {
            featureCoverage.forEach((code, coverage) -> {
                int total = coverage.getOrDefault("total", 0);
                int ready = coverage.getOrDefault("ready", 0);
                if (total == 0) {
                    blocking.add("FACTOR_NO_EVIDENCE:" + code);
                } else if (ratio(ready, total).compareTo(new BigDecimal("0.98")) < 0) {
                    blocking.add("FACTOR_COVERAGE_BELOW_98PCT:" + code);
                }
            });
        }
        String status = !blocking.isEmpty() ? "BLOCKED_BY_QUALITY" : base.status();
        String maturityLevel = "READY".equals(status)
                ? "R1_HISTORICAL_FACTS_READY" : "R0_RULES_LIVE";
        String evidenceChecksum = checksum(request, summary, horizonCounts, regimeDays,
                featureCoverage, classDistribution, blocking, pitViolations, duplicateLabels,
                mockSources, staleSources, inferredFacts);
        return new Evaluation(status, maturityLevel, positive(summary.tradingDays), positive(summary.stockCount),
                horizonCounts, regimeDays, positive(summary.tradabilityEligible), positive(summary.tradabilityReady),
                ratio(summary.tradabilityReady, summary.tradabilityEligible),
                positive(summary.universeEligible), positive(summary.universeReady),
                ratio(summary.universeReady, summary.universeEligible),
                positive(summary.sectorEligible), positive(summary.sectorReady),
                ratio(summary.sectorReady, summary.sectorEligible), featureCoverage, classDistribution,
                pitViolations, duplicateLabels, mockSources, staleSources, inferredFacts,
                List.copyOf(blocking), evidenceChecksum);
    }

    private static void validate(Request request) {
        if (request == null || request.runId() == null || request.runId() <= 0
                || request.asOfTime() == null || request.featureVersion() == null
                || request.factorVersion() == null || request.labelVersion() == null
                || request.calendarVersion() == null || request.startDate() == null
                || request.endDate() == null || request.startDate().isAfter(request.endDate())) {
            throw new IllegalArgumentException("历史 readiness 请求缺少版本、日期或截止时间");
        }
    }

    private static Map<Integer, Integer> dimensions(List<AiHistoricalReadinessMapper.DimensionMetric> values) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (AiHistoricalReadinessMapper.DimensionMetric value : values) {
            if (value == null || value.dimensionKey == null) {
                continue;
            }
            try {
                result.put(Integer.parseInt(value.dimensionKey), positive(value.metricCount));
            } catch (NumberFormatException ignored) {
                // A malformed dimension is not silently treated as a valid horizon.
            }
        }
        return result;
    }

    private static Map<String, Integer> dimensionsText(List<AiHistoricalReadinessMapper.DimensionMetric> values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (values != null) {
            for (AiHistoricalReadinessMapper.DimensionMetric value : values) {
                if (value != null && value.dimensionKey != null) {
                    result.put(value.dimensionKey, positive(value.metricCount));
                }
            }
        }
        return result;
    }

    private static Map<String, Map<String, Integer>> featureCoverage(
            List<com.maogou.stock.domain.entity.research.AiHistoricalReadinessFeatureMetric> values
    ) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        if (values != null) {
            for (AiHistoricalReadinessFeatureMetric value : values) {
                if (value == null || value.factorCode == null || value.factorCode.isBlank()) {
                    continue;
                }
                result.put(value.factorCode, Map.of(
                        "total", positive(value.totalCount),
                        "ready", positive(value.readyCount),
                        "missing", positive(value.missingCount)));
            }
        }
        return result;
    }

    private String checksum(
            Request request,
            AiHistoricalReadinessSummary summary,
            Map<Integer, Integer> horizons,
            Map<String, Integer> regimes,
            Map<String, Map<String, Integer>> featureCoverage,
            Map<String, Integer> classDistribution,
            List<String> blocking,
            int pit,
            int duplicates,
            int mock,
            int stale,
            int inferred
    ) {
        try {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("request", request);
            evidence.put("summary", summary);
            evidence.put("horizons", horizons);
            evidence.put("regimes", regimes);
            evidence.put("features", featureCoverage);
            evidence.put("classes", classDistribution);
            evidence.put("blocking", blocking);
            evidence.put("pit", pit);
            evidence.put("duplicates", duplicates);
            evidence.put("mock", mock);
            evidence.put("stale", stale);
            evidence.put("inferred", inferred);
            String content = objectMapper.writeValueAsString(evidence);
            return sha256(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成 readiness evidence checksum", exception);
        }
    }

    private static BigDecimal ratio(Integer numerator, Integer denominator) {
        int top = positive(numerator);
        int bottom = positive(denominator);
        return bottom == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(top).divide(BigDecimal.valueOf(bottom), 6, RoundingMode.HALF_UP);
    }

    private static int positive(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static int safe(int value) {
        return Math.max(0, value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
