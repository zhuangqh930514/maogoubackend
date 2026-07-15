package com.maogou.stock.service.impl.research;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSampleSnapshotServiceImplTest {

    @Test
    void returnsExistingSnapshotWithoutMutatingIt() {
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AiSample existing = new AiSample();
        existing.id = 12L;
        existing.featureSnapshot = "original";
        existing.asOfTime = LocalDateTime.of(2026, 7, 10, 16, 0);
        when(sampleMapper.selectOne(any())).thenReturn(existing);
        AiSampleSnapshotService service = service(sampleMapper, mock(AiDataBatchMapper.class));

        AiSample result = service.createOrGetSnapshot(command(existing.asOfTime, freshDetail(existing.asOfTime)));

        assertThat(result).isSameAs(existing);
        assertThat(result.featureSnapshot).isEqualTo("original");
        verify(sampleMapper, never()).insert(any(AiSample.class));
        verify(sampleMapper, never()).updateById(any(AiSample.class));
    }

    @Test
    void createsANewImmutableSnapshotForANewAsOfTime() {
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        when(sampleMapper.selectOne(any())).thenReturn(null);
        AiSampleSnapshotService service = service(sampleMapper, mock(AiDataBatchMapper.class));
        LocalDateTime asOf = LocalDateTime.of(2026, 7, 10, 16, 5);

        AiSample result = service.createOrGetSnapshot(command(asOf, freshDetail(asOf)));

        ArgumentCaptor<AiSample> captor = ArgumentCaptor.forClass(AiSample.class);
        verify(sampleMapper).insert(captor.capture());
        assertThat(result).isSameAs(captor.getValue());
        assertThat(result.asOfTime).isEqualTo(asOf);
        assertThat(result.qualityStatus).isEqualTo("READY");
        assertThat(result.sourceFingerprint).hasSize(64);
        verify(sampleMapper, never()).updateById(any(AiSample.class));
    }

    @Test
    void usesTheUniverseNameWhenTheQuoteProviderReturnsTheStockCodeAsName() {
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        when(sampleMapper.selectOne(any())).thenReturn(null);
        AiSampleSnapshotService service = service(sampleMapper, mock(AiDataBatchMapper.class));
        LocalDateTime asOf = LocalDateTime.of(2026, 7, 10, 16, 5);
        StockDetailResponse detail = withQuoteName(freshDetail(asOf), "600519");

        AiSample result = service.createOrGetSnapshot(command(asOf, detail));

        assertThat(result.stockName).isEqualTo("贵州茅台");
    }

    @Test
    void marksSnapshotUnavailableWhenQuoteIsStale() {
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        when(sampleMapper.selectOne(any())).thenReturn(null);
        AiSampleSnapshotService service = service(sampleMapper, mock(AiDataBatchMapper.class));
        LocalDateTime asOf = LocalDateTime.of(2026, 7, 10, 16, 0);
        StockDetailResponse detail = freshDetail(asOf.minusMinutes(10));

        AiSample result = service.createOrGetSnapshot(command(asOf, detail));

        assertThat(result.qualityStatus).isEqualTo("UNAVAILABLE");
        assertThat(result.dataQualityScore).isLessThan(new BigDecimal("60"));
    }

    @Test
    void returnsExistingDataBatchForTheSameIdempotencyKey() {
        AiDataBatchMapper batchMapper = mock(AiDataBatchMapper.class);
        AiDataBatch existing = new AiDataBatch();
        existing.id = 7L;
        existing.idempotencyKey = "AUTO_CLOSE:5:2026-07-10";
        when(batchMapper.selectOne(any())).thenReturn(existing);
        AiSampleSnapshotService service = service(mock(AiSampleMapper.class), batchMapper);

        AiDataBatch result = service.startOrGetBatch(
                5L,
                LocalDate.of(2026, 7, 10),
                "AFTER_CLOSE",
                LocalDateTime.of(2026, 7, 10, 16, 0),
                existing.idempotencyKey
        );

        assertThat(result).isSameAs(existing);
        verify(batchMapper, never()).insert(any(AiDataBatch.class));
    }

    @Test
    void storesBatchCaptureTimeAtDatabaseMillisecondPrecision() {
        AiDataBatchMapper batchMapper = mock(AiDataBatchMapper.class);
        when(batchMapper.selectOne(any())).thenReturn(null);
        AiSampleSnapshotService service = service(mock(AiSampleMapper.class), batchMapper);
        LocalDateTime capturedAt = LocalDateTime.of(2026, 7, 10, 16, 0, 0, 336_723_875);

        AiDataBatch result = service.startOrGetBatch(
                5L, LocalDate.of(2026, 7, 10), "AFTER_CLOSE", capturedAt, "GLOBAL:2026-07-10");

        assertThat(result.asOfTime).isEqualTo(LocalDateTime.of(2026, 7, 10, 16, 0, 0, 336_000_000));
        assertThat(result.startedAt).isEqualTo(result.asOfTime);
        verify(batchMapper).insert(result);
    }

    private static AiSampleSnapshotService service(AiSampleMapper sampleMapper, AiDataBatchMapper batchMapper) {
        return new AiSampleSnapshotServiceImpl(sampleMapper, batchMapper, new ObjectMapper().findAndRegisterModules());
    }

    private static AiSampleSnapshotService.SnapshotCommand command(LocalDateTime asOf, StockDetailResponse detail) {
        return new AiSampleSnapshotService.SnapshotCommand(
                5L,
                9L,
                LocalDate.of(2026, 7, 10),
                "AFTER_CLOSE",
                asOf,
                "RANGE",
                "BK0475",
                "白酒",
                "贵州茅台",
                detail
        );
    }

    private static StockDetailResponse withQuoteName(StockDetailResponse detail, String stockName) {
        StockQuoteResponse quote = detail.quote();
        return new StockDetailResponse(new StockQuoteResponse(
                quote.code(), stockName, quote.price(), quote.change(), quote.percent(),
                quote.volumeRatio(), quote.market(), quote.source(), quote.fetchedAt()),
                detail.finance(), detail.intraday(), detail.kline(), detail.aiAdvice(), detail.aiScore());
    }

    private static StockDetailResponse freshDetail(LocalDateTime quoteTime) {
        StockQuoteResponse quote = new StockQuoteResponse(
                "600519",
                "贵州茅台",
                new BigDecimal("1460.00"),
                new BigDecimal("10.00"),
                new BigDecimal("0.69"),
                new BigDecimal("1.20"),
                "SH",
                "TENCENT",
                quoteTime
        );
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        List<KlinePointResponse> klines = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> new KlinePointResponse(
                        tradeDate.minusDays(24L - index),
                        new BigDecimal("1400"),
                        new BigDecimal("1410").add(new BigDecimal(index)),
                        new BigDecimal("1390"),
                        new BigDecimal("1430").add(new BigDecimal(index)),
                        100000L,
                        new BigDecimal("100000000")
                ))
                .toList();
        return new StockDetailResponse(quote, FinanceSnapshotResponse.empty(), List.of(), klines, "", 0);
    }
}
