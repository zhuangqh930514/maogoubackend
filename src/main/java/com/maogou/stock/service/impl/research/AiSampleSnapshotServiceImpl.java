package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.mapper.research.AiDataBatchMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.service.research.AiSampleSnapshotService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AiSampleSnapshotServiceImpl implements AiSampleSnapshotService {

    private static final String FEATURE_VERSION = "POINT_IN_TIME_V2.1";
    private static final Duration MAX_QUOTE_AGE = Duration.ofMinutes(2);

    private final AiSampleMapper sampleMapper;
    private final AiDataBatchMapper batchMapper;
    private final ObjectMapper objectMapper;

    public AiSampleSnapshotServiceImpl(
            AiSampleMapper sampleMapper,
            AiDataBatchMapper batchMapper,
            ObjectMapper objectMapper
    ) {
        this.sampleMapper = sampleMapper;
        this.batchMapper = batchMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiDataBatch startOrGetBatch(
            Long userId,
            LocalDate tradeDate,
            String samplePhase,
            LocalDateTime asOfTime,
            String idempotencyKey
    ) {
        require(userId, "userId");
        require(tradeDate, "tradeDate");
        require(asOfTime, "asOfTime");
        String normalizedKey = requireText(idempotencyKey, "idempotencyKey");
        AiDataBatch existing = findBatch(userId, normalizedKey);
        if (existing != null) {
            return existing;
        }

        AiDataBatch batch = new AiDataBatch();
        batch.userId = userId;
        batch.tradeDate = tradeDate;
        batch.samplePhase = normalize(samplePhase, "AFTER_CLOSE");
        batch.asOfTime = asOfTime;
        batch.idempotencyKey = normalizedKey;
        batch.sourceStatus = "PENDING";
        batch.qualityScore = BigDecimal.ZERO;
        batch.qualityStatus = "UNAVAILABLE";
        batch.itemCount = 0;
        batch.successCount = 0;
        batch.failedCount = 0;
        batch.status = "RUNNING";
        batch.startedAt = asOfTime;
        batch.createdAt = LocalDateTime.now();
        try {
            batchMapper.insert(batch);
            return batch;
        } catch (DuplicateKeyException ex) {
            AiDataBatch concurrent = findBatch(userId, normalizedKey);
            if (concurrent != null) {
                return concurrent;
            }
            throw ex;
        }
    }

    @Override
    public AiSample createOrGetSnapshot(SnapshotCommand command) {
        Objects.requireNonNull(command, "command");
        require(command.userId(), "userId");
        require(command.dataBatchId(), "dataBatchId");
        Objects.requireNonNull(command.tradeDate(), "tradeDate");
        require(command.asOfTime(), "asOfTime");
        StockDetailResponse detail = Objects.requireNonNull(command.detail(), "detail");
        StockQuoteResponse quote = Objects.requireNonNull(detail.quote(), "detail.quote");
        String stockCode = requireText(quote.code(), "stockCode");
        String samplePhase = normalize(command.samplePhase(), "AFTER_CLOSE");
        String universeCode = normalize(command.universeCode(), "WATCHLIST");
        String universeVersion = requireText(command.universeVersion(), "universeVersion");

        AiSample existing = findSample(command.userId(), stockCode, command.asOfTime(), samplePhase, universeVersion);
        if (existing != null) {
            return existing;
        }

        Quality quality = assessQuality(detail, command.asOfTime(), command.sectorCode());
        String snapshot = featureSnapshot(command, quality);
        AiSample sample = new AiSample();
        sample.userId = command.userId();
        sample.dataBatchId = command.dataBatchId();
        sample.stockCode = stockCode;
        sample.stockName = quote.name();
        sample.tradeDate = command.tradeDate();
        sample.samplePhase = samplePhase;
        sample.asOfTime = command.asOfTime();
        sample.universeCode = universeCode;
        sample.universeVersion = universeVersion;
        sample.marketRegime = normalize(command.marketRegime(), "UNKNOWN");
        sample.sectorCode = command.sectorCode();
        sample.sectorName = command.sectorName();
        sample.dataQualityScore = quality.score();
        sample.qualityStatus = quality.status();
        sample.tradableStatus = tradableStatus(quote, quality);
        sample.excludeReason = excludeReason(quote, quality);
        sample.featureVersion = FEATURE_VERSION;
        sample.featureSnapshot = snapshot;
        sample.sourceFingerprint = sha256(snapshot);
        sample.createdAt = LocalDateTime.now();
        try {
            sampleMapper.insert(sample);
            return sample;
        } catch (DuplicateKeyException ex) {
            AiSample concurrent = findSample(command.userId(), stockCode, command.asOfTime(), samplePhase, universeVersion);
            if (concurrent != null) {
                return concurrent;
            }
            throw ex;
        }
    }

    @Override
    public AiDataBatch completeBatch(Long batchId, BatchCompletion completion) {
        require(batchId, "batchId");
        Objects.requireNonNull(completion, "completion");
        AiDataBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalStateException("数据批次不存在：" + batchId);
        }
        batch.sourceStatus = normalize(completion.sourceStatus(), "UNAVAILABLE");
        batch.qualityScore = completion.qualityScore() == null ? BigDecimal.ZERO : completion.qualityScore();
        batch.qualityStatus = normalize(completion.qualityStatus(), "UNAVAILABLE");
        batch.itemCount = Math.max(0, completion.itemCount());
        batch.successCount = Math.max(0, completion.successCount());
        batch.failedCount = Math.max(0, completion.failedCount());
        batch.errorMessage = completion.errorMessage();
        batch.status = batch.failedCount == 0
                ? "SUCCESS"
                : batch.successCount == 0 ? "FAILED" : "PARTIAL_SUCCESS";
        batch.completedAt = completion.completedAt() == null ? LocalDateTime.now() : completion.completedAt();
        batchMapper.updateById(batch);
        return batch;
    }

    @Override
    public List<AiSample> findBatchSnapshots(Long userId, Long batchId, LocalDate tradeDate) {
        require(userId, "userId");
        require(batchId, "batchId");
        Objects.requireNonNull(tradeDate, "tradeDate");
        return List.copyOf(sampleMapper.selectList(new QueryWrapper<AiSample>()
                .eq("user_id", userId)
                .eq("data_batch_id", batchId)
                .eq("trade_date", tradeDate)
                .orderByAsc("stock_code")));
    }

    private AiDataBatch findBatch(Long userId, String idempotencyKey) {
        return batchMapper.selectOne(new QueryWrapper<AiDataBatch>()
                .eq("user_id", userId)
                .eq("idempotency_key", idempotencyKey)
                .last("LIMIT 1"));
    }

    private AiSample findSample(Long userId, String stockCode, LocalDateTime asOfTime, String samplePhase, String universeVersion) {
        return sampleMapper.selectOne(new QueryWrapper<AiSample>()
                .eq("user_id", userId)
                .eq("stock_code", stockCode)
                .eq("as_of_time", asOfTime)
                .eq("sample_phase", samplePhase)
                .eq("universe_version", universeVersion)
                .last("LIMIT 1"));
    }

    private Quality assessQuality(StockDetailResponse detail, LocalDateTime asOfTime, String sectorCode) {
        StockQuoteResponse quote = detail.quote();
        BigDecimal score = BigDecimal.ZERO;
        boolean quoteReady = quote != null
                && quote.price() != null
                && quote.price().compareTo(BigDecimal.ZERO) > 0
                && realSource(quote.source())
                && quote.fetchedAt() != null
                && !quote.fetchedAt().isAfter(asOfTime.plusSeconds(5))
                && !Duration.between(quote.fetchedAt(), asOfTime).isNegative()
                && Duration.between(quote.fetchedAt(), asOfTime).compareTo(MAX_QUOTE_AGE) <= 0;
        if (quoteReady) {
            score = score.add(new BigDecimal("35"));
        }

        List<KlinePointResponse> klines = detail.kline() == null ? List.of() : detail.kline();
        LocalDate lastKlineDate = klines.stream()
                .map(KlinePointResponse::tradeDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        boolean klineReady = lastKlineDate != null
                && !lastKlineDate.isAfter(asOfTime.toLocalDate())
                && !lastKlineDate.isBefore(asOfTime.toLocalDate().minusDays(7));
        if (klineReady) {
            score = score.add(new BigDecimal("30"));
        }
        if (klines.size() >= 20) {
            score = score.add(new BigDecimal("15"));
        }
        if (hasFinance(detail.finance())) {
            score = score.add(new BigDecimal("10"));
        }
        if (detail.intraday() != null && !detail.intraday().isEmpty()) {
            score = score.add(new BigDecimal("5"));
        }
        if (sectorCode != null && !sectorCode.isBlank() && !"UNKNOWN".equalsIgnoreCase(sectorCode)) {
            score = score.add(new BigDecimal("5"));
        }

        if (!quoteReady || !klineReady || score.compareTo(new BigDecimal("60")) < 0) {
            return new Quality(score, "UNAVAILABLE", quoteReady, klineReady, lastKlineDate);
        }
        return new Quality(score, score.compareTo(new BigDecimal("80")) >= 0 ? "READY" : "PARTIAL", true, true, lastKlineDate);
    }

    private String featureSnapshot(SnapshotCommand command, Quality quality) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("asOfTime", command.asOfTime());
        snapshot.put("quote", command.detail().quote());
        snapshot.put("finance", command.detail().finance());
        snapshot.put("intraday", command.detail().intraday() == null ? List.of() : command.detail().intraday());
        snapshot.put("kline", command.detail().kline() == null ? List.of() : command.detail().kline());
        snapshot.put("marketRegime", normalize(command.marketRegime(), "UNKNOWN"));
        snapshot.put("sectorCode", command.sectorCode());
        snapshot.put("sectorName", command.sectorName());
        snapshot.put("qualityStatus", quality.status());
        snapshot.put("featureVersion", FEATURE_VERSION);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化 V2 样本快照", ex);
        }
    }

    private static String tradableStatus(StockQuoteResponse quote, Quality quality) {
        if (!"READY".equals(quality.status()) && !"PARTIAL".equals(quality.status())) {
            return "DATA_UNAVAILABLE";
        }
        String name = quote.name() == null ? "" : quote.name().toUpperCase(Locale.ROOT);
        return name.contains("ST") ? "ST_RESTRICTED" : "TRADABLE";
    }

    private static String excludeReason(StockQuoteResponse quote, Quality quality) {
        if (!quality.quoteReady()) {
            return "实时行情不可用或已过期";
        }
        if (!quality.klineReady()) {
            return "K线数据不可用或越过样本时点";
        }
        String name = quote.name() == null ? "" : quote.name().toUpperCase(Locale.ROOT);
        return name.contains("ST") ? "ST 风险股票" : null;
    }

    private static boolean realSource(String source) {
        String normalized = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        return !normalized.isBlank()
                && !normalized.contains("MOCK")
                && !"LOCAL_TEST_FIXTURE".equals(normalized)
                && !"LOCAL_FALLBACK".equals(normalized);
    }

    private static boolean hasFinance(FinanceSnapshotResponse finance) {
        return finance != null && java.util.stream.Stream.of(
                        finance.pe(), finance.pb(), finance.revenue(), finance.roe())
                .filter(Objects::nonNull)
                .anyMatch(item -> item.compareTo(BigDecimal.ZERO) != 0);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private record Quality(
            BigDecimal score,
            String status,
            boolean quoteReady,
            boolean klineReady,
            LocalDate lastKlineDate
    ) {
    }
}
