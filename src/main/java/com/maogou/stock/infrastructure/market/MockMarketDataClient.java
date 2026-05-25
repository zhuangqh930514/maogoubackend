package com.maogou.stock.infrastructure.market;

import com.maogou.stock.dto.market.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "maogou.market", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockMarketDataClient implements MarketDataClient {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final Map<String, StockQuoteResponse> QUOTES = Map.of(
            "600519", quote("600519", "贵州茅台", "1672.80", "23.45", "1.42", "1.12", "SH"),
            "300750", quote("300750", "宁德时代", "198.64", "-1.64", "-0.82", "1.86", "SZ"),
            "688981", quote("688981", "中芯国际", "89.32", "2.75", "3.18", "2.48", "SH"),
            "600036", quote("600036", "招商银行", "37.18", "0.17", "0.46", "0.92", "SH"),
            "002594", quote("002594", "比亚迪", "226.40", "3.91", "1.76", "1.36", "SZ"),
            "688256", quote("688256", "寒武纪", "432.18", "19.86", "4.82", "3.21", "SH")
    );

    @Override
    public List<StockSearchResponse> searchStocks(String keyword, int limit) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        return QUOTES.values().stream()
                .filter(item -> item.code().contains(normalized) || item.name().contains(normalized))
                .limit(Math.max(1, limit))
                .map(item -> new StockSearchResponse(item.code(), item.name(), item.market(), item.market().toLowerCase() + item.code()))
                .toList();
    }

    @Override
    public List<NewsFlashResponse> fetchLatestNews(int limit) {
        List<NewsFlashResponse> news = List.of(
                news("券商板块拉升，市场风险偏好回暖", "新浪财经", "14:53"),
                news("AI 算力概念股午后成交额放大", "东方财富", "14:37"),
                news("北向资金净流入超过 50 亿元", "AkShare", "13:58"),
                news("新能源产业链出现结构性反弹", "财联社", "11:22"),
                news("央行公开市场操作保持流动性平稳", "证券时报", "10:08")
        );
        return news.stream().limit(limit).toList();
    }

    @Override
    public List<MarketIndexResponse> fetchCoreIndexes() {
        return List.of(
                index("上证指数", "000001.SH", "3168.42", "26.45", "0.84", List.of("3118", "3126", "3121", "3140", "3136", "3152", "3148", "3168")),
                index("深证成指", "399001.SZ", "10248.91", "113.21", "1.12", List.of("10020", "10086", "10042", "10128", "10156", "10132", "10210", "10248")),
                index("创业板指", "399006.SZ", "2114.37", "-7.61", "-0.36", List.of("2132", "2128", "2134", "2116", "2122", "2108", "2110", "2114")),
                index("科创板指", "000688.SH", "889.26", "5.13", "0.58", List.of("872", "878", "874", "882", "880", "886", "884", "889"))
        );
    }

    @Override
    public List<IntradayPointResponse> fetchIntraday(String symbol) {
        List<String> times = List.of("09:30", "10:00", "10:30", "11:00", "13:30", "14:00", "14:30", "15:00");
        List<BigDecimal> values = List.of(bd("3128"), bd("3136"), bd("3124"), bd("3142"), bd("3138"), bd("3152"), bd("3148"), bd("3168"));
        return times.stream()
                .map(time -> new IntradayPointResponse(time, values.get(times.indexOf(time)), bd("0")))
                .toList();
    }

    @Override
    public List<KlinePointResponse> fetchKline(String symbol, String period, int limit) {
        return java.util.stream.IntStream.range(0, Math.min(limit, 30))
                .mapToObj(index -> {
                    BigDecimal base = bd("67").add(BigDecimal.valueOf(index));
                    return new KlinePointResponse(
                            LocalDate.now().minusDays(30L - index),
                            base,
                            base.add(index % 2 == 0 ? bd("2") : bd("-1")),
                            base.subtract(bd("2")),
                            base.add(bd("3")),
                            1_000_000L + index * 120_000L,
                            bd("120000000").add(BigDecimal.valueOf(index * 1_000_000L))
                    );
                })
                .toList();
    }

    @Override
    public StockQuoteResponse fetchQuote(String stockCode) {
        return QUOTES.getOrDefault(stockCode, quote(stockCode, "未知股票", "0", "0", "0", "0", guessMarket(stockCode)));
    }

    @Override
    public FinanceSnapshotResponse fetchFinance(String stockCode) {
        return switch (stockCode) {
            case "600519" -> new FinanceSnapshotResponse(bd("28.4"), bd("9.3"), bd("16.8"), bd("15.4"));
            case "300750" -> new FinanceSnapshotResponse(bd("21.8"), bd("4.6"), bd("12.1"), bd("9.8"));
            case "688981" -> new FinanceSnapshotResponse(bd("48.6"), bd("3.21"), bd("12.8"), bd("9.4"));
            case "600036" -> new FinanceSnapshotResponse(bd("6.8"), bd("0.92"), bd("3.1"), bd("5.6"));
            case "002594" -> new FinanceSnapshotResponse(bd("24.2"), bd("4.1"), bd("18.4"), bd("11.2"));
            case "688256" -> new FinanceSnapshotResponse(bd("0"), bd("13.4"), bd("28.1"), bd("-6.8"));
            default -> new FinanceSnapshotResponse(bd("0"), bd("0"), bd("0"), bd("0"));
        };
    }

    private static MarketIndexResponse index(String name, String code, String value, String change, String percent, List<String> trend) {
        return new MarketIndexResponse(name, code, bd(value), bd(change), bd(percent), trend.stream().map(MockMarketDataClient::bd).toList());
    }

    private static StockQuoteResponse quote(String code, String name, String price, String change, String percent, String volumeRatio, String market) {
        return new StockQuoteResponse(code, name, bd(price), bd(change), bd(percent), bd(volumeRatio), market);
    }

    private static NewsFlashResponse news(String title, String source, String time) {
        LocalDateTime publishedAt = LocalDate.now()
                .atTime(java.time.LocalTime.parse(time, TIME_FORMATTER));
        return new NewsFlashResponse(time, title, source, null, publishedAt);
    }

    private static String guessMarket(String code) {
        return code.startsWith("6") ? "SH" : "SZ";
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
