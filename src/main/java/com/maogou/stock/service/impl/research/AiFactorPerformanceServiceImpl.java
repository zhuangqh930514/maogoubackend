package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.maogou.stock.domain.entity.research.AiDriftEvent;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import com.maogou.stock.mapper.research.AiDriftEventMapper;
import com.maogou.stock.mapper.research.AiFactorPerformanceMapper;
import com.maogou.stock.service.research.AiFactorPerformanceService;
import com.maogou.stock.service.research.AiFactorSignalPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AiFactorPerformanceServiceImpl implements AiFactorPerformanceService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final AiFactorPerformanceMapper performanceMapper;
    private final AiDriftEventMapper driftMapper;
    private final ObjectMapper objectMapper;

    public AiFactorPerformanceServiceImpl(
            AiFactorPerformanceMapper performanceMapper,
            AiDriftEventMapper driftMapper,
            ObjectMapper objectMapper
    ) {
        this.performanceMapper = performanceMapper;
        this.driftMapper = driftMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public EvaluationResult evaluateAndStore(PerformanceBatch batch) {
        validateBatch(batch);
        batch.observations().forEach(observation -> validateObservationLineage(observation, batch, true));
        if (batch.baselineObservations() != null) {
            batch.baselineObservations().forEach(
                    observation -> validateObservationLineage(observation, batch, false));
        }
        validateUniqueObservations(batch.observations(), batch.baselineObservations());
        List<Observation> valid = batch.observations().stream()
                .filter(AiFactorPerformanceServiceImpl::validObservation)
                .toList();
        List<Observation> validBaseline = batch.baselineObservations() == null ? List.of()
                : batch.baselineObservations().stream()
                .filter(AiFactorPerformanceServiceImpl::validObservation)
                .toList();
        Map<String, List<Observation>> byFactor = new LinkedHashMap<>();
        valid.stream()
                .sorted(Comparator.comparing(observation -> observation.factor().factorCode))
                .forEach(observation -> byFactor.computeIfAbsent(
                        observation.factor().factorCode, ignored -> new ArrayList<>()).add(observation));
        Map<String, List<Observation>> baselineByFactor = new LinkedHashMap<>();
        validBaseline.stream()
                .sorted(Comparator.comparing(observation -> observation.factor().factorCode))
                .forEach(observation -> baselineByFactor.computeIfAbsent(
                        observation.factor().factorCode, ignored -> new ArrayList<>()).add(observation));

        List<AiFactorPerformance> candidates = byFactor.entrySet().stream()
                .map(entry -> performance(batch, entry.getKey(), entry.getValue(),
                        baselineByFactor.getOrDefault(entry.getKey(), List.of())))
                .toList();
        if (candidates.isEmpty()) {
            return new EvaluationResult(List.of(), List.of(), List.of());
        }
        performanceMapper.insertBatchImmutable(candidates);
        List<AiFactorPerformance> persisted = performanceMapper.selectWindowForShare(
                batch.factorVersion(), batch.horizonDays(), batch.marketRegime(),
                batch.windowType(), batch.windowStartDate(), batch.windowEndDate());
        Map<String, AiFactorPerformance> persistedByFactor = new HashMap<>();
        persisted.forEach(item -> persistedByFactor.put(item.factorCode, item));
        List<AiFactorPerformance> result = new ArrayList<>(candidates.size());
        for (AiFactorPerformance expected : candidates) {
            AiFactorPerformance actual = persistedByFactor.get(expected.factorCode);
            if (actual == null) {
                throw new IllegalStateException("因子表现写入后未读取到记录：" + expected.factorCode);
            }
            if (!Objects.equals(expected.inputFingerprint, actual.inputFingerprint)) {
                throw new IllegalStateException("不可变因子表现冲突：" + expected.factorCode);
            }
            result.add(actual);
        }
        List<AiDriftEvent> driftCandidates = new ArrayList<>();
        for (AiFactorPerformance performance : result) {
            driftCandidates.addAll(driftEvents(
                    batch,
                    performance,
                    byFactor.getOrDefault(performance.factorCode, List.of()),
                    baselineByFactor.getOrDefault(performance.factorCode, List.of())));
        }
        List<AiDriftEvent> driftEvents = persistDriftEvents(driftCandidates);
        List<String> reweightEligible = result.stream()
                .filter(performance -> !"LOW_SAMPLE".equals(performance.confidenceLevel))
                .filter(performance -> !"CRITICAL".equals(performance.driftStatus))
                .filter(performance -> performance.rankIc != null)
                .map(performance -> performance.factorCode)
                .toList();
        return new EvaluationResult(List.copyOf(result), driftEvents, reweightEligible);
    }

    private AiFactorPerformance performance(
            PerformanceBatch batch,
            String factorCode,
            List<Observation> observations,
            List<Observation> baselineObservations
    ) {
        List<BigDecimal> directionalReturns = observations.stream()
                .map(observation -> observation.label().excessReturn.multiply(
                        BigDecimal.valueOf(signal(observation).signum())))
                .toList();
        int successCount = (int) directionalReturns.stream().filter(value -> value.signum() > 0).count();
        List<BigDecimal> dailyIc = dailyRankIc(observations);

        AiFactorPerformance value = new AiFactorPerformance();
        value.factorDefinitionId = observations.get(0).factor().factorDefinitionId;
        value.factorCode = factorCode;
        value.factorVersion = batch.factorVersion();
        value.horizonDays = batch.horizonDays();
        value.marketRegime = batch.marketRegime();
        value.windowType = batch.windowType();
        value.windowStartDate = batch.windowStartDate();
        value.windowEndDate = batch.windowEndDate();
        value.sampleCount = observations.size();
        value.successCount = successCount;
        value.successRate = percentage(successCount, observations.size());
        value.wilsonLowerBound = wilsonLowerBound(successCount, observations.size());
        value.rankIc = average(dailyIc, 6);
        value.avgExcessReturn = average(directionalReturns, 6);
        value.avgAdverseReturn = average(observations.stream()
                .map(observation -> observation.label().maxAdverseReturn)
                .filter(Objects::nonNull).toList(), 6);
        value.stabilityScore = stability(dailyIc);
        boolean driftEligible = observations.size() >= 20 && baselineObservations.size() >= 20;
        value.psiScore = driftEligible ? populationStabilityIndex(
                baselineObservations.stream().map(AiFactorPerformanceServiceImpl::signal).toList(),
                observations.stream().map(AiFactorPerformanceServiceImpl::signal).toList()) : null;
        value.confidenceLevel = observations.size() < 20 ? "LOW_SAMPLE"
                : observations.size() < 50 ? "MEDIUM" : "HIGH";
        value.driftStatus = driftEligible
                ? driftStatus(value, baselineObservations, batch.thresholds()) : "UNKNOWN";
        value.evaluatedAt = batch.evaluatedAt();
        value.createdAt = LocalDateTime.now();
        value.updatedAt = value.createdAt;
        value.inputFingerprint = performanceFingerprint(
                batch, factorCode, observations, baselineObservations);
        return value;
    }

    private List<AiDriftEvent> driftEvents(
            PerformanceBatch batch,
            AiFactorPerformance performance,
            List<Observation> current,
            List<Observation> baseline
    ) {
        if (current.size() < 20 || baseline.size() < 20) {
            return List.of();
        }
        List<AiDriftEvent> events = new ArrayList<>();
        BigDecimal baselineRate = successRate(baseline);
        BigDecimal rateDrop = baselineRate.subtract(performance.successRate);
        if (performance.psiScore != null
                && performance.psiScore.compareTo(batch.thresholds().psiWarning()) >= 0) {
            String severity = performance.psiScore.compareTo(batch.thresholds().psiCritical()) >= 0
                    ? "CRITICAL" : "WARNING";
            BigDecimal threshold = "CRITICAL".equals(severity)
                    ? batch.thresholds().psiCritical() : batch.thresholds().psiWarning();
            events.add(driftEvent(batch, performance, "PSI", null,
                    performance.psiScore, threshold, severity,
                    Map.of("baselineSampleCount", baseline.size(), "currentSampleCount", current.size())));
        }
        if (rateDrop.compareTo(batch.thresholds().hitRateDropWarning()) >= 0) {
            String severity = rateDrop.compareTo(batch.thresholds().hitRateDropCritical()) >= 0
                    ? "CRITICAL" : "WARNING";
            BigDecimal threshold = "CRITICAL".equals(severity)
                    ? batch.thresholds().hitRateDropCritical()
                    : batch.thresholds().hitRateDropWarning();
            events.add(driftEvent(batch, performance, "SUCCESS_RATE", baselineRate,
                    performance.successRate, threshold, severity,
                    Map.of("dropPercentagePoints", rateDrop,
                            "baselineSampleCount", baseline.size(), "currentSampleCount", current.size())));
        }
        return events;
    }

    private AiDriftEvent driftEvent(
            PerformanceBatch batch,
            AiFactorPerformance performance,
            String metric,
            BigDecimal baselineValue,
            BigDecimal observedValue,
            BigDecimal threshold,
            String severity,
            Map<String, Object> evidence
    ) {
        AiDriftEvent event = new AiDriftEvent();
        event.factorPerformanceId = performance.id;
        event.eventType = "FACTOR_DRIFT";
        event.subjectType = "FACTOR";
        event.subjectKey = String.join(":", performance.factorCode, performance.factorVersion,
                String.valueOf(performance.horizonDays), performance.marketRegime);
        event.detectorVersion = batch.detectorVersion();
        event.windowStartDate = batch.windowStartDate();
        event.windowEndDate = batch.windowEndDate();
        event.metricName = metric;
        event.baselineValue = baselineValue;
        event.observedValue = observedValue;
        event.thresholdValue = threshold;
        event.severity = severity;
        event.status = "OPEN";
        try {
            event.evidenceJson = objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化漂移证据", ex);
        }
        event.detectedAt = batch.evaluatedAt();
        event.createdAt = LocalDateTime.now();
        event.eventFingerprint = sha256(String.join("|", performance.inputFingerprint,
                batch.detectorVersion(), metric, severity, String.valueOf(threshold)));
        return event;
    }

    private List<AiDriftEvent> persistDriftEvents(List<AiDriftEvent> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<AiDriftEvent> sorted = candidates.stream()
                .sorted(Comparator.comparing(event -> event.eventFingerprint)).toList();
        driftMapper.insertBatchImmutable(sorted);
        List<String> fingerprints = sorted.stream().map(event -> event.eventFingerprint).toList();
        List<AiDriftEvent> persisted = driftMapper.selectByFingerprintsForShare(fingerprints);
        Map<String, AiDriftEvent> byFingerprint = new HashMap<>();
        persisted.forEach(event -> byFingerprint.put(event.eventFingerprint, event));
        List<AiDriftEvent> result = new ArrayList<>(sorted.size());
        for (AiDriftEvent expected : sorted) {
            AiDriftEvent actual = byFingerprint.get(expected.eventFingerprint);
            if (actual == null) {
                throw new IllegalStateException("漂移事件写入后未读取到记录：" + expected.eventFingerprint);
            }
            if (!Objects.equals(expected.metricName, actual.metricName)
                    || !Objects.equals(expected.subjectKey, actual.subjectKey)
                    || !Objects.equals(expected.evidenceJson, actual.evidenceJson)) {
                throw new IllegalStateException("不可变漂移事件冲突：" + expected.eventFingerprint);
            }
            result.add(actual);
        }
        return List.copyOf(result);
    }

    private static String driftStatus(
            AiFactorPerformance performance,
            List<Observation> baseline,
            DriftThresholds thresholds
    ) {
        BigDecimal rateDrop = successRate(baseline).subtract(performance.successRate);
        if (performance.psiScore.compareTo(thresholds.psiCritical()) >= 0
                || rateDrop.compareTo(thresholds.hitRateDropCritical()) >= 0) {
            return "CRITICAL";
        }
        if (performance.psiScore.compareTo(thresholds.psiWarning()) >= 0
                || rateDrop.compareTo(thresholds.hitRateDropWarning()) >= 0) {
            return "WARNING";
        }
        return "STABLE";
    }

    private static BigDecimal successRate(List<Observation> observations) {
        int success = (int) observations.stream()
                .filter(observation -> signal(observation)
                        .multiply(observation.label().excessReturn).signum() > 0)
                .count();
        return percentage(success, observations.size());
    }

    private static BigDecimal populationStabilityIndex(
            List<BigDecimal> baseline,
            List<BigDecimal> current
    ) {
        List<BigDecimal> sorted = baseline.stream().sorted().toList();
        List<BigDecimal> cuts = new ArrayList<>();
        for (int index = 1; index < 10; index++) {
            int position = (int) Math.ceil(index / 10d * sorted.size()) - 1;
            cuts.add(sorted.get(Math.max(0, Math.min(sorted.size() - 1, position))));
        }
        int[] baselineCounts = histogram(baseline, cuts);
        int[] currentCounts = histogram(current, cuts);
        double epsilon = 0.0001d;
        double result = 0d;
        for (int index = 0; index < baselineCounts.length; index++) {
            double expected = (baselineCounts[index] + epsilon)
                    / (baseline.size() + epsilon * baselineCounts.length);
            double observed = (currentCounts[index] + epsilon)
                    / (current.size() + epsilon * currentCounts.length);
            result += (observed - expected) * Math.log(observed / expected);
        }
        return BigDecimal.valueOf(result).setScale(6, RoundingMode.HALF_UP);
    }

    private static int[] histogram(List<BigDecimal> values, List<BigDecimal> cuts) {
        int[] counts = new int[cuts.size() + 1];
        for (BigDecimal value : values) {
            int bucket = 0;
            while (bucket < cuts.size() && value.compareTo(cuts.get(bucket)) > 0) {
                bucket++;
            }
            counts[bucket]++;
        }
        return counts;
    }

    private static List<BigDecimal> dailyRankIc(List<Observation> observations) {
        Map<LocalDate, List<Observation>> byDate = new LinkedHashMap<>();
        observations.stream().sorted(Comparator.comparing(observation -> observation.sample().tradeDate))
                .forEach(observation -> byDate.computeIfAbsent(
                        observation.sample().tradeDate, ignored -> new ArrayList<>()).add(observation));
        List<BigDecimal> result = new ArrayList<>();
        for (List<Observation> day : byDate.values()) {
            if (day.size() < 2) {
                continue;
            }
            List<BigDecimal> signals = day.stream().map(AiFactorPerformanceServiceImpl::signal).toList();
            List<BigDecimal> returns = day.stream().map(item -> item.label().excessReturn).toList();
            BigDecimal correlation = spearman(signals, returns);
            if (correlation != null) {
                result.add(correlation);
            }
        }
        return result;
    }

    private static BigDecimal spearman(List<BigDecimal> left, List<BigDecimal> right) {
        List<Double> leftRanks = ranks(left);
        List<Double> rightRanks = ranks(right);
        double leftMean = leftRanks.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
        double rightMean = rightRanks.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
        double covariance = 0d;
        double leftVariance = 0d;
        double rightVariance = 0d;
        for (int index = 0; index < leftRanks.size(); index++) {
            double leftDelta = leftRanks.get(index) - leftMean;
            double rightDelta = rightRanks.get(index) - rightMean;
            covariance += leftDelta * rightDelta;
            leftVariance += leftDelta * leftDelta;
            rightVariance += rightDelta * rightDelta;
        }
        if (leftVariance == 0d || rightVariance == 0d) {
            return null;
        }
        return BigDecimal.valueOf(covariance / Math.sqrt(leftVariance * rightVariance))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private static List<Double> ranks(List<BigDecimal> values) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            indexes.add(index);
        }
        indexes.sort(Comparator.comparing(values::get));
        Double[] result = new Double[values.size()];
        int cursor = 0;
        while (cursor < indexes.size()) {
            int end = cursor + 1;
            while (end < indexes.size()
                    && values.get(indexes.get(cursor)).compareTo(values.get(indexes.get(end))) == 0) {
                end++;
            }
            double averageRank = (cursor + 1 + end) / 2d;
            for (int position = cursor; position < end; position++) {
                result[indexes.get(position)] = averageRank;
            }
            cursor = end;
        }
        return List.of(result);
    }

    private static BigDecimal signal(Observation observation) {
        return AiFactorSignalPolicy.orient(
                observation.factor(), observation.factor().normalizedValue);
    }

    private static BigDecimal stability(List<BigDecimal> dailyIc) {
        if (dailyIc.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        double mean = dailyIc.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0d);
        double variance = dailyIc.stream().mapToDouble(value -> Math.pow(value.doubleValue() - mean, 2))
                .average().orElse(0d);
        double dispersion = Math.sqrt(variance);
        double consistency = dailyIc.stream().filter(value -> value.signum() == Double.compare(mean, 0d))
                .count() / (double) dailyIc.size();
        double score = consistency * 70d + (1d - Math.min(1d, dispersion)) * 30d;
        return BigDecimal.valueOf(Math.max(0d, Math.min(100d, score)))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values, int scale) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentage(int success, int total) {
        return total == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(success).multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal wilsonLowerBound(int success, int total) {
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        double z = 1.96d;
        double phat = success / (double) total;
        double denominator = 1d + z * z / total;
        double numerator = phat + z * z / (2d * total)
                - z * Math.sqrt((phat * (1d - phat) + z * z / (4d * total)) / total);
        return BigDecimal.valueOf(numerator / denominator * 100d)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static boolean validObservation(Observation observation) {
        return Objects.equals(0, observation.factor().missing)
                && observation.factor().normalizedValue != null
                && observation.factor().normalizedValue.signum() != 0
                && observation.label().excessReturn != null
                && "EXECUTED".equals(observation.label().executionStatus)
                && "MATURED".equals(observation.label().labelStatus);
    }

    private static void validateObservationLineage(
            Observation observation,
            PerformanceBatch batch,
            boolean currentWindow
    ) {
        if (observation == null || observation.sample() == null || observation.factor() == null
                || observation.label() == null || observation.sample().id == null
                || observation.factor().id == null || observation.factor().factorDefinitionId == null
                || observation.label().id == null
                || observation.sample().tradeDate == null
                || observation.sample().sourceFingerprint == null
                || observation.factor().inputFingerprint == null
                || observation.label().inputFingerprint == null
                || !Objects.equals(observation.sample().id, observation.factor().sampleId)
                || !Objects.equals(observation.sample().id, observation.label().sampleId)
                || !Objects.equals(observation.sample().stockCode, observation.factor().stockCode)
                || !Objects.equals(observation.sample().stockCode, observation.label().stockCode)
                || !Objects.equals(batch.factorVersion(), observation.factor().factorVersion)
                || !Objects.equals(batch.horizonDays(), observation.label().horizonTradingDays)
                || !Objects.equals(batch.marketRegime(), observation.sample().marketRegime)) {
            throw new IllegalArgumentException("因子观察的版本、周期或血缘不一致");
        }
        if (currentWindow && (observation.sample().tradeDate.isBefore(batch.windowStartDate())
                || observation.sample().tradeDate.isAfter(batch.windowEndDate()))) {
            throw new IllegalArgumentException("当前因子观察超出统计窗口");
        }
        if (!currentWindow && !observation.sample().tradeDate.isBefore(batch.windowStartDate())) {
            throw new IllegalArgumentException("漂移基线必须早于当前统计窗口");
        }
        LocalDateTime labelAvailableAt = labelAvailableAt(observation.label());
        if (labelAvailableAt == null || labelAvailableAt.isAfter(batch.evaluatedAt())) {
            throw new IllegalArgumentException("标签在因子表现评估时点尚不可用");
        }
    }

    private static void validateUniqueObservations(
            List<Observation> current,
            List<Observation> baseline
    ) {
        Set<String> currentKeys = new HashSet<>();
        for (Observation observation : current) {
            if (!currentKeys.add(observationKey(observation))) {
                throw new IllegalArgumentException("因子表现批次包含重复观察");
            }
        }
        Set<String> baselineKeys = new HashSet<>();
        if (baseline != null) {
            for (Observation observation : baseline) {
                String key = observationKey(observation);
                if (!baselineKeys.add(key) || currentKeys.contains(key)) {
                    throw new IllegalArgumentException("因子表现批次包含重复观察");
                }
            }
        }
    }

    private static String observationKey(Observation observation) {
        return observation.sample().id + "|" + observation.factor().factorCode
                + "|" + observation.label().id;
    }

    private static void validateBatch(PerformanceBatch batch) {
        if (batch == null || batch.factorVersion() == null || batch.factorVersion().isBlank()
                || batch.horizonDays() == null || batch.horizonDays() <= 0
                || batch.marketRegime() == null || batch.marketRegime().isBlank()
                || batch.windowType() == null || batch.windowType().isBlank()
                || batch.windowStartDate() == null || batch.windowEndDate() == null
                || batch.windowStartDate().isAfter(batch.windowEndDate())
                || batch.observations() == null || batch.detectorVersion() == null
                || batch.thresholds() == null || batch.evaluatedAt() == null) {
            throw new IllegalArgumentException("因子表现批次缺少有效窗口、版本或阈值");
        }
        DriftThresholds thresholds = batch.thresholds();
        if (thresholds.psiWarning() == null || thresholds.psiCritical() == null
                || thresholds.hitRateDropWarning() == null || thresholds.hitRateDropCritical() == null
                || thresholds.psiWarning().signum() < 0 || thresholds.psiCritical().signum() < 0
                || thresholds.hitRateDropWarning().signum() < 0
                || thresholds.hitRateDropCritical().signum() < 0
                || thresholds.psiWarning().compareTo(thresholds.psiCritical()) > 0
                || thresholds.hitRateDropWarning().compareTo(thresholds.hitRateDropCritical()) > 0) {
            throw new IllegalArgumentException("漂移阈值必须非负且 warning 不得高于 critical");
        }
    }

    private static String performanceFingerprint(
            PerformanceBatch batch,
            String factorCode,
            List<Observation> observations,
            List<Observation> baselineObservations
    ) {
        String lineage = observationFingerprint(observations);
        String baselineLineage = observationFingerprint(baselineObservations);
        return sha256(String.join("|", factorCode, batch.factorVersion(),
                String.valueOf(batch.horizonDays()), batch.marketRegime(),
                batch.windowType(), String.valueOf(batch.windowStartDate()),
                String.valueOf(batch.windowEndDate()), batch.detectorVersion(),
                String.valueOf(batch.thresholds()), lineage, baselineLineage));
    }

    private static LocalDateTime labelAvailableAt(com.maogou.stock.domain.entity.research.AiSampleLabel label) {
        if (label.labelAvailableAt != null) {
            return label.labelAvailableAt;
        }
        if (label.maturedAt != null) {
            return label.maturedAt;
        }
        return label.verifiedAt;
    }

    private static String observationFingerprint(List<Observation> observations) {
        return observations.stream()
                .sorted(Comparator.comparing(observation -> observation.sample().id))
                .map(observation -> observation.sample().sourceFingerprint + ":"
                        + observation.factor().inputFingerprint + ":" + observation.label().inputFingerprint)
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
