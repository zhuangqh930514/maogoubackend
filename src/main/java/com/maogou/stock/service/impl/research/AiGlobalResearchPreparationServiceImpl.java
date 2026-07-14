package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.service.research.AiGlobalResearchPreparationService;
import com.maogou.stock.service.research.AiSampleSnapshotService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class AiGlobalResearchPreparationServiceImpl implements AiGlobalResearchPreparationService {

    private final AiSampleSnapshotService snapshotService;
    private final AiStrategyReleaseMapper releaseMapper;

    public AiGlobalResearchPreparationServiceImpl(
            AiSampleSnapshotService snapshotService,
            AiStrategyReleaseMapper releaseMapper
    ) {
        this.snapshotService = snapshotService;
        this.releaseMapper = releaseMapper;
    }

    @Override
    @Transactional
    public PreparedPipeline prepare(
            Long userId,
            LocalDate tradeDate,
            LocalDateTime startedAt,
            String idempotencyKey
    ) {
        if (userId == null || tradeDate == null || startedAt == null
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("流水线准备缺少用户、交易日、开始时间或幂等键");
        }
        LocalDateTime marketDataAsOf = marketDataAsOf(tradeDate);
        AiDataBatch batch = snapshotService.startOrGetBatch(
                userId, tradeDate, "AFTER_CLOSE", marketDataAsOf, idempotencyKey + ":BATCH");
        if (batch == null || batch.id == null) {
            throw new IllegalStateException("未创建有效的数据批次");
        }
        AiStrategyRelease champion = releaseMapper.selectActiveChampionForUpdate(userId);
        if (champion == null) {
            champion = createBaseline(userId, startedAt);
        }
        if (champion.id == null || !userId.equals(champion.userId)) {
            throw new IllegalStateException("当前冠军策略无效或不属于当前用户");
        }
        String fingerprint = sha256(String.join("|",
                "DAILY_CLOSE_V2.1",
                String.valueOf(userId),
                String.valueOf(tradeDate),
                String.valueOf(batch.id),
                String.valueOf(champion.id),
                String.valueOf(champion.modelVersionId)));
        return new PreparedPipeline(batch.id, champion.id, champion.modelVersionId, fingerprint);
    }

    static LocalDateTime marketDataAsOf(LocalDate tradeDate) {
        return tradeDate.atTime(16, 0);
    }

    private AiStrategyRelease createBaseline(Long userId, LocalDateTime now) {
        AiStrategyRelease baseline = new AiStrategyRelease();
        baseline.userId = userId;
        baseline.versionNo = "RULE-BASELINE-V1";
        baseline.title = "规则基线策略";
        baseline.modelVersionId = null;
        baseline.status = "ACTIVE";
        baseline.releaseRole = "CHAMPION";
        baseline.configJson = "{\"engine\":\"RULE_BASELINE\",\"policyVersion\":\"DECISION_V2.1\"}";
        baseline.factorSnapshotJson = "{\"factorVersion\":\"2.0.0\"}";
        baseline.validationMetricsJson = "{}";
        baseline.promotionReason = "系统首次运行时创建的可追溯规则基线";
        baseline.activatedAt = now;
        baseline.createdAt = now;
        baseline.updatedAt = now;
        try {
            releaseMapper.insert(baseline);
            return baseline;
        } catch (DuplicateKeyException exception) {
            AiStrategyRelease concurrent = releaseMapper.selectActiveChampionForUpdate(userId);
            if (concurrent != null) {
                return concurrent;
            }
            throw exception;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
