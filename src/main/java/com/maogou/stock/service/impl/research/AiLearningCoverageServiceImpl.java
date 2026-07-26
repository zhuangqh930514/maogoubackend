package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.research.AiLearningCoverageDaily;
import com.maogou.stock.mapper.research.AiLearningCoverageDailyMapper;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import com.maogou.stock.service.research.AiLearningCoverageService;
import com.maogou.stock.service.research.AiPredictionEvaluationService;
import com.maogou.stock.service.research.AiResearchContract;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AiLearningCoverageServiceImpl implements AiLearningCoverageService {
    private static final int LOOKBACK_DAYS = 16;
    private final AiLearningCoverageDailyMapper mapper;

    public AiLearningCoverageServiceImpl(AiLearningCoverageDailyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void recordDueEvaluation(Long pipelineRunId, LocalDate tradeDate,
                                    AiLabelVerificationCoordinator.VerificationResult result, LocalDateTime generatedAt) {
        for (int horizon : List.of(1, 2, 3)) {
            long eligible = mapper.countEligibleDuePredictions(tradeDate, horizon, LOOKBACK_DAYS,
                    AiResearchContract.LABEL_VERSION);
            long evaluated = mapper.countEvaluatedDuePredictions(tradeDate, horizon, LOOKBACK_DAYS,
                    AiResearchContract.LABEL_VERSION, AiPredictionEvaluationServiceImpl.VERSION);
            AiLearningCoverageDaily value = new AiLearningCoverageDaily();
            value.tradeDate = tradeDate;
            value.horizonTradingDays = horizon;
            value.pipelineRunId = pipelineRunId;
            value.eligiblePredictionCount = Math.toIntExact(eligible);
            value.matureLabelCount = Math.toIntExact(eligible);
            value.evaluationCount = Math.toIntExact(evaluated);
            value.directionAssessedCount = Math.toIntExact(evaluated);
            value.unavailableCount = 0;
            value.retryableCount = Math.max(0, Math.toIntExact(eligible - evaluated));
            value.failedCount = result == null ? 0 : result.failedCount();
            value.coverageRate = eligible == 0 ? BigDecimal.ONE : BigDecimal.valueOf(evaluated)
                    .divide(BigDecimal.valueOf(eligible), 4, RoundingMode.HALF_UP);
            value.coverageStatus = eligible == evaluated ? "COMPLETE"
                    : evaluated == 0 && result != null && !result.errors().isEmpty() ? "BLOCKED" : "PARTIAL_EXPLAINED";
            value.errorSummary = result == null || result.errors().isEmpty() ? null : String.join("；", result.errors());
            value.generatedAt = generatedAt;
            value.createdAt = generatedAt;
            value.updatedAt = generatedAt;
            mapper.upsert(value);
        }
    }

    @Override
    public List<Coverage> find(Long pipelineRunId, LocalDate tradeDate) {
        return mapper.selectList(new QueryWrapper<AiLearningCoverageDaily>()
                        .eq("pipeline_run_id", pipelineRunId).eq("trade_date", tradeDate)
                        .orderByAsc("horizon_trading_days"))
                .stream().map(value -> new Coverage(value.horizonTradingDays, value.eligiblePredictionCount,
                        value.evaluationCount, value.coverageStatus, value.errorSummary)).toList();
    }
}
