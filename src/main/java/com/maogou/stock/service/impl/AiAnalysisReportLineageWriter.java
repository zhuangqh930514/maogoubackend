package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.research.AiAnalysisReportPrediction;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.research.AiAnalysisReportPredictionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AiAnalysisReportLineageWriter {

    private static final Map<Integer, BigDecimal> HORIZON_WEIGHTS = Map.of(
            1, new BigDecimal("0.200000"),
            2, new BigDecimal("0.300000"),
            3, new BigDecimal("0.500000"),
            5, new BigDecimal("0.000000"));

    private final AiAnalysisReportMapper reportMapper;
    private final AiAnalysisReportPredictionMapper linkMapper;

    public AiAnalysisReportLineageWriter(
            AiAnalysisReportMapper reportMapper,
            AiAnalysisReportPredictionMapper linkMapper
    ) {
        this.reportMapper = reportMapper;
        this.linkMapper = linkMapper;
    }

    @Transactional
    public AiAnalysisReport persistVersion(AiAnalysisReport draft, List<AiPrediction> predictions) {
        validate(draft);
        List<AiPrediction> linkedPredictions = validatePredictions(draft, predictions);
        if (reportMapper.lockUser(draft.userId) == null) {
            throw new IllegalArgumentException("用户不存在，无法保存个股报告");
        }
        AiAnalysisReport previous = reportMapper.selectLatestVersionForUpdate(
                draft.userId, draft.stockCode, draft.reportDate);
        draft.id = null;
        draft.reportVersion = previous == null || previous.reportVersion == null
                ? 1 : previous.reportVersion + 1;
        draft.supersedesReportId = previous == null ? null : previous.id;
        draft.idempotencyKey = "analysis-report:" + draft.userId + ":" + draft.stockCode
                + ":" + draft.reportDate + ":v" + draft.reportVersion;
        LocalDateTime now = draft.generatedAt == null ? LocalDateTime.now() : draft.generatedAt;
        draft.createdAt = now;
        draft.updatedAt = now;
        reportMapper.insert(draft);
        if (draft.id == null) {
            throw new IllegalStateException("个股报告写入后缺少主键");
        }

        linkedPredictions.stream()
                .filter(item -> item.id != null && HORIZON_WEIGHTS.containsKey(item.horizonDays))
                .sorted(Comparator.comparingInt(item -> item.horizonDays))
                .forEach(prediction -> linkMapper.insert(link(draft, prediction, now)));
        return draft;
    }

    private static AiAnalysisReportPrediction link(
            AiAnalysisReport report,
            AiPrediction prediction,
            LocalDateTime createdAt
    ) {
        AiAnalysisReportPrediction link = new AiAnalysisReportPrediction();
        link.userId = report.userId;
        link.reportId = report.id;
        link.predictionId = prediction.id;
        link.purpose = switch (prediction.horizonDays) {
            case 1 -> "T1_SIGNAL";
            case 2 -> "T2_SIGNAL";
            case 3 -> "PRIMARY_RANKING";
            case 5 -> "RESEARCH_CONTEXT";
            default -> throw new IllegalArgumentException("不支持的报告预测周期：" + prediction.horizonDays);
        };
        link.weight = HORIZON_WEIGHTS.get(prediction.horizonDays);
        link.createdAt = createdAt;
        return link;
    }

    private static void validate(AiAnalysisReport report) {
        Objects.requireNonNull(report, "个股报告不能为空");
        Objects.requireNonNull(report.userId, "个股报告缺少用户");
        Objects.requireNonNull(report.sampleId, "个股报告缺少正式样本");
        Objects.requireNonNull(report.strategyReleaseId, "个股报告缺少正式策略发布");
        Objects.requireNonNull(report.stockCode, "个股报告缺少股票代码");
        Objects.requireNonNull(report.reportDate, "个股报告缺少报告日期");
        Objects.requireNonNull(report.status, "个股报告缺少状态");
    }

    private static List<AiPrediction> safePredictions(List<AiPrediction> predictions) {
        return predictions == null ? List.of() : predictions.stream().filter(Objects::nonNull).toList();
    }

    private static List<AiPrediction> validatePredictions(
            AiAnalysisReport report,
            List<AiPrediction> predictions
    ) {
        List<AiPrediction> values = safePredictions(predictions);
        long distinctHorizons = values.stream().map(item -> item.horizonDays).distinct().count();
        if (distinctHorizons != values.size()) {
            throw new IllegalArgumentException("个股报告每个预测周期只能关联一个正式预测");
        }
        for (AiPrediction prediction : values) {
            if (!HORIZON_WEIGHTS.containsKey(prediction.horizonDays)
                    || !Objects.equals(prediction.sampleId, report.sampleId)
                    || !Objects.equals(prediction.strategyReleaseId, report.strategyReleaseId)) {
                throw new IllegalArgumentException("个股报告预测与样本、策略发布或周期不一致");
            }
        }
        return values;
    }
}
