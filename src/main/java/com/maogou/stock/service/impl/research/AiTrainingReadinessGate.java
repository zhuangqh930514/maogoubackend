package com.maogou.stock.service.impl.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AiTrainingReadinessGate {

    public static final int MINIMUM_TRADING_DAYS = 120;
    public static final int MINIMUM_STOCKS = 200;
    public static final int MINIMUM_LABELS_PER_HORIZON = 20_000;
    public static final int MINIMUM_REGIME_DAYS = 20;
    public static final List<Integer> CORE_HORIZONS = List.of(1, 2, 3, 5);
    public static final List<String> REQUIRED_REGIMES = List.of("UP", "DOWN", "SIDEWAYS");

    public Readiness evaluate(Evidence evidence) {
        if (evidence == null || evidence.tradingDays() < 0 || evidence.stocks() < 0) {
            throw new IllegalArgumentException("训练就绪证据不能为负或为空");
        }
        Map<Integer, Integer> actualLabels = new LinkedHashMap<>();
        Map<Integer, Integer> remainingLabels = new LinkedHashMap<>();
        for (Integer horizon : CORE_HORIZONS) {
            int actual = Math.max(0, evidence.matureExecutableLabels().getOrDefault(horizon, 0));
            actualLabels.put(horizon, actual);
            remainingLabels.put(horizon, Math.max(0, MINIMUM_LABELS_PER_HORIZON - actual));
        }

        Map<String, Integer> actualRegimes = normalizedRegimes(evidence.regimeTradingDays());
        Map<String, Integer> remainingRegimes = new LinkedHashMap<>();
        List<String> missingRegimes = new ArrayList<>();
        for (String regime : REQUIRED_REGIMES) {
            int remaining = Math.max(0, MINIMUM_REGIME_DAYS - actualRegimes.getOrDefault(regime, 0));
            remainingRegimes.put(regime, remaining);
            if (remaining > 0) {
                missingRegimes.add(regime);
            }
        }

        int remainingTradingDays = Math.max(0, MINIMUM_TRADING_DAYS - evidence.tradingDays());
        int remainingStocks = Math.max(0, MINIMUM_STOCKS - evidence.stocks());
        boolean ready = remainingTradingDays == 0
                && remainingStocks == 0
                && remainingLabels.values().stream().allMatch(value -> value == 0)
                && missingRegimes.isEmpty();
        return new Readiness(
                ready ? "READY" : "INSUFFICIENT_DATA",
                evidence.tradingDays(), evidence.stocks(), Map.copyOf(actualLabels), Map.copyOf(actualRegimes),
                remainingTradingDays, remainingStocks, Map.copyOf(remainingLabels),
                Map.copyOf(remainingRegimes), List.copyOf(missingRegimes));
    }

    private static Map<String, Integer> normalizedRegimes(Map<String, Integer> raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        raw.forEach((key, count) -> {
            String normalized = normalizeRegime(key);
            if (normalized != null) {
                result.merge(normalized, Math.max(0, count == null ? 0 : count), Integer::sum);
            }
        });
        return result;
    }

    private static String normalizeRegime(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "UP", "BULL", "STRONG", "UPTREND" -> "UP";
            case "DOWN", "BEAR", "WEAK", "DOWNTREND" -> "DOWN";
            case "SIDEWAYS", "RANGE", "NEUTRAL" -> "SIDEWAYS";
            default -> null;
        };
    }

    public record Evidence(
            int tradingDays,
            int stocks,
            Map<Integer, Integer> matureExecutableLabels,
            Map<String, Integer> regimeTradingDays
    ) {
        public Evidence {
            matureExecutableLabels = matureExecutableLabels == null
                    ? Map.of() : Map.copyOf(matureExecutableLabels);
            regimeTradingDays = regimeTradingDays == null ? Map.of() : Map.copyOf(regimeTradingDays);
        }
    }

    public record Readiness(
            String status,
            int tradingDays,
            int stocks,
            Map<Integer, Integer> matureExecutableLabels,
            Map<String, Integer> regimeTradingDays,
            int remainingTradingDays,
            int remainingStocks,
            Map<Integer, Integer> remainingLabels,
            Map<String, Integer> remainingRegimeDays,
            List<String> missingRegimes
    ) {
    }
}
