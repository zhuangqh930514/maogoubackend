package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.UserPositionSnapshot;
import com.maogou.stock.dto.portfolio.TradePositionAggregate;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.UserPositionSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserPositionSnapshotServiceImplTest {

    @Test
    void missingQuoteKeepsLastPriceButMarksSnapshotStale() {
        TradeRecordMapper tradeRecordMapper = mock(TradeRecordMapper.class);
        UserPositionSnapshotMapper snapshotMapper = mock(UserPositionSnapshotMapper.class);
        TradePositionAggregate position = new TradePositionAggregate();
        position.stockCode = "600519";
        position.stockName = "贵州茅台";
        position.totalCost = new BigDecimal("1000");
        position.quantity = 100;
        UserPositionSnapshot previous = new UserPositionSnapshot();
        previous.currentPrice = new BigDecimal("12");
        previous.totalCost = new BigDecimal("1000");
        previous.quantity = 100;
        previous.calculationStatus = "AVAILABLE";
        previous.quoteStatus = "REALTIME";
        when(tradeRecordMapper.selectActivePosition(5L, "600519")).thenReturn(position);
        when(snapshotMapper.selectByUserAndStock(5L, "600519")).thenReturn(previous);

        new UserPositionSnapshotServiceImpl(tradeRecordMapper, snapshotMapper)
                .rebuild(5L, "600519", null);

        ArgumentCaptor<UserPositionSnapshot> captor = ArgumentCaptor.forClass(UserPositionSnapshot.class);
        verify(snapshotMapper).upsert(captor.capture());
        UserPositionSnapshot actual = captor.getValue();
        assertThat(actual.currentPrice).isEqualByComparingTo("12");
        assertThat(actual.calculationStatus).isEqualTo("STALE");
        assertThat(actual.unavailableReason).contains("上一次真实行情快照");
    }
}
