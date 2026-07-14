package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.service.research.AiGlobalResearchPreparationService;
import com.maogou.stock.service.research.AiResearchContract;
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

    private final AiStrategyReleaseMapper releaseMapper;

    public AiGlobalResearchPreparationServiceImpl(AiStrategyReleaseMapper releaseMapper) {
        this.releaseMapper = releaseMapper;
    }

    @Override
    @Transactional
    public PreparedPipeline prepare(
            LocalDate tradeDate,
            LocalDateTime startedAt,
            String idempotencyKey
    ) {
        if (tradeDate == null || startedAt == null
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("全局流水线准备缺少交易日、开始时间或幂等键");
        }
        AiStrategyRelease champion = releaseMapper.selectGlobalActiveChampionForUpdate(
                AiResearchContract.SYSTEM_UNIVERSE_CODE,
                AiResearchContract.MODEL_FAMILY);
        if (champion == null || champion.id == null
                || !"CHAMPION".equals(champion.releaseRole) || !"ACTIVE".equals(champion.status)) {
            throw new IllegalStateException("统一研究域缺少可运行的全局 Champion 基线");
        }
        String fingerprint = sha256(String.join("|",
                "GLOBAL_DAILY_RESEARCH/1.0.0",
                String.valueOf(tradeDate),
                String.valueOf(champion.id),
                String.valueOf(champion.modelVersionId)));
        return new PreparedPipeline(champion.id, champion.modelVersionId, fingerprint);
    }

    static LocalDateTime marketDataAsOf(LocalDate tradeDate) {
        return tradeDate.atTime(16, 0);
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
