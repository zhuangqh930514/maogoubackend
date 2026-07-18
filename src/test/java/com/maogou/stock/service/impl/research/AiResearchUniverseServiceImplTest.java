package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.domain.entity.research.AiResearchUniverse;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.domain.enums.TradeSide;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseSnapshotMapper;
import com.maogou.stock.service.research.AiResearchUniverseService;
import com.maogou.stock.service.research.AiSystemCoreUniverseProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiResearchUniverseServiceImplTest {

    @Test
    void usesSystemBaselineWhenConfiguredComponentsAreEmpty() {
        AiResearchUniverseMapper universeMapper = mock(AiResearchUniverseMapper.class);
        AiResearchUniverseSnapshotMapper snapshotMapper = mock(AiResearchUniverseSnapshotMapper.class);
        AiResearchUniverseItemMapper itemMapper = mock(AiResearchUniverseItemMapper.class);
        WatchStockMapper watchMapper = mock(WatchStockMapper.class);
        TradeRecordMapper tradeMapper = mock(TradeRecordMapper.class);
        AiSystemCoreUniverseProvider baselineProvider = mock(AiSystemCoreUniverseProvider.class);
        AiResearchUniverse universe = universe();
        when(universeMapper.selectOne(any())).thenReturn(universe);
        when(snapshotMapper.selectOne(any())).thenReturn(null);
        when(snapshotMapper.selectCount(any())).thenReturn(0L);
        AtomicLong sequence = new AtomicLong(20);
        when(snapshotMapper.insert(any(AiResearchUniverseSnapshot.class))).thenAnswer(invocation -> {
            AiResearchUniverseSnapshot snapshot = invocation.getArgument(0);
            snapshot.id = sequence.getAndIncrement();
            return 1;
        });
        when(itemMapper.insert(any(AiResearchUniverseItem.class))).thenAnswer(invocation -> {
            AiResearchUniverseItem item = invocation.getArgument(0);
            item.id = sequence.getAndIncrement();
            return 1;
        });
        when(watchMapper.selectList(any())).thenReturn(List.of(
                watch(5L, "002594", "比亚迪", "SZ", "2026-07-02T09:00:00")
        ));
        when(tradeMapper.selectList(any())).thenReturn(List.of());
        when(baselineProvider.baselineCandidates(any(), any(), any())).thenReturn(List.of(
                candidate("600519", "贵州茅台", "SH", "SYSTEM_BASELINE", true, null),
                candidate("300750", "宁德时代", "SZ", "SYSTEM_BASELINE", true, null)
        ));

        AiResearchUniverseService service = new AiResearchUniverseServiceImpl(
                universeMapper, snapshotMapper, itemMapper, watchMapper, tradeMapper, baselineProvider,
                new ObjectMapper()
        );

        AiResearchUniverseService.SnapshotResult result = service.createSystemCoreSnapshot(
                new AiResearchUniverseService.SnapshotRequest(
                        LocalDate.parse("2026-07-14"),
                        LocalDateTime.parse("2026-07-14T16:00:00"),
                        "CN_A_CALENDAR/2026.1",
                        List.of()
                )
        );

        assertThat(result.snapshot().qualityStatus).isEqualTo("PARTIAL");
        assertThat(result.items()).extracting(item -> item.stockCode)
                .containsExactly("002594", "300750", "600519");
        assertThat(result.items()).anySatisfy(item -> {
            assertThat(item.stockCode).isEqualTo("600519");
            assertThat(item.sourceType).isEqualTo("SYSTEM_BASELINE");
        });
        assertThat(result.items()).anySatisfy(item -> {
            assertThat(item.stockCode).isEqualTo("002594");
            assertThat(item.sourceType).isEqualTo("USER_WATCHLIST");
        });
        verify(baselineProvider).baselineCandidates(
                LocalDate.parse("2026-07-14"),
                LocalDateTime.parse("2026-07-14T16:00:00"),
                200
        );
    }

    @Test
    void mergesConfiguredComponentsAllWatchlistsAndPerUserOpenPositionsByStockCode() {
        AiResearchUniverseMapper universeMapper = mock(AiResearchUniverseMapper.class);
        AiResearchUniverseSnapshotMapper snapshotMapper = mock(AiResearchUniverseSnapshotMapper.class);
        AiResearchUniverseItemMapper itemMapper = mock(AiResearchUniverseItemMapper.class);
        WatchStockMapper watchMapper = mock(WatchStockMapper.class);
        TradeRecordMapper tradeMapper = mock(TradeRecordMapper.class);
        AiResearchUniverse universe = universe();
        when(universeMapper.selectOne(any())).thenReturn(universe);
        when(snapshotMapper.selectOne(any())).thenReturn(null);
        when(snapshotMapper.selectCount(any())).thenReturn(0L);
        AtomicLong sequence = new AtomicLong(10);
        when(snapshotMapper.insert(any(AiResearchUniverseSnapshot.class))).thenAnswer(invocation -> {
            AiResearchUniverseSnapshot snapshot = invocation.getArgument(0);
            snapshot.id = sequence.getAndIncrement();
            return 1;
        });
        when(itemMapper.insert(any(AiResearchUniverseItem.class))).thenAnswer(invocation -> {
            AiResearchUniverseItem item = invocation.getArgument(0);
            item.id = sequence.getAndIncrement();
            return 1;
        });
        when(watchMapper.selectList(any())).thenReturn(List.of(
                watch(5L, "600519", "贵州茅台", "SH", "2026-07-01T09:00:00"),
                watch(6L, "002594", "比亚迪", "SZ", "2026-07-02T09:00:00")
        ));
        when(tradeMapper.selectList(any())).thenReturn(List.of(
                trade(1L, "000001", "平安银行", TradeSide.BUY, 100, "2026-07-01T10:00:00"),
                trade(1L, "000001", "平安银行", TradeSide.SELL, 100, "2026-07-02T10:00:00"),
                trade(2L, "300750", "宁德时代", TradeSide.BUY, 200, "2026-07-03T10:00:00")
        ));

        AiResearchUniverseService service = new AiResearchUniverseServiceImpl(
                universeMapper, snapshotMapper, itemMapper, watchMapper, tradeMapper, new ObjectMapper()
        );
        AiResearchUniverseService.SnapshotResult result = service.createSystemCoreSnapshot(
                new AiResearchUniverseService.SnapshotRequest(
                        LocalDate.parse("2026-07-14"),
                        LocalDateTime.parse("2026-07-14T16:00:00"),
                        "CN_A_CALENDAR/2026.1",
                        List.of(
                                candidate("600519", "贵州茅台", "SH", "CONFIGURED_BASELINE", true, null),
                                candidate("000001", "平安银行", "SZ", "CONFIGURED_BASELINE", false, "基准配置排除")
                        )
                )
        );

        assertThat(result.reused()).isFalse();
        assertThat(result.snapshot().status).isEqualTo("FINALIZED");
        assertThat(result.snapshot().qualityStatus).isEqualTo("PARTIAL");
        assertThat(result.items()).extracting(item -> item.stockCode)
                .containsExactly("000001", "002594", "300750", "600519");
        AiResearchUniverseItem maotai = result.items().stream()
                .filter(item -> "600519".equals(item.stockCode))
                .findFirst()
                .orElseThrow();
        assertThat(maotai.sourceType).isEqualTo("CONFIGURED_BASELINE,USER_WATCHLIST");
        assertThat(maotai.included).isEqualTo(1);
        AiResearchUniverseItem closedPosition = result.items().stream()
                .filter(item -> "000001".equals(item.stockCode))
                .findFirst()
                .orElseThrow();
        assertThat(closedPosition.included).isEqualTo(0);
        assertThat(closedPosition.excludeReason).isEqualTo("基准配置排除");
        assertThat(result.items()).anySatisfy(item -> {
            assertThat(item.stockCode).isEqualTo("300750");
            assertThat(item.sourceType).isEqualTo("USER_HOLDING");
        });

        ArgumentCaptor<AiResearchUniverseItem> itemCaptor = ArgumentCaptor.forClass(AiResearchUniverseItem.class);
        verify(itemMapper, times(4)).insert(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues()).allSatisfy(item -> {
            assertThat(item.evidenceJson).contains("sourceTypes");
            assertThat(item.sourceFingerprint).hasSize(64);
        });
    }

    @Test
    void identicalFingerprintReusesImmutableSnapshotWithoutUpdatingOrReinsertingItems() {
        AiResearchUniverseMapper universeMapper = mock(AiResearchUniverseMapper.class);
        AiResearchUniverseSnapshotMapper snapshotMapper = mock(AiResearchUniverseSnapshotMapper.class);
        AiResearchUniverseItemMapper itemMapper = mock(AiResearchUniverseItemMapper.class);
        WatchStockMapper watchMapper = mock(WatchStockMapper.class);
        TradeRecordMapper tradeMapper = mock(TradeRecordMapper.class);
        AiResearchUniverseSnapshot existing = new AiResearchUniverseSnapshot();
        existing.id = 88L;
        existing.sourceFingerprint = "existing";
        AiResearchUniverseItem existingItem = new AiResearchUniverseItem();
        existingItem.universeSnapshotId = 88L;
        existingItem.stockCode = "600519";
        when(universeMapper.selectOne(any())).thenReturn(universe());
        when(snapshotMapper.selectOne(any())).thenReturn(existing);
        when(itemMapper.selectList(any())).thenReturn(List.of(existingItem));
        when(watchMapper.selectList(any())).thenReturn(List.of());
        when(tradeMapper.selectList(any())).thenReturn(List.of());

        AiResearchUniverseService service = new AiResearchUniverseServiceImpl(
                universeMapper, snapshotMapper, itemMapper, watchMapper, tradeMapper, new ObjectMapper()
        );
        AiResearchUniverseService.SnapshotResult result = service.createSystemCoreSnapshot(
                new AiResearchUniverseService.SnapshotRequest(
                        LocalDate.parse("2026-07-14"),
                        LocalDateTime.parse("2026-07-14T16:00:00"),
                        "CN_A_CALENDAR/2026.1",
                        List.of(candidate("600519", "贵州茅台", "SH", "CONFIGURED_BASELINE", true, null))
                )
        );

        assertThat(result.reused()).isTrue();
        assertThat(result.snapshot().id).isEqualTo(88L);
        verify(snapshotMapper, never()).insert(any(AiResearchUniverseSnapshot.class));
        verify(itemMapper, never()).insert(any(AiResearchUniverseItem.class));
    }

    private static AiResearchUniverse universe() {
        AiResearchUniverse universe = new AiResearchUniverse();
        universe.id = 1L;
        universe.universeCode = "CN_A_SYSTEM_CORE";
        universe.minimumStockCount = 200;
        return universe;
    }

    private static AiResearchUniverseService.UniverseCandidate candidate(
            String code,
            String name,
            String market,
            String source,
            boolean included,
            String excludeReason
    ) {
        return new AiResearchUniverseService.UniverseCandidate(
                code, name, market, source, included, excludeReason, LocalDate.parse("2026-07-01")
        );
    }

    private static WatchStock watch(Long userId, String code, String name, String market, String createdAt) {
        WatchStock stock = new WatchStock();
        stock.userId = userId;
        stock.stockCode = code;
        stock.stockName = name;
        stock.market = market;
        stock.createdAt = LocalDateTime.parse(createdAt);
        return stock;
    }

    private static TradeRecord trade(
            Long userId,
            String code,
            String name,
            TradeSide side,
            int quantity,
            String tradedAt
    ) {
        TradeRecord trade = new TradeRecord();
        trade.userId = userId;
        trade.stockCode = code;
        trade.stockName = name;
        trade.side = side;
        trade.quantity = quantity;
        trade.price = BigDecimal.TEN;
        trade.tradedAt = LocalDateTime.parse(tradedAt);
        return trade;
    }
}
