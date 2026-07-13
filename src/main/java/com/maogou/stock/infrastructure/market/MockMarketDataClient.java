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
@ConditionalOnProperty(prefix = "maogou.market", name = "provider", havingValue = "mock")
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
                index("科创板指", "000688.SH", "889.26", "5.13", "0.58", List.of("872", "878", "874", "882", "880", "886", "884", "889")),
                index("富时中国A50", "A50.CFD", "15669.52", "-98.48", "-0.63", List.of())
        );
    }

    @Override
    public MarketBreadthResponse fetchMarketBreadth() {
        List<MarketBreadthBucketResponse> buckets = List.of(
                new MarketBreadthBucketResponse(">10%", 15, "down"),
                new MarketBreadthBucketResponse("10~7", 43, "down"),
                new MarketBreadthBucketResponse("7~5", 97, "down"),
                new MarketBreadthBucketResponse("5~3", 482, "down"),
                new MarketBreadthBucketResponse("3~0", 2688, "down"),
                new MarketBreadthBucketResponse("0", 120, "flat"),
                new MarketBreadthBucketResponse("0~3", 1445, "up"),
                new MarketBreadthBucketResponse("3~5", 291, "up"),
                new MarketBreadthBucketResponse("5~7", 124, "up"),
                new MarketBreadthBucketResponse("7~10", 135, "up"),
                new MarketBreadthBucketResponse(">10%", 64, "up")
        );
        return new MarketBreadthResponse(buckets, 2059, 3325, 120, 99, 16, 1947, 3006, 120, LocalDateTime.now());
    }

    @Override
    public SectorHeatmapResponse fetchSectorHeatmap() {
        List<SectorHeatmapItemResponse> items = List.of(
                new SectorHeatmapItemResponse("BK1623", "钼", bd("1658.99"), bd("7.53"), bd("279734016"), "up", 1),
                new SectorHeatmapItemResponse("BK1298", "文字媒体", bd("2913.70"), bd("6.66"), bd("575816895"), "up", 2),
                new SectorHeatmapItemResponse("BK1552", "超市", bd("1768.48"), bd("4.39"), bd("499536736"), "up", 3),
                new SectorHeatmapItemResponse("BK1327", "分立器件", bd("18268.58"), bd("3.88"), bd("1325649152"), "up", 4),
                new SectorHeatmapItemResponse("BK1320", "逆变器", bd("23682.73"), bd("3.40"), bd("598794752"), "up", 5),
                new SectorHeatmapItemResponse("BK1031", "个护小家电", bd("920.88"), bd("-7.30"), bd("-180000000"), "down", 1),
                new SectorHeatmapItemResponse("BK0732", "白银", bd("1685.22"), bd("-6.07"), bd("-640000000"), "down", 2),
                new SectorHeatmapItemResponse("BK0733", "贵金属", bd("1098.42"), bd("-5.60"), bd("-520000000"), "down", 3),
                new SectorHeatmapItemResponse("BK0734", "黄金", bd("2108.12"), bd("-5.46"), bd("-710000000"), "down", 4),
                new SectorHeatmapItemResponse("BK0735", "铅锌", bd("1560.32"), bd("-5.02"), bd("-330000000"), "down", 5)
        );
        return new SectorHeatmapResponse(items, LocalDateTime.now());
    }

    @Override
    public List<SectorHotStockResponse> fetchMarketHotStocks(int limit) {
        return List.of(
                new SectorHotStockResponse("300750", "宁德时代", bd("198.64"), bd("-0.82"), bd("382615420"), 1287032L, bd("51280880550"), 1),
                new SectorHotStockResponse("600519", "贵州茅台", bd("1672.80"), bd("1.42"), bd("361225118"), 436171L, bd("39110660941.32"), 2),
                new SectorHotStockResponse("688256", "寒武纪", bd("432.18"), bd("4.82"), bd("318415728"), 887032L, bd("1882880550"), 3),
                new SectorHotStockResponse("002594", "比亚迪", bd("226.40"), bd("1.76"), bd("286300000"), 920000L, bd("21010000000"), 4),
                new SectorHotStockResponse("688981", "中芯国际", bd("89.32"), bd("3.18"), bd("245800000"), 1680000L, bd("15080000000"), 5),
                new SectorHotStockResponse("300308", "中际旭创", bd("148.52"), bd("2.64"), bd("211300000"), 756000L, bd("11230000000"), 6),
                new SectorHotStockResponse("300033", "同花顺", bd("126.75"), bd("1.21"), bd("198400000"), 622000L, bd("7850000000"), 7),
                new SectorHotStockResponse("601318", "中国平安", bd("52.18"), bd("0.94"), bd("186500000"), 1338000L, bd("9680000000"), 8),
                new SectorHotStockResponse("600036", "招商银行", bd("37.18"), bd("0.46"), bd("172800000"), 1100000L, bd("6200000000"), 9),
                new SectorHotStockResponse("000063", "中兴通讯", bd("34.67"), bd("2.11"), bd("165300000"), 845000L, bd("5360000000"), 10)
        ).stream().limit(Math.max(1, limit)).toList();
    }

    @Override
    public List<SectorHotStockResponse> fetchSectorHotStocks(String sectorCode, int limit) {
        return List.of(
                new SectorHotStockResponse("001257", "盛龙股份", bd("28.41"), bd("9.99"), bd("175232194"), 396171L, bd("1106609414.32"), 1),
                new SectorHotStockResponse("601958", "金钼股份", bd("24.46"), bd("5.29"), bd("114415728"), 1187032L, bd("2882880550"), 2),
                new SectorHotStockResponse("600000", "示例股份", bd("12.36"), bd("3.18"), bd("86300000"), 820000L, bd("1010000000"), 3)
        ).stream().limit(Math.max(1, limit)).toList();
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
    public KlineSeriesSnapshot fetchKlineAt(
            String symbol,
            String period,
            int limit,
            LocalDateTime asOfTime
    ) {
        List<KlinePointResponse> points = fetchKline(symbol, period, limit).stream()
                .filter(point -> point.tradeDate() != null && !point.tradeDate().isAfter(asOfTime.toLocalDate()))
                .toList();
        return KlineSeriesSnapshot.create(
                symbol,
                period == null || period.isBlank() ? "day" : period,
                "NONE",
                "LOCAL_TEST_FIXTURE",
                asOfTime,
                LocalDateTime.now(),
                points);
    }

    @Override
    public StockQuoteResponse fetchQuote(String stockCode) {
        return QUOTES.getOrDefault(stockCode, quote(stockCode, "未知股票", "0", "0", "0", "0", guessMarket(stockCode)));
    }

    @Override
    public FinanceSnapshotResponse fetchFinance(String stockCode) {
        return switch (stockCode) {
            case "600519" -> finance("28.4", "9.3", "2100000000000", "2100000000000", "49.3", "216.3", "54702912385", "16.8", "27242512886", "15.4", "10.57", "89.76", "52.22", "12.12", "21.49");
            case "300750" -> finance("21.8", "4.6", "980000000000", "760000000000", "8.1", "43.2", "79700000000", "12.1", "12800000000", "9.8", "7.84", "24.18", "12.03", "62.31", "5.42");
            case "688981" -> finance("48.6", "3.21", "720000000000", "410000000000", "1.6", "27.8", "13100000000", "12.8", "3400000000", "9.4", "3.61", "22.64", "8.41", "38.52", "1.82");
            case "600036" -> finance("6.8", "0.92", "910000000000", "760000000000", "5.9", "39.4", "92000000000", "3.1", "38000000000", "5.6", "4.23", "0", "41.32", "91.48", "0");
            case "002594" -> finance("24.2", "4.1", "850000000000", "326000000000", "0.45", "25.44", "150225314000", "18.4", "4084551000", "11.2", "1.65", "18.81", "2.67", "76.28", "0.31");
            case "688256" -> finance("0", "13.4", "260000000000", "110000000000", "-1.2", "18.4", "1800000000", "28.1", "-620000000", "-6.8", "-3.42", "61.20", "-34.44", "21.36", "-0.86");
            default -> FinanceSnapshotResponse.empty();
        };
    }

    private static FinanceSnapshotResponse finance(
            String pe,
            String pb,
            String totalMarketValue,
            String circulatingMarketValue,
            String eps,
            String bps,
            String revenue,
            String revenueGrowth,
            String netProfit,
            String profitGrowth,
            String roe,
            String grossMargin,
            String netMargin,
            String debtRatio,
            String operatingCashFlowPerShare
    ) {
        return new FinanceSnapshotResponse(
                bd(pe),
                bd(pb),
                bd(totalMarketValue),
                bd(circulatingMarketValue),
                bd(eps),
                bd(bps),
                bd(revenue),
                bd(revenueGrowth),
                bd(netProfit),
                bd(profitGrowth),
                bd(roe),
                bd(grossMargin),
                bd(netMargin),
                bd(debtRatio),
                bd(operatingCashFlowPerShare)
        );
    }

    private static MarketIndexResponse index(String name, String code, String value, String change, String percent, List<String> trend) {
        return new MarketIndexResponse(name, code, bd(value), bd(change), bd(percent), trend.stream().map(MockMarketDataClient::bd).toList());
    }

    private static StockQuoteResponse quote(String code, String name, String price, String change, String percent, String volumeRatio, String market) {
        return new StockQuoteResponse(code, name, bd(price), bd(change), bd(percent), bd(volumeRatio), market, "MOCK", LocalDateTime.now());
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
