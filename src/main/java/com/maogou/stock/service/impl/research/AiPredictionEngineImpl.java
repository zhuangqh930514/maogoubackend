package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.service.research.AiDecisionPolicy;
import com.maogou.stock.service.research.AiFactorSignalPolicy;
import com.maogou.stock.service.research.AiModelInferenceService;
import com.maogou.stock.service.research.AiPredictionEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AiPredictionEngineImpl implements AiPredictionEngine {

    static final String POLICY_VERSION = "POLICY_V2_1";
    private static final int BATCH_SIZE = 200;
    private static final int EXPECTED_FACTOR_COUNT = 16;
    private static final BigDecimal ONE = BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP);

    private final AiPredictionMapper predictionMapper;
    private final AiDecisionPolicy decisionPolicy;
    private final AiModelInferenceService modelInferenceService;
    private final ObjectMapper objectMapper;

    public AiPredictionEngineImpl(
            AiPredictionMapper predictionMapper,
            AiDecisionPolicy decisionPolicy,
            AiModelInferenceService modelInferenceService,
            ObjectMapper objectMapper
    ) {
        this.predictionMapper = predictionMapper;
        this.decisionPolicy = decisionPolicy;
        this.modelInferenceService = modelInferenceService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<AiPrediction> predictAndStore(PredictionBatch batch) {
        validateBatch(batch);
        Long userId = batch.inputs().get(0).sample().userId;
        String universeFingerprint = universeFingerprint(batch);
        List<Candidate> candidates = batch.inputs().stream()
                .map(input -> buildCandidate(input, batch, universeFingerprint))
                .toList();

        assignRanks(candidates, batch);
        for (Candidate candidate : candidates) {
            AiPrediction prediction = candidate.prediction();
            if (candidate.modelError() != null) {
                prediction.rankNo = null;
                prediction.action = "UNAVAILABLE";
                prediction.actionBucket = "UNAVAILABLE";
                prediction.targetDirection = "SIDEWAYS";
                prediction.abstainReason = "MODEL_INFERENCE_UNAVAILABLE";
                prediction.reasonJson = reasonJson(candidate, null);
                continue;
            }
            AiDecisionPolicy.Decision decision = decisionPolicy.decide(
                    candidate.sample(),
                    new AiDecisionPolicy.Signal(
                            prediction.score,
                            prediction.riskScore,
                            prediction.calibratedConfidence,
                            prediction.expectedReturn),
                    prediction.rankNo,
                    batch.topK());
            prediction.action = decision.action();
            prediction.actionBucket = decision.actionBucket();
            prediction.targetDirection = decision.targetDirection();
            prediction.abstainReason = decision.abstainReason();
            prediction.reasonJson = reasonJson(candidate, decision);
        }

        return persistImmutable(userId, candidates.stream().map(Candidate::prediction).toList());
    }

    private Candidate buildCandidate(
            PredictionInput input,
            PredictionBatch batch,
            String universeFingerprint
    ) {
        AiSample sample = input.sample();
        List<AiFactorValue> factors = input.factors() == null ? List.of() : List.copyOf(input.factors());
        List<FactorContribution> contributions = new ArrayList<>();
        int availableCount = 0;
        for (AiFactorValue factor : factors) {
            if (factor == null || factor.missing != null && factor.missing == 1 || factor.normalizedValue == null) {
                continue;
            }
            availableCount++;
            BigDecimal bounded = clamp(factor.normalizedValue, new BigDecimal("-3"), new BigDecimal("3"));
            BigDecimal contribution = contribution(factor, bounded);
            contributions.add(new FactorContribution(factor.factorCode, bounded, contribution));
        }

        double averageContribution = contributions.stream()
                .mapToDouble(item -> item.contribution().doubleValue())
                .average()
                .orElse(0d);
        double quality = sample.dataQualityScore == null ? 0d : sample.dataQualityScore.doubleValue();
        double completeness = Math.min(1d, availableCount / (double) EXPECTED_FACTOR_COUNT);
        double scoreValue = clamp(50d + averageContribution * 15d, 0d, 100d);
        double volatilityRisk = contributions.stream()
                .filter(item -> item.factorCode().contains("VOLATILITY"))
                .mapToDouble(item -> Math.max(0d, item.rawNormalized().doubleValue()))
                .sum() * 10d;
        double riskValue = clamp(
                28d
                        + Math.max(0d, -averageContribution) * 16d
                        + volatilityRisk
                        + (1d - completeness) * 30d
                        + Math.max(0d, 80d - quality) * 0.35d,
                0d,
                100d);
        double confidenceValue = clamp(
                quality * 0.55d + completeness * 30d + Math.min(15d, Math.abs(scoreValue - 50d) * 0.5d),
                0d,
                95d);
        double horizonScale = Math.sqrt(batch.horizonDays() / 3d);
        double expectedReturnValue = ((scoreValue - 50d) / 50d) * 0.06d * horizonScale;
        double expectedExcessValue = expectedReturnValue - riskValue / 100d * 0.01d;
        double probabilityUpValue = 1d / (1d + Math.exp(-(scoreValue - 50d) / 10d));
        AiModelInferenceService.ModelInference modelInference = null;
        String modelError = null;
        if (batch.modelVersionId() != null) {
            try {
                modelInference = modelInferenceService.infer(
                        sample.userId, batch.modelVersionId(), sample, factors);
                probabilityUpValue = clamp(modelInference.probabilityUp(), 0d, 1d);
                scoreValue = probabilityUpValue * 100d;
                confidenceValue = Math.max(probabilityUpValue, 1d - probabilityUpValue) * 100d;
                expectedExcessValue = (probabilityUpValue - 0.5d) * 0.12d * horizonScale;
                expectedReturnValue = expectedExcessValue;
            } catch (RuntimeException exception) {
                modelError = conciseRootMessage(exception);
                probabilityUpValue = 0.5d;
                scoreValue = 50d;
                confidenceValue = 0d;
                riskValue = 100d;
                expectedReturnValue = 0d;
                expectedExcessValue = 0d;
            }
        }

        AiPrediction prediction = new AiPrediction();
        prediction.userId = sample.userId;
        prediction.sampleId = sample.id;
        prediction.strategyReleaseId = batch.strategyReleaseId();
        prediction.modelVersionId = batch.modelVersionId();
        prediction.stockCode = sample.stockCode;
        prediction.tradeDate = sample.tradeDate;
        prediction.samplePhase = sample.samplePhase;
        prediction.inferenceMode = normalize(batch.inferenceMode());
        prediction.horizonDays = batch.horizonDays();
        prediction.expectedReturn = decimal(expectedReturnValue, 6);
        prediction.expectedExcessReturn = decimal(expectedExcessValue, 6);
        prediction.probabilityUp = decimal(probabilityUpValue, 6);
        prediction.probabilityDown = ONE.subtract(prediction.probabilityUp).setScale(6, RoundingMode.HALF_UP);
        prediction.calibratedConfidence = decimal(confidenceValue, 4);
        prediction.score = decimal(scoreValue, 4);
        prediction.riskScore = decimal(riskValue, 4);
        prediction.predictedAt = batch.predictedAt();
        prediction.createdAt = LocalDateTime.now();
        prediction.idempotencyKey = idempotencyKey(prediction, batch);
        prediction.inputFingerprint = inputFingerprint(
                input,
                batch,
                universeFingerprint,
                modelInference == null ? batch.modelVersionId() == null ? "RULE_BASELINE" : "MODEL_UNAVAILABLE"
                        : modelInference.artifactChecksum());
        return new Candidate(sample, factors, contributions, prediction, modelInference, modelError);
    }

    private static void assignRanks(List<Candidate> candidates, PredictionBatch batch) {
        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (!rankable(candidate)) {
                candidate.prediction().rankNo = null;
                continue;
            }
            AiSample sample = candidate.sample();
            String key = String.join("|",
                    String.valueOf(sample.userId),
                    String.valueOf(sample.tradeDate),
                    String.valueOf(sample.samplePhase),
                    String.valueOf(sample.universeCode),
                    String.valueOf(sample.universeVersion),
                    normalize(batch.inferenceMode()),
                    String.valueOf(batch.strategyReleaseId()),
                    String.valueOf(batch.horizonDays()));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }
        Comparator<Candidate> ranking = Comparator
                .comparing((Candidate candidate) -> candidate.prediction().score, Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.prediction().riskScore)
                .thenComparing(candidate -> candidate.prediction().stockCode);
        for (List<Candidate> group : groups.values()) {
            group.sort(ranking);
            for (int index = 0; index < group.size(); index++) {
                group.get(index).prediction().rankNo = index + 1;
            }
        }
    }

    private static boolean rankable(Candidate candidate) {
        AiSample sample = candidate.sample();
        return candidate.modelError() == null
                && sample != null
                && ("READY".equals(sample.qualityStatus) || "PARTIAL".equals(sample.qualityStatus))
                && "TRADABLE".equals(sample.tradableStatus)
                && sample.dataQualityScore != null
                && sample.dataQualityScore.compareTo(new BigDecimal("60")) >= 0
                && candidate.prediction().calibratedConfidence.compareTo(new BigDecimal("60")) >= 0;
    }

    private List<AiPrediction> persistImmutable(Long userId, List<AiPrediction> predictions) {
        List<AiPrediction> sorted = predictions.stream()
                .sorted(Comparator.comparing(item -> item.idempotencyKey))
                .toList();
        Map<String, AiPrediction> resultByKey = new HashMap<>();
        for (int offset = 0; offset < sorted.size(); offset += BATCH_SIZE) {
            List<AiPrediction> batch = new ArrayList<>(sorted.subList(
                    offset, Math.min(offset + BATCH_SIZE, sorted.size())));
            ensureUniqueKeys(batch);
            predictionMapper.insertBatchImmutable(batch);
            List<String> keys = batch.stream().map(item -> item.idempotencyKey).toList();
            List<AiPrediction> persisted = predictionMapper.selectByIdempotencyKeysForShare(userId, keys);
            Map<String, AiPrediction> persistedByKey = new HashMap<>();
            persisted.forEach(item -> persistedByKey.put(item.idempotencyKey, item));
            for (AiPrediction expected : batch) {
                AiPrediction actual = persistedByKey.get(expected.idempotencyKey);
                if (actual == null) {
                    throw new IllegalStateException("预测批量写入后未读取到记录：" + expected.idempotencyKey);
                }
                if (!Objects.equals(expected.inputFingerprint, actual.inputFingerprint)) {
                    throw new IllegalStateException("不可变预测冲突：" + expected.idempotencyKey);
                }
                resultByKey.put(actual.idempotencyKey, actual);
            }
        }
        return predictions.stream().map(item -> resultByKey.get(item.idempotencyKey)).toList();
    }

    private String reasonJson(Candidate candidate, AiDecisionPolicy.Decision decision) {
        List<Map<String, Object>> factorReasons = candidate.contributions().stream()
                .sorted(Comparator.comparing(
                        (FactorContribution item) -> item.contribution().abs(), Comparator.reverseOrder()))
                .limit(5)
                .map(item -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("factorCode", item.factorCode());
                    value.put("normalizedValue", item.rawNormalized());
                    value.put("contribution", item.contribution());
                    return value;
                })
                .toList();
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("policyVersion", POLICY_VERSION);
        reason.put("action", decision == null ? "UNAVAILABLE" : decision.action());
        reason.put("actionBucket", decision == null ? "UNAVAILABLE" : decision.actionBucket());
        reason.put("factors", factorReasons);
        reason.put("missingFactorCount", candidate.factors().size() - candidate.contributions().size());
        if (candidate.modelInference() != null) {
            AiModelInferenceService.ModelInference inference = candidate.modelInference();
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("status", "SUCCESS");
            model.put("modelVersionId", inference.modelVersionId());
            model.put("modelKey", inference.modelKey());
            model.put("versionNo", inference.versionNo());
            model.put("artifactChecksum", inference.artifactChecksum());
            model.put("rawOutput", inference.rawOutput());
            model.put("probabilityUp", inference.probabilityUp());
            model.put("featureCount", inference.featureCount());
            model.put("matchedFeatureCount", inference.matchedFeatureCount());
            model.put("expectedReturnMethod", "CALIBRATED_DIRECTION_PROBABILITY_PROXY");
            reason.put("modelInference", model);
        } else if (candidate.modelError() != null) {
            reason.put("modelInference", Map.of(
                    "status", "UNAVAILABLE",
                    "modelVersionId", candidate.prediction().modelVersionId,
                    "message", candidate.modelError()));
        }
        try {
            return objectMapper.writeValueAsString(reason);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化预测理由", ex);
        }
    }

    private static BigDecimal contribution(AiFactorValue factor, BigDecimal normalized) {
        return AiFactorSignalPolicy.orient(factor, normalized);
    }

    private static String universeFingerprint(PredictionBatch batch) {
        String canonical = batch.inputs().stream()
                .sorted(Comparator.comparing(input -> input.sample().id))
                .map(input -> input.sample().id + ":" + input.sample().sourceFingerprint + ":"
                        + factorFingerprint(input.factors()))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        return sha256(canonical);
    }

    private static String inputFingerprint(
            PredictionInput input,
            PredictionBatch batch,
            String universeFingerprint,
            String modelEvidence
    ) {
        return sha256(String.join("|",
                POLICY_VERSION,
                universeFingerprint,
                String.valueOf(input.sample().sourceFingerprint),
                factorFingerprint(input.factors()),
                String.valueOf(batch.strategyReleaseId()),
                String.valueOf(batch.modelVersionId()),
                String.valueOf(batch.horizonDays()),
                String.valueOf(batch.topK()),
                normalize(batch.inferenceMode()),
                modelEvidence));
    }

    private static String factorFingerprint(List<AiFactorValue> factors) {
        if (factors == null) {
            return "";
        }
        return factors.stream()
                .sorted(Comparator.comparing(item -> item.factorCode == null ? "" : item.factorCode))
                .map(item -> String.valueOf(item.inputFingerprint))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static String idempotencyKey(AiPrediction prediction, PredictionBatch batch) {
        return String.join(":",
                normalize(batch.inferenceMode()),
                String.valueOf(batch.strategyReleaseId()),
                batch.modelVersionId() == null ? "BASELINE" : String.valueOf(batch.modelVersionId()),
                String.valueOf(prediction.sampleId),
                String.valueOf(batch.horizonDays()),
                "K" + batch.topK(),
                POLICY_VERSION);
    }

    private static void validateBatch(PredictionBatch batch) {
        if (batch == null || batch.inputs() == null || batch.inputs().isEmpty()) {
            throw new IllegalArgumentException("预测批次不能为空");
        }
        if (batch.strategyReleaseId() == null || batch.strategyReleaseId() <= 0) {
            throw new IllegalArgumentException("strategyReleaseId 必须是有效外键");
        }
        if (batch.horizonDays() <= 0 || batch.topK() <= 0 || batch.predictedAt() == null) {
            throw new IllegalArgumentException("预测周期、Top K 和预测时点必须有效");
        }
        String inferenceMode = normalize(batch.inferenceMode());
        if ("RULE_BASELINE".equals(inferenceMode) && batch.modelVersionId() != null) {
            throw new IllegalArgumentException("RULE_BASELINE 的 modelVersionId 必须为 NULL");
        }
        if (!"RULE_BASELINE".equals(inferenceMode)
                && (batch.modelVersionId() == null || batch.modelVersionId() <= 0)) {
            throw new IllegalArgumentException("模型推理的 modelVersionId 必须是有效外键");
        }
        Long userId = null;
        String rankingScope = null;
        LinkedHashSet<Long> sampleIds = new LinkedHashSet<>();
        for (PredictionInput input : batch.inputs()) {
            if (input == null || input.sample() == null || input.sample().id == null
                    || input.sample().userId == null || input.sample().tradeDate == null
                    || input.sample().samplePhase == null || input.sample().sourceFingerprint == null) {
                throw new IllegalArgumentException("预测输入缺少不可变样本字段");
            }
            if (userId == null) {
                userId = input.sample().userId;
            } else if (!userId.equals(input.sample().userId)) {
                throw new IllegalArgumentException("一个预测批次只能包含同一用户");
            }
            if (!sampleIds.add(input.sample().id)) {
                throw new IllegalArgumentException("预测批次包含重复样本：" + input.sample().id);
            }
            String inputScope = String.join("|",
                    String.valueOf(input.sample().tradeDate),
                    String.valueOf(input.sample().samplePhase),
                    String.valueOf(input.sample().universeCode),
                    String.valueOf(input.sample().universeVersion));
            if (rankingScope == null) {
                rankingScope = inputScope;
            } else if (!rankingScope.equals(inputScope)) {
                throw new IllegalArgumentException("一个排名批次必须属于同一交易日、阶段和股票池版本");
            }
            validateFactorLineage(input);
        }
    }

    private static void validateFactorLineage(PredictionInput input) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        List<AiFactorValue> factors = input.factors() == null ? List.of() : input.factors();
        for (AiFactorValue factor : factors) {
            if (factor == null
                    || !Objects.equals(factor.userId, input.sample().userId)
                    || !Objects.equals(factor.sampleId, input.sample().id)
                    || !Objects.equals(factor.stockCode, input.sample().stockCode)
                    || factor.factorCode == null
                    || factor.factorVersion == null
                    || factor.inputFingerprint == null) {
                throw new IllegalArgumentException("预测因子血缘与样本不一致");
            }
            if (!codes.add(factor.factorCode + "@" + factor.factorVersion)) {
                throw new IllegalArgumentException("预测因子列表包含重复版本：" + factor.factorCode);
            }
        }
    }

    private static void ensureUniqueKeys(List<AiPrediction> predictions) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (AiPrediction prediction : predictions) {
            if (!keys.add(prediction.idempotencyKey)) {
                throw new IllegalArgumentException("预测批次包含重复幂等键：" + prediction.idempotencyKey);
            }
        }
    }

    private static BigDecimal decimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.max(min).min(max);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "RULE_BASELINE" : value.trim().toUpperCase();
    }

    private static String conciseRootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        String value = message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record FactorContribution(
            String factorCode,
            BigDecimal rawNormalized,
            BigDecimal contribution
    ) {
    }

    private record Candidate(
            AiSample sample,
            List<AiFactorValue> factors,
            List<FactorContribution> contributions,
            AiPrediction prediction,
            AiModelInferenceService.ModelInference modelInference,
            String modelError
    ) {
    }
}
