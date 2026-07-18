package com.maogou.stock.service.impl.research;

import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.service.research.AiResearchUniverseService;
import com.maogou.stock.service.research.AiSystemCoreUniverseProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AiSystemCoreUniverseProviderImpl implements AiSystemCoreUniverseProvider {

    static final String SOURCE_TYPE = "SYSTEM_BASELINE";

    private static final int DEFAULT_TARGET_COUNT = 240;
    private static final int MINIMUM_TARGET_COUNT = 200;
    private static final int MAX_CATALOG_SIZE = 1000;
    private static final int MIN_LISTED_DAYS = 60;

    private final List<HistoricalMarketDataProvider> providers;

    public AiSystemCoreUniverseProviderImpl(List<HistoricalMarketDataProvider> providers) {
        this.providers = orderedProviders(providers);
    }

    @Override
    public List<AiResearchUniverseService.UniverseCandidate> baselineCandidates(
            LocalDate tradeDate,
            LocalDateTime asOfTime,
            Integer minimumStockCount
    ) {
        if (tradeDate == null || asOfTime == null) {
            throw new IllegalArgumentException("系统研究池缺少交易日或研究时点");
        }
        int minimum = Math.max(MINIMUM_TARGET_COUNT, minimumStockCount == null ? 0 : minimumStockCount);
        int targetCount = Math.max(DEFAULT_TARGET_COUNT, minimum);
        int requestedSize = Math.min(MAX_CATALOG_SIZE, Math.max(targetCount + 120, (int) Math.ceil(targetCount * 1.8d)));

        RuntimeException lastFailure = null;
        for (HistoricalMarketDataProvider provider : providers) {
            try {
                HistoricalMarketDataProvider.UniverseCatalog catalog = provider.fetchCurrentListedUniverse(
                        requestedSize, asOfTime);
                List<HistoricalMarketDataProvider.Security> eligible = catalog.securities().stream()
                        .filter(security -> eligible(security, tradeDate))
                        .toList();
                if (eligible.size() < minimum) {
                    lastFailure = new IllegalStateException(provider.providerCode()
                            + " 仅提供 " + eligible.size() + " 只可用证券，低于最低要求 " + minimum);
                    continue;
                }
                return selectBalanced(eligible, targetCount).stream()
                        .map(security -> new AiResearchUniverseService.UniverseCandidate(
                                security.stockCode(),
                                security.stockName(),
                                normalizeMarket(security.market(), security.stockCode()),
                                SOURCE_TYPE,
                                true,
                                null,
                                security.listedOn() == null ? tradeDate : security.listedOn()))
                        .toList();
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        throw new IllegalStateException("系统研究池基线股票目录不可用：" + rootMessage(lastFailure), lastFailure);
    }

    private static List<HistoricalMarketDataProvider.Security> selectBalanced(
            List<HistoricalMarketDataProvider.Security> eligible,
            int targetCount
    ) {
        Map<Integer, List<HistoricalMarketDataProvider.Security>> byBoard = new LinkedHashMap<>();
        for (HistoricalMarketDataProvider.Security security : eligible) {
            byBoard.computeIfAbsent(boardRank(security.stockCode()), ignored -> new ArrayList<>())
                    .add(security);
        }
        byBoard.values().forEach(list -> list.sort(Comparator.comparing(HistoricalMarketDataProvider.Security::stockCode)));

        List<HistoricalMarketDataProvider.Security> selected = new ArrayList<>(Math.min(targetCount, eligible.size()));
        List<Integer> order = List.of(0, 1, 2, 3, 4);
        int cursor = 0;
        boolean added;
        do {
            added = false;
            for (Integer board : order) {
                List<HistoricalMarketDataProvider.Security> bucket = byBoard.get(board);
                if (bucket == null || cursor >= bucket.size()) {
                    continue;
                }
                selected.add(bucket.get(cursor));
                added = true;
                if (selected.size() >= targetCount) {
                    return selected;
                }
            }
            cursor++;
        } while (added);
        return selected;
    }

    private static boolean eligible(HistoricalMarketDataProvider.Security security, LocalDate tradeDate) {
        if (security == null || security.stockCode() == null || security.stockName() == null) {
            return false;
        }
        String code = security.stockCode().trim();
        String name = security.stockName().trim();
        if (!code.matches("[036]\\d{5}") || name.isBlank()) {
            return false;
        }
        String normalizedName = name.toUpperCase(Locale.ROOT);
        if (normalizedName.contains("ST") || normalizedName.contains("退")) {
            return false;
        }
        LocalDate listedOn = security.listedOn();
        return listedOn == null || !listedOn.isAfter(tradeDate.minus(MIN_LISTED_DAYS, ChronoUnit.DAYS));
    }

    private static int boardRank(String stockCode) {
        if (stockCode == null) {
            return 4;
        }
        if (stockCode.startsWith("688") || stockCode.startsWith("689")) {
            return 3;
        }
        if (stockCode.startsWith("300") || stockCode.startsWith("301")) {
            return 2;
        }
        if (stockCode.startsWith("000") || stockCode.startsWith("001")
                || stockCode.startsWith("002") || stockCode.startsWith("003")) {
            return 1;
        }
        if (stockCode.startsWith("600") || stockCode.startsWith("601")
                || stockCode.startsWith("603") || stockCode.startsWith("605")) {
            return 0;
        }
        return 4;
    }

    private static String normalizeMarket(String market, String stockCode) {
        String normalized = market == null ? "" : market.trim().toUpperCase(Locale.ROOT);
        if (!normalized.isBlank()) {
            return normalized;
        }
        if (stockCode != null && stockCode.startsWith("6")) {
            return "SH";
        }
        return "SZ";
    }

    private static List<HistoricalMarketDataProvider> orderedProviders(List<HistoricalMarketDataProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个真实历史行情来源");
        }
        return providers.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(provider -> switch (provider.providerCode()) {
                    case "EASTMONEY" -> 0;
                    case "SINA_TENCENT" -> 1;
                    default -> 10;
                }))
                .toList();
    }

    private static String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
