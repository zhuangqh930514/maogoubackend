package com.maogou.stock.dto.market;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

public record KlineSeriesSnapshot(
        String symbol,
        String period,
        String adjustmentMode,
        String source,
        LocalDateTime asOfTime,
        LocalDateTime fetchedAt,
        String sourceFingerprint,
        List<KlinePointResponse> points
) {
    public static KlineSeriesSnapshot create(
            String symbol,
            String period,
            String adjustmentMode,
            String source,
            LocalDateTime asOfTime,
            LocalDateTime fetchedAt,
            List<KlinePointResponse> points
    ) {
        List<KlinePointResponse> immutablePoints = points == null ? List.of() : List.copyOf(points);
        String fingerprint = fingerprint(symbol, period, adjustmentMode, asOfTime, immutablePoints);
        return new KlineSeriesSnapshot(
                symbol, period, adjustmentMode, source, asOfTime, fetchedAt, fingerprint, immutablePoints);
    }

    public boolean fingerprintMatches() {
        return sourceFingerprint != null
                && sourceFingerprint.equals(fingerprint(symbol, period, adjustmentMode, asOfTime, points));
    }

    private static String fingerprint(
            String symbol,
            String period,
            String adjustmentMode,
            LocalDateTime asOfTime,
            List<KlinePointResponse> points
    ) {
        String canonical = symbol + "|" + period + "|" + adjustmentMode + "|" + asOfTime + "|" + points;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
