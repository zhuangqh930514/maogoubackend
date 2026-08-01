package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.MarketQuoteCurrent;
import com.maogou.stock.domain.entity.MarketSnapshot;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.mapper.MarketQuoteCurrentMapper;
import com.maogou.stock.mapper.MarketSnapshotMapper;
import com.maogou.stock.service.MarketSnapshotService;
import com.maogou.stock.service.UserPositionSnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class MarketSnapshotServiceImpl implements MarketSnapshotService {
    private final MarketSnapshotMapper snapshotMapper;
    private final MarketQuoteCurrentMapper currentMapper;
    private final UserPositionSnapshotService positionSnapshotService;

    public MarketSnapshotServiceImpl(
            MarketSnapshotMapper snapshotMapper,
            MarketQuoteCurrentMapper currentMapper
    ) {
        this(snapshotMapper, currentMapper, null);
    }

    @Autowired
    public MarketSnapshotServiceImpl(
            MarketSnapshotMapper snapshotMapper,
            MarketQuoteCurrentMapper currentMapper,
            UserPositionSnapshotService positionSnapshotService
    ) {
        this.snapshotMapper = snapshotMapper;
        this.currentMapper = currentMapper;
        this.positionSnapshotService = positionSnapshotService;
    }

    @Override
    public void recordRealtimeQuote(StockQuoteResponse quote) {
        if (quote == null || !quote.hasUsablePrice()
                || !"REALTIME".equalsIgnoreCase(quote.sourceStatus())
                || quote.sourceAsOf() == null) {
            return;
        }
        LocalDateTime sourceAsOf = quote.sourceAsOf();
        LocalDate tradeDate = quote.tradeDate() == null ? sourceAsOf.toLocalDate() : quote.tradeDate();
        String fingerprint = fingerprint(quote, sourceAsOf);

        MarketSnapshot snapshot = new MarketSnapshot();
        snapshot.symbol = quote.code();
        snapshot.name = quote.name();
        snapshot.market = quote.market();
        snapshot.latestPrice = quote.price();
        snapshot.changeAmount = quote.change();
        snapshot.changePercent = quote.percent();
        snapshot.volumeRatio = quote.volumeRatio();
        snapshot.quoteTime = sourceAsOf;
        snapshot.tradeDate = tradeDate;
        snapshot.sourceProvider = quote.source();
        snapshot.sourceStatus = quote.sourceStatus();
        snapshot.dataMode = quote.dataMode();
        snapshot.sourceFingerprint = fingerprint;
        snapshotMapper.insertIgnore(snapshot);

        MarketQuoteCurrent current = new MarketQuoteCurrent();
        current.symbol = quote.code();
        current.name = quote.name();
        current.market = quote.market();
        current.latestPrice = quote.price();
        current.changeAmount = quote.change();
        current.changePercent = quote.percent();
        current.volumeRatio = quote.volumeRatio();
        current.tradeDate = tradeDate;
        current.sourceProvider = quote.source();
        current.sourceAsOf = sourceAsOf;
        current.sourceFingerprint = fingerprint;
        current.sourceStatus = quote.sourceStatus();
        current.dataMode = quote.dataMode();
        current.updatedAt = LocalDateTime.now();
        currentMapper.upsert(current);
        if (positionSnapshotService != null) {
            positionSnapshotService.refreshForQuote(quote);
        }
    }

    private static String fingerprint(StockQuoteResponse quote, LocalDateTime sourceAsOf) {
        String value = String.join("|",
                safe(quote.code()), safe(quote.name()), safe(quote.price()), safe(quote.change()),
                safe(quote.percent()), safe(quote.volumeRatio()), safe(quote.source()), sourceAsOf.toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
