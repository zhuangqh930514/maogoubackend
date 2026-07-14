package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.mapper.research.AiPredictionEvaluationMapper;
import com.maogou.stock.service.research.AiPredictionEvaluationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AiPredictionEvaluationServiceImpl implements AiPredictionEvaluationService {

    public static final String VERSION = "EVALUATION/1.0.0";

    private final AiPredictionEvaluationMapper evaluationMapper;
    private final ObjectMapper objectMapper;

    public AiPredictionEvaluationServiceImpl(
            AiPredictionEvaluationMapper evaluationMapper,
            ObjectMapper objectMapper
    ) {
        this.evaluationMapper = evaluationMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<AiPredictionEvaluation> evaluateAndStore(EvaluationBatch batch) {
        validate(batch);
        Map<LabelKey, AiSampleLabel> labels = indexLabels(batch.labels());
        List<AiPredictionEvaluation> evaluations = new ArrayList<>();
        for (PredictionInput prediction : batch.predictions()) {
            AiSampleLabel label = labels.get(new LabelKey(
                    prediction.sampleId(), prediction.horizonTradingDays()));
            if (label == null || !"MATURED".equalsIgnoreCase(label.labelStatus)) {
                continue;
            }
            AiPredictionEvaluation candidate = evaluate(
                    prediction,
                    label,
                    batch.evaluationVersion(),
                    batch.evaluatedAt()
            );
            AiPredictionEvaluation existing = findExisting(
                    prediction.predictionId(), label.id, batch.evaluationVersion());
            if (existing != null) {
                if (!Objects.equals(existing.evidenceJson, candidate.evidenceJson)) {
                    throw new IllegalStateException("prediction evaluation immutable evidence conflict");
                }
                evaluations.add(existing);
                continue;
            }
            try {
                evaluationMapper.insert(candidate);
                evaluations.add(candidate);
            } catch (DuplicateKeyException duplicate) {
                AiPredictionEvaluation raced = findExisting(
                        prediction.predictionId(), label.id, batch.evaluationVersion());
                if (raced == null) {
                    throw duplicate;
                }
                if (!Objects.equals(raced.evidenceJson, candidate.evidenceJson)) {
                    throw new IllegalStateException("prediction evaluation immutable evidence conflict", duplicate);
                }
                evaluations.add(raced);
            }
        }
        return List.copyOf(evaluations);
    }

    private AiPredictionEvaluation evaluate(
            PredictionInput prediction,
            AiSampleLabel label,
            String evaluationVersion,
            LocalDateTime evaluatedAt
    ) {
        String action = normalizeAction(prediction.action(), prediction.actionBucket());
        String perspective = perspective(action);
        AiPredictionEvaluation evaluation = new AiPredictionEvaluation();
        evaluation.predictionId = prediction.predictionId();
        evaluation.sampleLabelId = label.id;
        evaluation.evaluationVersion = evaluationVersion;
        evaluation.netReturn = label.netReturn;
        evaluation.excessReturn = label.excessReturn;
        evaluation.evaluatedAt = evaluatedAt;
        evaluation.createdAt = LocalDateTime.now();

        if (!"EXECUTED".equalsIgnoreCase(label.executionStatus)) {
            evaluation.evaluationStatus = "NOT_EXECUTABLE";
        } else if ("ABSTAIN".equals(perspective)) {
            evaluation.evaluationStatus = "ABSTAIN";
        } else {
            evaluation.evaluationStatus = "EVALUATED";
            evaluation.directionCorrect = directionCorrect(prediction.targetDirection(), label.actualDirection);
            evaluation.actionEffective = actionEffective(perspective, label.netReturn, label.excessReturn);
            evaluation.probabilityError = probabilityError(
                    prediction.probabilityUp(), prediction.probabilityDown(), label.actualDirection);
            evaluation.predictedReturnError = absoluteDifference(prediction.expectedReturn(), label.netReturn);
        }
        evaluation.evidenceJson = evidence(prediction, label, action, perspective, evaluation);
        return evaluation;
    }

    private AiPredictionEvaluation findExisting(Long predictionId, Long labelId, String evaluationVersion) {
        return evaluationMapper.selectOne(
                new QueryWrapper<AiPredictionEvaluation>()
                        .eq("prediction_id", predictionId)
                        .eq("sample_label_id", labelId)
                        .eq("evaluation_version", evaluationVersion)
                        .last("LIMIT 1")
        );
    }

    private String evidence(
            PredictionInput prediction,
            AiSampleLabel label,
            String action,
            String perspective,
            AiPredictionEvaluation evaluation
    ) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("predictionInputFingerprint", prediction.inputFingerprint());
        evidence.put("labelInputFingerprint", label.inputFingerprint);
        evidence.put("action", action);
        evidence.put("perspective", perspective);
        evidence.put("targetDirection", prediction.targetDirection());
        evidence.put("actualDirection", label.actualDirection);
        evidence.put("expectedReturn", prediction.expectedReturn());
        evidence.put("probabilityUp", prediction.probabilityUp());
        evidence.put("probabilityDown", prediction.probabilityDown());
        evidence.put("netReturn", label.netReturn);
        evidence.put("excessReturn", label.excessReturn);
        evidence.put("maxFavorableReturn", label.maxFavorableReturn);
        evidence.put("maxAdverseReturn", label.maxAdverseReturn);
        evidence.put("executionStatus", label.executionStatus);
        evidence.put("executionReason", label.executionReason);
        evidence.put("directionCorrect", evaluation.directionCorrect);
        evidence.put("actionEffective", evaluation.actionEffective);
        evidence.put("evaluationStatus", evaluation.evaluationStatus);
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化预测评价证据", exception);
        }
    }

    private static Map<LabelKey, AiSampleLabel> indexLabels(List<AiSampleLabel> labels) {
        Map<LabelKey, AiSampleLabel> result = new HashMap<>();
        for (AiSampleLabel label : labels) {
            if (label == null || label.sampleId == null || label.horizonTradingDays == null) {
                continue;
            }
            LabelKey key = new LabelKey(label.sampleId, label.horizonTradingDays);
            AiSampleLabel previous = result.putIfAbsent(key, label);
            if (previous != null && !Objects.equals(previous.inputFingerprint, label.inputFingerprint)) {
                throw new IllegalArgumentException("评价批次包含冲突的样本标签");
            }
        }
        return result;
    }

    private static Integer directionCorrect(String predicted, String actual) {
        if (predicted == null || predicted.isBlank() || actual == null || actual.isBlank()) {
            return null;
        }
        return predicted.trim().equalsIgnoreCase(actual.trim()) ? 1 : 0;
    }

    private static Integer actionEffective(
            String perspective,
            BigDecimal netReturn,
            BigDecimal excessReturn
    ) {
        if (netReturn == null) {
            return null;
        }
        if ("POSITIVE_RETURN".equals(perspective)) {
            return netReturn.signum() > 0 && (excessReturn == null || excessReturn.signum() > 0) ? 1 : 0;
        }
        if ("AVOIDED_LOSS".equals(perspective)) {
            return netReturn.signum() < 0 ? 1 : 0;
        }
        return null;
    }

    private static BigDecimal probabilityError(
            BigDecimal probabilityUp,
            BigDecimal probabilityDown,
            String actualDirection
    ) {
        if (actualDirection == null) {
            return null;
        }
        if ("UP".equalsIgnoreCase(actualDirection) && probabilityUp != null) {
            return square(BigDecimal.ONE.subtract(probabilityUp));
        }
        if ("DOWN".equalsIgnoreCase(actualDirection) && probabilityDown != null) {
            return square(BigDecimal.ONE.subtract(probabilityDown));
        }
        if ("SIDEWAYS".equalsIgnoreCase(actualDirection)
                && probabilityUp != null && probabilityDown != null) {
            return square(probabilityUp).add(square(probabilityDown))
                    .setScale(6, RoundingMode.HALF_UP);
        }
        return null;
    }

    private static BigDecimal square(BigDecimal value) {
        return value.multiply(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal absoluteDifference(BigDecimal expected, BigDecimal actual) {
        return expected == null || actual == null
                ? null
                : expected.subtract(actual).abs().setScale(6, RoundingMode.HALF_UP);
    }

    private static String normalizeAction(String action, String actionBucket) {
        String value = action == null || action.isBlank() ? actionBucket : action;
        return value == null ? "WATCH" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String perspective(String action) {
        if (List.of("BUY", "HOLD", "ADD", "RECOMMEND", "POSITIVE", "OPPORTUNITY").contains(action)) {
            return "POSITIVE_RETURN";
        }
        if (List.of("REDUCE", "SELL", "AVOID", "DEFENSIVE", "RISK").contains(action)) {
            return "AVOIDED_LOSS";
        }
        return "ABSTAIN";
    }

    private static void validate(EvaluationBatch batch) {
        if (batch == null || batch.predictions().isEmpty() || batch.labels().isEmpty()
                || batch.evaluationVersion() == null || batch.evaluationVersion().isBlank()
                || batch.evaluatedAt() == null) {
            throw new IllegalArgumentException("预测评价批次缺少预测、标签、版本或评价时间");
        }
        if (!VERSION.equals(batch.evaluationVersion())) {
            throw new IllegalArgumentException("评价版本必须与固化的评价规则一致");
        }
        if (batch.predictions().stream().anyMatch(prediction -> prediction == null
                || prediction.predictionId() == null || prediction.sampleId() == null
                || prediction.horizonTradingDays() == null
                || prediction.inputFingerprint() == null || prediction.inputFingerprint().isBlank())) {
            throw new IllegalArgumentException("预测评价输入不完整");
        }
    }

    private record LabelKey(Long sampleId, Integer horizon) {
    }
}
