package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.domain.entity.research.AiSecurityDailyState;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseSnapshotMapper;
import com.maogou.stock.mapper.research.AiSecurityDailyStateMapper;
import com.maogou.stock.service.research.HistoricalUniverseCatalogService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalUniverseCatalogServiceImplTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 16);
    private static final LocalDateTime AS_OF = TRADE_DATE.atTime(16, 0);

    @Test
    void loadsOnlyPointInTimeHistoricalItemsWithExecutableState() {
        AiResearchUniverseSnapshot snapshot = snapshot("TUSHARE", "READY");
        AiResearchUniverseItem item = item("600519", "贵州茅台", "TUSHARE_HISTORICAL_MEMBERSHIP");
        AiSecurityDailyState state = state("600519", 0, 0, 1);
        Fixture fixture = fixture(snapshot, List.of(item), List.of(state));

        HistoricalUniverseCatalogService.CatalogPlan result = service(fixture).load(
                List.of(TRADE_DATE), AS_OF, 1);

        assertThat(result.byDate()).containsKey(TRADE_DATE);
        assertThat(result.byDate().get(TRADE_DATE).snapshotId()).isEqualTo(11L);
        assertThat(result.unionSecurities()).extracting(value -> value.stockCode())
                .containsExactly("600519");
    }

    @Test
    void rejectsCurrentListingSnapshotEvenWhenItIsMarkedReady() {
        AiResearchUniverseSnapshot snapshot = snapshot("CURRENT_LISTED_HISTORICAL_COHORT", "READY");
        AiResearchUniverseItem item = item("600519", "贵州茅台", "CURRENT_LISTED_HISTORICAL_COHORT");
        Fixture fixture = fixture(snapshot, List.of(item), List.of(state("600519", 0, 0, 1)));

        assertThatThrownBy(() -> service(fixture).load(List.of(TRADE_DATE), AS_OF, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("历史股票池无法用于正式回放");
    }

    @Test
    void excludesUnknownOrNonTradableStateAndFailsTheStockGate() {
        AiResearchUniverseSnapshot snapshot = snapshot("TUSHARE", "READY");
        AiResearchUniverseItem item = item("600519", "贵州茅台", "TUSHARE_HISTORICAL_MEMBERSHIP");
        Fixture fixture = fixture(snapshot, List.of(item), List.of(state("600519", null, 0, 1)));

        assertThatThrownBy(() -> service(fixture).load(List.of(TRADE_DATE), AS_OF, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("真实可执行成分不足");
    }

    private static HistoricalUniverseCatalogService service(Fixture fixture) {
        return new HistoricalUniverseCatalogServiceImpl(
                fixture.snapshotMapper, fixture.itemMapper, fixture.stateMapper);
    }

    private static Fixture fixture(
            AiResearchUniverseSnapshot snapshot,
            List<AiResearchUniverseItem> items,
            List<AiSecurityDailyState> states
    ) {
        AiResearchUniverseSnapshotMapper snapshotMapper = mock(AiResearchUniverseSnapshotMapper.class);
        AiResearchUniverseItemMapper itemMapper = mock(AiResearchUniverseItemMapper.class);
        AiSecurityDailyStateMapper stateMapper = mock(AiSecurityDailyStateMapper.class);
        when(snapshotMapper.selectOne(any(QueryWrapper.class))).thenReturn(snapshot);
        when(itemMapper.selectList(any(QueryWrapper.class))).thenReturn(items);
        when(stateMapper.selectCurrentForStocksBetween(any(), any(), any())).thenReturn(states);
        return new Fixture(snapshotMapper, itemMapper, stateMapper);
    }

    private static AiResearchUniverseSnapshot snapshot(String sourceName, String pointInTimeStatus) {
        AiResearchUniverseSnapshot snapshot = new AiResearchUniverseSnapshot();
        snapshot.id = 11L;
        snapshot.tradeDate = TRADE_DATE;
        snapshot.asOfTime = AS_OF;
        snapshot.membershipSourceName = sourceName;
        snapshot.membershipSourceRevision = "REVISION-1";
        snapshot.sourceObservedAt = LocalDateTime.of(2026, 7, 17, 9, 0);
        snapshot.pointInTimeStatus = pointInTimeStatus;
        snapshot.pointInTimeReason = "供应商历史证券主数据按目标交易日重建";
        snapshot.sourceFingerprint = "snapshot-fingerprint";
        snapshot.qualityStatus = "READY";
        snapshot.status = "FINALIZED";
        return snapshot;
    }

    private static AiResearchUniverseItem item(String code, String name, String sourceType) {
        AiResearchUniverseItem item = new AiResearchUniverseItem();
        item.id = 21L;
        item.universeSnapshotId = 11L;
        item.stockCode = code;
        item.stockName = name;
        item.market = "SH";
        item.included = 1;
        item.listedStatus = "LISTED";
        item.effectiveFrom = LocalDate.of(2001, 1, 1);
        item.sourceType = sourceType;
        item.sourceFingerprint = "item-fingerprint";
        item.evidenceJson = "{\"source\":\"historical\"}";
        return item;
    }

    private static AiSecurityDailyState state(String code, Integer isSt, Integer suspended, Integer buyTradable) {
        AiSecurityDailyState state = new AiSecurityDailyState();
        state.stockCode = code;
        state.tradeDate = TRADE_DATE;
        state.isCurrent = 1;
        state.isSt = isSt;
        state.suspended = suspended;
        state.buyTradable = buyTradable;
        state.qualityStatus = "READY";
        state.sourceFingerprint = "state-fingerprint";
        state.evidenceJson = "{\"source\":\"historical\"}";
        return state;
    }

    private record Fixture(
            AiResearchUniverseSnapshotMapper snapshotMapper,
            AiResearchUniverseItemMapper itemMapper,
            AiSecurityDailyStateMapper stateMapper
    ) {
    }
}
