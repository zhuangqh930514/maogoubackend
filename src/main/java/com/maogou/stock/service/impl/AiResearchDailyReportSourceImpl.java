package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiDailyInsightItem;
import com.maogou.stock.domain.entity.AiDailyInsightSnapshot;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.v2.AiFactorPerformanceV2;
import com.maogou.stock.domain.entity.v2.AiPipelineRun;
import com.maogou.stock.domain.entity.v2.AiPipelineStep;
import com.maogou.stock.domain.entity.v2.AiPortfolioBacktestRun;
import com.maogou.stock.domain.entity.v2.AiStrategyRelease;
import com.maogou.stock.dto.ai.AiResearchDailyReportPayloads;
import com.maogou.stock.mapper.AiDailyInsightItemMapper;
import com.maogou.stock.mapper.AiDailyInsightSnapshotMapper;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.v2.AiFactorPerformanceV2Mapper;
import com.maogou.stock.mapper.v2.AiPipelineRunMapper;
import com.maogou.stock.mapper.v2.AiPipelineStepMapper;
import com.maogou.stock.mapper.v2.AiPortfolioBacktestRunMapper;
import com.maogou.stock.mapper.v2.AiStrategyReleaseMapper;
import com.maogou.stock.service.v2.AiResearchDailyReportSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AiResearchDailyReportSourceImpl implements AiResearchDailyReportSource {

    private final AiDailyInsightSnapshotMapper snapshotMapper;
    private final AiDailyInsightItemMapper itemMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final AiStrategyReleaseMapper strategyReleaseMapper;
    private final AiPortfolioBacktestRunMapper backtestRunMapper;
    private final AiFactorPerformanceV2Mapper factorPerformanceMapper;
    private final AiPipelineRunMapper pipelineRunMapper;
    private final AiPipelineStepMapper pipelineStepMapper;
    private final ObjectMapper objectMapper;

    public AiResearchDailyReportSourceImpl(
            AiDailyInsightSnapshotMapper snapshotMapper,
            AiDailyInsightItemMapper itemMapper,
            TradeRecordMapper tradeRecordMapper,
            AiStrategyReleaseMapper strategyReleaseMapper,
            AiPortfolioBacktestRunMapper backtestRunMapper,
            AiFactorPerformanceV2Mapper factorPerformanceMapper,
            AiPipelineRunMapper pipelineRunMapper,
            AiPipelineStepMapper pipelineStepMapper,
            ObjectMapper objectMapper
    ) {
        this.snapshotMapper = snapshotMapper;
        this.itemMapper = itemMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.strategyReleaseMapper = strategyReleaseMapper;
        this.backtestRunMapper = backtestRunMapper;
        this.factorPerformanceMapper = factorPerformanceMapper;
        this.pipelineRunMapper = pipelineRunMapper;
        this.pipelineStepMapper = pipelineStepMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReportSource load(Long userId, LocalDate tradeDate, PipelineRequest pipelineRequest) {
        AiDailyInsightSnapshot snapshot = snapshotMapper.selectOne(new QueryWrapper<AiDailyInsightSnapshot>()
                .eq("user_id", userId)
                .eq("trade_date", tradeDate)
                .last("LIMIT 1"));
        List<AiDailyInsightItem> items = snapshot == null
                ? List.of()
                : itemMapper.selectList(new QueryWrapper<AiDailyInsightItem>()
                .eq("snapshot_id", snapshot.id)
                .orderByDesc("composite_score"));
        List<TradeRecord> holdings = tradeRecordMapper.selectList(new QueryWrapper<TradeRecord>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .eq("side", "BUY")
                .orderByDesc("traded_at"));

        AiStrategyRelease release = pipelineRequest.strategyReleaseId() == null
                ? null
                : strategyReleaseMapper.selectById(pipelineRequest.strategyReleaseId());
        AiResearchDailyReportPayloads.StrategyPerformance strategyPerformance = buildStrategyPerformance(
                userId, release, pipelineRequest.modelVersionId(), tradeDate);
        AiResearchDailyReportPayloads.PipelineSummary pipeline = buildPipelineSummary(userId, pipelineRequest);
        String marketRegime = inferMarketRegime(items, snapshot, pipelineRequest.pipelineStatus());
        return new ReportSource(snapshot, items, holdings, marketRegime, strategyPerformance, pipeline);
    }

    private AiResearchDailyReportPayloads.StrategyPerformance buildStrategyPerformance(
            Long userId,
            AiStrategyRelease release,
            Long modelVersionId,
            LocalDate tradeDate
    ) {
        if (release == null) {
            return new AiResearchDailyReportPayloads.StrategyPerformance(
                    null, "UNKNOWN", "未绑定策略版本", modelVersionId,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    0, BigDecimal.ZERO, "UNKNOWN");
        }
        JsonNode metrics = parse(release.validationMetricsJson);
        String factorVersion = text(parse(release.factorSnapshotJson), "factorVersion", "factor_version");
        Long backtestRunId = longValue(metrics, "validationBacktestRunId", "validation_backtest_run_id", "backtestRunId");
        AiPortfolioBacktestRun backtest = backtestRunId == null ? null : backtestRunMapper.selectById(backtestRunId);
        String drift = loadDriftStatus(userId, factorVersion, tradeDate);
        return new AiResearchDailyReportPayloads.StrategyPerformance(
                release.id,
                release.versionNo,
                release.title,
                modelVersionId,
                backtest == null ? BigDecimal.ZERO : nullToZero(backtest.totalReturn),
                backtest == null ? BigDecimal.ZERO : nullToZero(backtest.alpha),
                backtest == null ? BigDecimal.ZERO : nullToZero(backtest.maxDrawdown),
                backtest == null ? BigDecimal.ZERO : nullToZero(backtest.sharpeRatio),
                backtest == null ? 0 : backtest.tradeCount,
                decimal(metrics, "shadowHitRate", "shadow_hit_rate", "hitRate"),
                drift);
    }

    private String loadDriftStatus(Long userId, String factorVersion, LocalDate tradeDate) {
        if (factorVersion == null || factorVersion.isBlank()) {
            return "UNKNOWN";
        }
        List<AiFactorPerformanceV2> performance = factorPerformanceMapper.selectList(
                new QueryWrapper<AiFactorPerformanceV2>()
                        .eq("user_id", userId)
                        .eq("factor_version", factorVersion)
                        .eq("horizon_days", 3)
                        .eq("market_regime", "UNKNOWN")
                        .eq("window_type", "ROLLING_60D")
                        .le("window_end_date", tradeDate)
                        .orderByDesc("window_end_date")
                        .orderByDesc("evaluated_at")
                        .last("LIMIT 100"));
        if (performance.stream().anyMatch(item -> "CRITICAL".equals(item.driftStatus))) {
            return "CRITICAL";
        }
        if (performance.stream().anyMatch(item -> "WARNING".equals(item.driftStatus))) {
            return "WARNING";
        }
        return performance.stream().map(item -> item.driftStatus)
                .filter(Objects::nonNull).filter(item -> !item.isBlank())
                .findFirst().orElse("UNKNOWN");
    }

    private AiResearchDailyReportPayloads.PipelineSummary buildPipelineSummary(Long userId, PipelineRequest request) {
        if (request.pipelineRunId() == null) {
            return new AiResearchDailyReportPayloads.PipelineSummary(
                    null,
                    request.pipelineStatus(),
                    request.failedStep(),
                    request.failedStep(),
                    0, 0, 0,
                    request.pipelineMessage(),
                    List.of());
        }
        AiPipelineRun run = pipelineRunMapper.selectById(request.pipelineRunId());
        List<AiPipelineStep> steps = pipelineStepMapper.selectByRunIdForUpdate(request.pipelineRunId());
        boolean reportWillBeReady = "SUCCESS".equals(request.pipelineStatus())
                || "PARTIAL_SUCCESS".equals(request.pipelineStatus());
        List<AiResearchDailyReportPayloads.PipelineStep> projectedSteps = steps == null
                ? List.of()
                : steps.stream()
                .map(step -> {
                    boolean currentReportStep = reportWillBeReady
                            && "BUILD_RESEARCH_DAILY_REPORT".equals(step.stepKey);
                    return new AiResearchDailyReportPayloads.PipelineStep(
                            step.stepKey,
                            currentReportStep ? "SUCCESS" : step.status,
                            currentReportStep ? 1 : value(step.inputCount),
                            currentReportStep ? 1 : value(step.outputCount),
                            currentReportStep ? null : step.errorMessage);
                })
                .toList();
        int processed = projectedSteps.stream().mapToInt(AiResearchDailyReportPayloads.PipelineStep::inputCount).sum();
        int success = projectedSteps.stream().mapToInt(AiResearchDailyReportPayloads.PipelineStep::outputCount).sum();
        int failed = projectedSteps.stream()
                .mapToInt(step -> "FAILED".equals(step.status())
                        ? Math.max(1, step.inputCount() - step.outputCount())
                        : Math.max(0, step.inputCount() - step.outputCount()))
                .sum();
        return new AiResearchDailyReportPayloads.PipelineSummary(
                request.pipelineRunId(),
                request.pipelineStatus() == null || request.pipelineStatus().isBlank()
                        ? run == null ? "UNKNOWN" : run.status
                        : request.pipelineStatus(),
                run == null ? request.failedStep() : run.currentStep,
                request.failedStep(),
                processed,
                success,
                failed,
                request.pipelineMessage() == null ? run == null ? null : run.errorMessage : request.pipelineMessage(),
                projectedSteps);
    }

    private static String inferMarketRegime(
            List<AiDailyInsightItem> items,
            AiDailyInsightSnapshot snapshot,
            String pipelineStatus
    ) {
        if (items.stream().filter(item -> "RECOMMEND".equals(item.actionBucket)).count() >= 3) {
            return "TRENDING";
        }
        if (snapshot != null && "REALTIME".equals(snapshot.freshnessStatus)
                && snapshot.dataQualityScore != null
                && snapshot.dataQualityScore.compareTo(new BigDecimal("80")) >= 0
                && "SUCCESS".equals(pipelineStatus)) {
            return "BALANCED";
        }
        return "DEFENSIVE";
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private JsonNode parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (IOException exception) {
            return null;
        }
    }

    private static String text(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode candidate = node.get(key);
            if (candidate != null && !candidate.isNull()) {
                String value = candidate.asText();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Long longValue(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode candidate = node.get(key);
            if (candidate != null && candidate.canConvertToLong()) {
                return candidate.longValue();
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String... keys) {
        if (node == null) {
            return BigDecimal.ZERO;
        }
        for (String key : keys) {
            JsonNode candidate = node.get(key);
            if (candidate != null && !candidate.isNull()) {
                return new BigDecimal(candidate.asText("0"));
            }
        }
        return BigDecimal.ZERO;
    }
}
