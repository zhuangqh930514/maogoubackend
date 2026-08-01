package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.MarketSnapshot;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.mapper.MarketQuoteCurrentMapper;
import com.maogou.stock.mapper.MarketSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MarketSnapshotServiceImplTest {

    @Test
    void storesSourceFingerprintAndKeepsMissingChangeAsMissing() {
        MarketSnapshotMapper snapshotMapper = mock(MarketSnapshotMapper.class);
        MarketQuoteCurrentMapper currentMapper = mock(MarketQuoteCurrentMapper.class);
        MarketSnapshotServiceImpl service = new MarketSnapshotServiceImpl(snapshotMapper, currentMapper);
        LocalDateTime sourceAsOf = LocalDateTime.of(2026, 8, 1, 14, 58, 3);
        StockQuoteResponse quote = new StockQuoteResponse(
                "600519", "贵州茅台", BigDecimal.valueOf(1500), null, null, null, "SH", "SINA",
                sourceAsOf, "REALTIME", "LIVE", "TRADING", LocalDate.of(2026, 8, 1),
                sourceAsOf, LocalDateTime.of(2026, 8, 1, 14, 58, 5), "真实行情");

        service.recordRealtimeQuote(quote);

        ArgumentCaptor<MarketSnapshot> captor = ArgumentCaptor.forClass(MarketSnapshot.class);
        verify(snapshotMapper).insertIgnore(captor.capture());
        verify(currentMapper).upsert(org.mockito.ArgumentMatchers.any());
        assertEquals("600519", captor.getValue().symbol);
        assertEquals("SINA", captor.getValue().sourceProvider);
        assertEquals(64, captor.getValue().sourceFingerprint.length());
        assertNull(captor.getValue().changeAmount);
        assertNull(captor.getValue().changePercent);
    }
}
