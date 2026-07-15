package com.maogou.stock.infrastructure.market;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "maogou.market", name = "provider", havingValue = "sina")
public class SinaMarketDataClient implements MarketDataClient, ResearchMarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(SinaMarketDataClient.class);
    private static final Charset GBK = Charset.forName("GBK");
    private static final DateTimeFormatter NEWS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern QUOTE_PATTERN = Pattern.compile("var hq_str_([A-Za-z0-9_]+)=\\\"(.*?)\\\";");
    private static final Pattern TENCENT_QUOTE_PATTERN = Pattern.compile("v_([A-Za-z0-9_]+)=\\\"(.*?)\\\";");
    private static final Pattern JSONP_ARRAY_PATTERN = Pattern.compile("\\((\\[.*])\\)", Pattern.DOTALL);
    private static final Pattern JSONP_OBJECT_PATTERN = Pattern.compile("\\((\\{.*})\\)", Pattern.DOTALL);
    private static final Pattern EASTMONEY_NEWS_PATTERN = Pattern.compile("var ajaxResult=(\\{.*});?", Pattern.DOTALL);
    private static final Pattern SUGGEST_PATTERN = Pattern.compile("var suggestvalue=\\\"(.*?)\\\";", Pattern.DOTALL);
    private static final int QUOTE_BATCH_SIZE = 80;
    private static final List<IndexSymbol> CORE_INDEXES = List.of(
            new IndexSymbol("上证指数", "000001.SH", "sh000001"),
            new IndexSymbol("深证成指", "399001.SZ", "sz399001"),
            new IndexSymbol("创业板指", "399006.SZ", "sz399006"),
            new IndexSymbol("科创50", "000688.SH", "sh000688")
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public SinaMarketDataClient(@Qualifier("marketRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper, AppProperties properties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String providerCode() {
        return "SINA";
    }

    @Override
    public boolean supports(String endpointType) {
        return ResearchMarketDataProvider.ENDPOINT_KLINE.equals(endpointType);
    }

    @Override
    public boolean supports(String endpointType, String symbol) {
        return supports(endpointType)
                && (symbol == null || !symbol.trim().toUpperCase(Locale.ROOT).startsWith("BK"));
    }

    @Override
    public List<StockSearchResponse> searchStocks(String keyword, int limit) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        int size = Math.max(1, Math.min(limit, 50));
        String lowered = normalized.toLowerCase(Locale.ROOT);
        Map<String, StockSearchResponse> results = new LinkedHashMap<>();
        try {
            for (StockSearchResponse stock : fetchEastmoneyStockList()) {
                if (stock.code().contains(normalized)
                        || stock.name().contains(normalized)
                        || stock.symbol().toLowerCase(Locale.ROOT).contains(lowered)) {
                    results.putIfAbsent(stock.code(), stock);
                    if (results.size() >= size) {
                        return new ArrayList<>(results.values());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            URI uri = URI.create("https://suggest3.sinajs.cn/suggest/type=11,12,13,14,15&key="
                    + URLEncoder.encode(normalized, StandardCharsets.UTF_8));
            String text = getText(uri, GBK, "https://finance.sina.com.cn/");
            Matcher matcher = SUGGEST_PATTERN.matcher(text);
            if (!matcher.find() || matcher.group(1).isBlank()) {
                return new ArrayList<>(results.values());
            }
            String[] rows = matcher.group(1).split(";");
            for (String row : rows) {
                String[] fields = row.split(",", -1);
                if (fields.length < 5) {
                    continue;
                }
                String code = fields[2];
                String symbol = fields[3];
                String name = fields[4];
                if (!code.matches("\\d{6}") || !(symbol.startsWith("sh") || symbol.startsWith("sz"))) {
                    continue;
                }
                String market = symbol.startsWith("sh") ? "SH" : "SZ";
                results.putIfAbsent(code, new StockSearchResponse(code, name, market, symbol));
                if (results.size() >= size) {
                    break;
                }
            }
            return new ArrayList<>(results.values());
        } catch (Exception ex) {
            if (!results.isEmpty()) {
                return new ArrayList<>(results.values());
            }
            throw new IllegalStateException("搜索股票失败：" + ex.getMessage(), ex);
        }
    }

    private List<StockSearchResponse> fetchEastmoneyStockList() throws Exception {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://push2.eastmoney.com/api/qt/clist/get")
                .queryParam("pn", "1")
                .queryParam("pz", "6000")
                .queryParam("po", "1")
                .queryParam("np", "1")
                .queryParam("fltt", "2")
                .queryParam("invt", "2")
                .queryParam("fid", "f3")
                .queryParam("fs", "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23")
                .queryParam("fields", "f12,f13,f14")
                .build(false)
                .toUri();
        JsonNode data = objectMapper.readTree(getText(uri, StandardCharsets.UTF_8, "https://quote.eastmoney.com/")).path("data").path("diff");
        if (!data.isArray()) {
            return List.of();
        }
        List<StockSearchResponse> stocks = new ArrayList<>();
        for (JsonNode item : data) {
            String code = item.path("f12").asText("");
            String name = item.path("f14").asText("");
            String market = item.path("f13").asInt() == 1 ? "SH" : "SZ";
            if (!code.matches("\\d{6}") || name.isBlank()) {
                continue;
            }
            stocks.add(new StockSearchResponse(code, name, market, market.toLowerCase(Locale.ROOT) + code));
        }
        return stocks;
    }

    @Override
    public List<NewsFlashResponse> fetchLatestNews(int limit) {
        int pageSize = Math.max(1, Math.min(limit, 100));
        URI uri = URI.create("https://newsapi.eastmoney.com/kuaixun/v1/getlist_102_ajaxResult_"
                + pageSize + "_1_.html?r=" + UUID.randomUUID());
        try {
            String text = getText(uri, StandardCharsets.UTF_8, "https://kuaixun.eastmoney.com/");
            Matcher matcher = EASTMONEY_NEWS_PATTERN.matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException("东方财富快讯返回格式异常");
            }
            JsonNode root = objectMapper.readTree(matcher.group(1));
            JsonNode data = root.path("LivesList");
            if (!data.isArray()) {
                throw new IllegalStateException("东方财富快讯返回格式异常");
            }
            List<NewsFlashResponse> news = new ArrayList<>();
            for (JsonNode item : data) {
                LocalDateTime publishedAt = parseDateTime(item.path("showtime").asText(""));
                news.add(new NewsFlashResponse(
                        publishedAt.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                        item.path("title").asText(),
                        "东方财富",
                        item.path("url_m").asText(item.path("url_w").asText(null)),
                        publishedAt
                ));
                if (news.size() >= limit) {
                    break;
                }
            }
            return news;
        } catch (Exception ex) {
            throw new IllegalStateException("获取东方财富财经快讯失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public List<MarketIndexResponse> fetchCoreIndexes() {
        Map<String, MarketIndexResponse> tencentIndexes = Map.of();
        try {
            tencentIndexes = fetchTencentIndexes(CORE_INDEXES);
        } catch (RuntimeException ex) {
            log.warn("tencent core indexes failed, try eastmoney fallback", ex);
        }

        List<MarketIndexResponse> indexes = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (IndexSymbol index : CORE_INDEXES) {
            MarketIndexResponse tencentIndex = tencentIndexes.get(index.code());
            if (tencentIndex != null) {
                indexes.add(tencentIndex);
                continue;
            }
            try {
                indexes.add(fetchEastmoneyIndex(index));
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
                log.warn("core index source failed, skip index={}", index.code(), ex);
            }
        }
        if (indexes.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        MarketIndexResponse a50 = fetchA50IndexSafely();
        if (a50 != null) {
            indexes.add(a50);
        }
        return indexes;
    }

    private Map<String, MarketIndexResponse> fetchTencentIndexes(List<IndexSymbol> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        Map<String, IndexSymbol> symbolMap = new LinkedHashMap<>();
        for (IndexSymbol symbol : symbols) {
            symbolMap.put(symbol.sinaSymbol(), symbol);
        }
        URI uri = URI.create("https://qt.gtimg.cn/q=" + String.join(",", symbols.stream()
                .map(IndexSymbol::sinaSymbol)
                .toList()));
        try {
            String text = getTextWithRetry(uri, GBK, "https://gu.qq.com/");
            Matcher matcher = TENCENT_QUOTE_PATTERN.matcher(text);
            Map<String, MarketIndexResponse> result = new LinkedHashMap<>();
            while (matcher.find()) {
                String sinaSymbol = matcher.group(1);
                IndexSymbol index = symbolMap.get(sinaSymbol);
                if (index == null) {
                    continue;
                }
                String[] fields = matcher.group(2).split("~", -1);
                if (fields.length < 33 || fields[3].isBlank()) {
                    continue;
                }
                BigDecimal value = bdOrZero(fields[3]);
                BigDecimal prevClose = bdOrZero(fields[4]);
                BigDecimal change = fields[31].isBlank() ? value.subtract(prevClose) : bdOrZero(fields[31]);
                BigDecimal percent = fields[32].isBlank() || prevClose.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : bdOrZero(fields[32]);
                List<BigDecimal> trend = prevClose.compareTo(BigDecimal.ZERO) > 0
                        ? List.of(prevClose, value)
                        : List.of(value);
                result.put(index.code(), new MarketIndexResponse(
                        index.name(),
                        index.code(),
                        value,
                        change,
                        percent,
                        trend
                ));
            }
            if (result.isEmpty()) {
                throw new IllegalStateException("腾讯核心指数返回为空");
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("获取腾讯核心指数失败：" + ex.getMessage(), ex);
        }
    }

    private MarketIndexResponse fetchA50IndexSafely() {
        try {
            return fetchA50Index();
        } catch (RuntimeException ex) {
            log.warn("A50 realtime source failed, skip stale static fallback", ex);
            return null;
        }
    }

    private MarketIndexResponse fetchEastmoneyIndex(IndexSymbol index) {
        List<IntradayPointResponse> intraday = fetchEastmoneyIntraday(index.sinaSymbol());
        if (intraday.isEmpty()) {
            throw new IllegalStateException("东方财富指数分时数据为空：" + index.name());
        }
        BigDecimal value = intraday.get(intraday.size() - 1).value();
        BigDecimal prevClose = fetchEastmoneyPrevClose(index.sinaSymbol());
        BigDecimal change = value.subtract(prevClose);
        BigDecimal percent = prevClose.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : change.multiply(new BigDecimal("100")).divide(prevClose, 4, java.math.RoundingMode.HALF_UP);
        List<BigDecimal> trend = intraday.stream().map(IntradayPointResponse::value).toList();
        return new MarketIndexResponse(index.name(), index.code(), value, change, percent, trend);
    }

    private BigDecimal fetchEastmoneyPrevClose(String symbol) {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://push2his.eastmoney.com/api/qt/stock/trends2/get")
                .queryParam("secid", toEastmoneyCode(toSinaSymbol(symbol)))
                .queryParam("fields1", "f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13")
                .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58")
                .queryParam("iscr", "0")
                .queryParam("iscca", "0")
                .queryParam("ndays", "1")
                .build(false)
                .toUri();
        try {
            JsonNode data = objectMapper.readTree(getTextWithRetry(uri, StandardCharsets.UTF_8, "https://quote.eastmoney.com/"))
                    .path("data");
            return decimal(data.path("preClose"));
        } catch (Exception ex) {
            throw new IllegalStateException("获取东方财富昨收失败：" + symbol + "，" + ex.getMessage(), ex);
        }
    }

    private StockQuoteResponse fetchEastmoneyStockQuote(String stockCode) throws Exception {
        String sinaSymbol = toSinaSymbol(stockCode);
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://push2.eastmoney.com/api/qt/stock/get")
                .queryParam("secid", toEastmoneyCode(sinaSymbol))
                .queryParam("fields", "f43,f50,f57,f58,f60,f152,f169,f170")
                .build(false)
                .toUri();
        JsonNode data = objectMapper.readTree(getTextWithRetry(uri, StandardCharsets.UTF_8, "https://quote.eastmoney.com/"))
                .path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("东方财富实时行情为空：" + stockCode);
        }
        BigDecimal price = eastmoneyScaledValue(data, "f43");
        BigDecimal change = eastmoneyScaledValue(data, "f169");
        BigDecimal percent = eastmoneyScaledValue(data, "f170");
        String name = data.path("f58").asText(stockCode);
        return new StockQuoteResponse(normalizeStockCode(stockCode), name, price, change, percent, eastmoneyScaledValue(data, "f50"), marketOf(stockCode), "EASTMONEY", LocalDateTime.now());
    }

    private List<IntradayPointResponse> fetchEastmoneyIntraday(String symbol) {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://push2his.eastmoney.com/api/qt/stock/trends2/get")
                .queryParam("secid", toEastmoneyCode(toSinaSymbol(symbol)))
                .queryParam("fields1", "f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13")
                .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58")
                .queryParam("iscr", "0")
                .queryParam("iscca", "0")
                .queryParam("ndays", "1")
                .build(false)
                .toUri();
        try {
            JsonNode points = objectMapper.readTree(getTextWithRetry(uri, StandardCharsets.UTF_8, "https://quote.eastmoney.com/"))
                    .path("data")
                    .path("trends");
            if (!points.isArray()) {
                throw new IllegalStateException("东方财富分时数据为空");
            }
            List<IntradayPointResponse> result = new ArrayList<>();
            for (JsonNode point : points) {
                String[] fields = point.asText("").split(",", -1);
                if (fields.length < 7) {
                    continue;
                }
                String time = fields[0].length() >= 16 ? fields[0].substring(11, 16) : normalizeTime(fields[0]);
                result.add(new IntradayPointResponse(time, bd(fields[2]), bdOrZero(fields[5])));
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("获取东方财富分时行情失败：" + symbol + "，" + ex.getMessage(), ex);
        }
    }

    private List<KlinePointResponse> fetchEastmoneyKline(
            String symbol,
            String period,
            int limit,
            String adjustmentMode,
            String endDate
    ) {
        String klt = switch (period == null ? "day" : period.toLowerCase(Locale.ROOT)) {
            case "week", "weekly" -> "102";
            case "month", "monthly" -> "103";
            default -> "101";
        };
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://push2his.eastmoney.com/api/qt/stock/kline/get")
                .queryParam("secid", toEastmoneyCode(toSinaSymbol(symbol)))
                .queryParam("fields1", "f1,f2,f3,f4,f5,f6")
                .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61")
                .queryParam("klt", klt)
                .queryParam("fqt", adjustmentMode)
                .queryParam("end", endDate)
                .queryParam("lmt", Math.max(1, Math.min(limit, 240)))
                .build(false)
                .toUri();
        try {
            JsonNode points = objectMapper.readTree(getTextWithRetry(uri, StandardCharsets.UTF_8, "https://quote.eastmoney.com/"))
                    .path("data")
                    .path("klines");
            if (!points.isArray()) {
                throw new IllegalStateException("东方财富 K 线数据为空");
            }
            List<KlinePointResponse> result = new ArrayList<>();
            for (JsonNode point : points) {
                String[] fields = point.asText("").split(",", -1);
                if (fields.length < 7) {
                    continue;
                }
                result.add(new KlinePointResponse(
                        LocalDate.parse(fields[0]),
                        bd(fields[1]),
                        bd(fields[2]),
                        bd(fields[4]),
                        bd(fields[3]),
                        Long.parseLong(fields[5]),
                        bdOrZero(fields[6])
                ));
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("获取东方财富 K 线行情失败：" + symbol + "，" + ex.getMessage(), ex);
        }
    }

    private MarketIndexResponse fetchA50Index() {
        URI uri = UriComponentsBuilder
                .fromHttpUrl(properties.getMarket().getSinaBaseUrl())
                .queryParam("list", "hf_CHA50CFD")
                .build(true)
                .toUri();
        try {
            String text = getText(uri, GBK, "https://finance.sina.com.cn/");
            Matcher matcher = QUOTE_PATTERN.matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException("新浪 A50 行情返回为空");
            }
            String[] fields = matcher.group(2).split(",", -1);
            if (fields.length < 14 || fields[0].isBlank()) {
                throw new IllegalStateException("新浪 A50 行情格式异常");
            }
            BigDecimal price = bd(fields[0]);
            BigDecimal prevClose = bd(fields[7]);
            BigDecimal change = price.subtract(prevClose);
            BigDecimal percent = prevClose.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : change.multiply(new BigDecimal("100")).divide(prevClose, 4, java.math.RoundingMode.HALF_UP);
            return new MarketIndexResponse("富时中国A50", "A50.CFD", price, change, percent, List.of());
        } catch (Exception ex) {
            throw new IllegalStateException("获取新浪 A50 行情失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public MarketBreadthResponse fetchMarketBreadth() {
        try {
            int downGt10 = 0;
            int down10To7 = 0;
            int down7To5 = 0;
            int down5To3 = 0;
            int down3To0 = 0;
            int flat = 0;
            int up0To3 = 0;
            int up3To5 = 0;
            int up5To7 = 0;
            int up7To10 = 0;
            int upGt10 = 0;
            int limitUp = 0;
            int limitDown = 0;
            int fundIn = 0;
            int fundOut = 0;
            int fundFlat = 0;
            int processed = 0;
            int total = Integer.MAX_VALUE;

            for (int page = 1; processed < total; page++) {
                URI uri = UriComponentsBuilder
                        .fromHttpUrl("https://push2delay.eastmoney.com/api/qt/clist/get")
                        .queryParam("pn", page)
                        .queryParam("pz", "100")
                        .queryParam("po", "1")
                        .queryParam("np", "1")
                        .queryParam("fltt", "2")
                        .queryParam("invt", "2")
                        .queryParam("fid", "f3")
                        .queryParam("fs", "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23")
                        .queryParam("fields", "f3,f62")
                        .build(false)
                        .toUri();
                JsonNode data = objectMapper.readTree(getTextWithRetry(uri, StandardCharsets.UTF_8, "https://quote.eastmoney.com/"))
                        .path("data");
                JsonNode rows = data.path("diff");
                if (!rows.isArray() || rows.isEmpty()) {
                    break;
                }
                total = data.path("total").asInt(total);
                processed += rows.size();

                for (JsonNode row : rows) {
                    JsonNode percentNode = row.path("f3");
                    if (percentNode.isMissingNode() || percentNode.isNull() || "-".equals(percentNode.asText())) {
                        continue;
                    }
                    BigDecimal percentValue = decimal(percentNode);
                    double percent = percentValue.doubleValue();
                    if (percentValue.signum() == 0) {
                        flat++;
                    } else if (percent < -10) {
                        downGt10++;
                    } else if (percent <= -7) {
                        down10To7++;
                    } else if (percent <= -5) {
                        down7To5++;
                    } else if (percent <= -3) {
                        down5To3++;
                    } else if (percent < 0) {
                        down3To0++;
                    } else if (percent < 3) {
                        up0To3++;
                    } else if (percent < 5) {
                        up3To5++;
                    } else if (percent < 7) {
                        up5To7++;
                    } else if (percent <= 10) {
                        up7To10++;
                    } else {
                        upGt10++;
                    }

                    if (percent >= 9.8) {
                        limitUp++;
                    }
                    if (percent <= -9.8) {
                        limitDown++;
                    }

                    int fundSign = decimal(row.path("f62")).signum();
                    if (fundSign > 0) {
                        fundIn++;
                    } else if (fundSign < 0) {
                        fundOut++;
                    } else {
                        fundFlat++;
                    }
                }
            }

            List<MarketBreadthBucketResponse> buckets = List.of(
                    new MarketBreadthBucketResponse(">10%", downGt10, "down"),
                    new MarketBreadthBucketResponse("10~7", down10To7, "down"),
                    new MarketBreadthBucketResponse("7~5", down7To5, "down"),
                    new MarketBreadthBucketResponse("5~3", down5To3, "down"),
                    new MarketBreadthBucketResponse("3~0", down3To0, "down"),
                    new MarketBreadthBucketResponse("0", flat, "flat"),
                    new MarketBreadthBucketResponse("0~3", up0To3, "up"),
                    new MarketBreadthBucketResponse("3~5", up3To5, "up"),
                    new MarketBreadthBucketResponse("5~7", up5To7, "up"),
                    new MarketBreadthBucketResponse("7~10", up7To10, "up"),
                    new MarketBreadthBucketResponse(">10%", upGt10, "up")
            );
            int downCount = downGt10 + down10To7 + down7To5 + down5To3 + down3To0;
            int upCount = up0To3 + up3To5 + up5To7 + up7To10 + upGt10;
            return new MarketBreadthResponse(buckets, upCount, downCount, flat, limitUp, limitDown, fundIn, fundOut, fundFlat, LocalDateTime.now());
        } catch (Exception ex) {
            throw new IllegalStateException("获取东方财富涨跌分布失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public SectorHeatmapResponse fetchSectorHeatmap() {
        List<SectorHeatmapItemResponse> items = new ArrayList<>();
        items.addAll(fetchSectorHeatmapItems(1, "up"));
        items.addAll(fetchSectorHeatmapItems(0, "down"));
        return new SectorHeatmapResponse(items, LocalDateTime.now());
    }

    private List<SectorHeatmapItemResponse> fetchSectorHeatmapItems(int order, String direction) {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://push2.eastmoney.com/api/qt/clist/get")
                .queryParam("pn", "1")
                .queryParam("pz", "10")
                .queryParam("po", order)
                .queryParam("np", "1")
                .queryParam("fltt", "2")
                .queryParam("invt", "2")
                .queryParam("fid", "f3")
                .queryParam("fs", "m:90+t:2")
                .queryParam("fields", "f12,f14,f2,f3,f62")
                .build(false)
                .toUri();
        try {
            JsonNode rows = objectMapper.readTree(getTextWithRetry(uri, StandardCharsets.UTF_8, "https://quote.eastmoney.com/center/boardlist.html"))
                    .path("data")
                    .path("diff");
            if (!rows.isArray()) {
                throw new IllegalStateException("东方财富板块热力数据为空");
            }
            List<SectorHeatmapItemResponse> result = new ArrayList<>();
            int rank = 1;
            for (JsonNode row : rows) {
                String code = row.path("f12").asText("");
                String name = row.path("f14").asText("");
                if (code.isBlank() || name.isBlank()) {
                    continue;
                }
                result.add(new SectorHeatmapItemResponse(
                        code,
                        name,
                        decimal(row.path("f2")),
                        decimal(row.path("f3")),
                        decimal(row.path("f62")),
                        direction,
                        rank++
                ));
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("获取东方财富板块热力图失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public List<SectorHotStockResponse> fetchMarketHotStocks(int limit) {
        return fetchHotStocks("m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23", Math.max(1, Math.min(limit, 20)), "https://quote.eastmoney.com/center/gridlist.html", "获取东方财富全市场热门股票失败");
    }

    @Override
    public List<SectorHotStockResponse> fetchSectorHotStocks(String sectorCode, int limit) {
        int size = Math.max(1, Math.min(limit, 20));
        return fetchHotStocks("b:" + sectorCode, size, "https://quote.eastmoney.com/center/boardlist.html", "获取东方财富板块热门股票失败");
    }

    private List<SectorHotStockResponse> fetchHotStocks(String fs, int size, String referer, String errorMessage) {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://push2.eastmoney.com/api/qt/clist/get")
                .queryParam("pn", "1")
                .queryParam("pz", size)
                .queryParam("po", "1")
                .queryParam("np", "1")
                .queryParam("fltt", "2")
                .queryParam("invt", "2")
                .queryParam("fid", "f62")
                .queryParam("fs", fs)
                .queryParam("fields", "f12,f14,f2,f3,f5,f6,f62")
                .build(false)
                .toUri();
        try {
            JsonNode rows = objectMapper.readTree(getTextWithRetry(uri, StandardCharsets.UTF_8, referer))
                    .path("data")
                    .path("diff");
            if (!rows.isArray()) {
                return List.of();
            }
            List<SectorHotStockResponse> result = new ArrayList<>();
            int rank = 1;
            for (JsonNode row : rows) {
                String code = row.path("f12").asText("");
                String name = row.path("f14").asText("");
                if (code.isBlank() || name.isBlank()) {
                    continue;
                }
                result.add(new SectorHotStockResponse(
                        code,
                        name,
                        decimal(row.path("f2")),
                        decimal(row.path("f3")),
                        decimal(row.path("f62")),
                        row.path("f5").asLong(0),
                        decimal(row.path("f6")),
                        rank++
                ));
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException(errorMessage + "：" + ex.getMessage(), ex);
        }
    }

    @Override
    public List<IntradayPointResponse> fetchIntraday(String symbol) {
        if ("A50.CFD".equalsIgnoreCase(symbol) || "CHA50CFD".equalsIgnoreCase(symbol)) {
            return fetchA50Intraday();
        }
        try {
            return fetchEastmoneyIntraday(symbol);
        } catch (Exception ignored) {
            // 新浪在云服务器上可能返回 403，东方财富也偶发断连；保留旧通道作为备源。
        }
        String sinaSymbol = toSinaSymbol(symbol);
        URI uri = URI.create("https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_" + sinaSymbol
                + "_1_1=/CN_MinlineService.getMinlineData?symbol=" + sinaSymbol);
        try {
            String text = getText(uri, StandardCharsets.UTF_8, "https://finance.sina.com.cn/");
            Matcher matcher = JSONP_ARRAY_PATTERN.matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException("新浪分时返回格式异常");
            }
            JsonNode points = objectMapper.readTree(matcher.group(1));
            List<IntradayPointResponse> result = new ArrayList<>();
            for (JsonNode point : points) {
                result.add(new IntradayPointResponse(
                        normalizeTime(point.path("m").asText()),
                        bd(point.path("p").asText("0")),
                        bd(point.path("v").asText("0"))
                ));
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("获取新浪分时行情失败：" + symbol + "，" + ex.getMessage(), ex);
        }
    }

    private List<IntradayPointResponse> fetchA50Intraday() {
        URI uri = URI.create("https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_CHA50CFD="
                + "/GlobalFuturesService.getGlobalFuturesMinLine?symbol=CHA50CFD&_=" + System.currentTimeMillis());
        try {
            String text = getText(uri, StandardCharsets.UTF_8, "https://finance.sina.com.cn/");
            Matcher matcher = JSONP_OBJECT_PATTERN.matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException("新浪 A50 分时返回格式异常");
            }
            JsonNode points = objectMapper.readTree(matcher.group(1)).path("minLine_1d");
            if (!points.isArray()) {
                throw new IllegalStateException("新浪 A50 分时数据为空");
            }
            List<IntradayPointResponse> result = new ArrayList<>();
            for (JsonNode point : points) {
                if (!point.isArray() || point.size() < 2) {
                    continue;
                }
                String time = point.get(0).asText("");
                if (time.length() > 5) {
                    time = point.size() > 4 ? point.get(4).asText(time) : time;
                }
                result.add(new IntradayPointResponse(
                        normalizeTime(time),
                        bd(point.get(1).asText("0")),
                        point.size() > 2 ? bdOrZero(point.get(2).asText("0")) : BigDecimal.ZERO
                ));
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("获取新浪 A50 分时行情失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public List<KlinePointResponse> fetchKline(String symbol, String period, int limit) {
        if ("A50.CFD".equalsIgnoreCase(symbol) || "CHA50CFD".equalsIgnoreCase(symbol)) {
            return fetchA50Kline(period, limit);
        }
        try {
            return fetchEastmoneyKline(symbol, period, limit, "1", "20500101");
        } catch (Exception ignored) {
            // 保留新浪 K 线作为备源。
        }
        return fetchSinaKline(symbol, period, limit);
    }

    @Override
    public KlineSeriesSnapshot fetchKlineAt(
            String symbol,
            String period,
            int limit,
            LocalDateTime asOfTime
    ) {
        if (asOfTime == null) {
            throw new IllegalArgumentException("K 线 asOfTime 不能为空");
        }
        boolean a50 = "A50.CFD".equalsIgnoreCase(symbol) || "CHA50CFD".equalsIgnoreCase(symbol);
        List<KlinePointResponse> loaded = a50
                ? fetchA50Kline(period, limit)
                : fetchSinaKline(symbol, period, limit);
        boolean closingBarAvailable = !asOfTime.toLocalTime().isBefore(LocalTime.of(15, 5));
        List<KlinePointResponse> pointInTime = loaded.stream()
                .filter(point -> point.tradeDate().isBefore(asOfTime.toLocalDate())
                        || (closingBarAvailable && point.tradeDate().isEqual(asOfTime.toLocalDate())))
                .toList();
        LocalDateTime fetchedAt = LocalDateTime.now();
        String normalizedPeriod = period == null || period.isBlank() ? "day" : period;
        return KlineSeriesSnapshot.create(
                symbol,
                normalizedPeriod,
                "NONE",
                "SINA",
                asOfTime,
                fetchedAt,
                pointInTime
        );
    }

    private List<KlinePointResponse> fetchSinaKline(String symbol, String period, int limit) {
        String sinaSymbol = toSinaSymbol(symbol);
        String scale = switch (period == null ? "day" : period.toLowerCase(Locale.ROOT)) {
            case "week", "weekly" -> "1200";
            case "month", "monthly" -> "7200";
            default -> "240";
        };
        int dataLength = Math.max(1, Math.min(limit, 240));
        URI jsonUri = URI.create("https://quotes.sina.cn/cn/api/json_v2.php/"
                + "CN_MarketDataService.getKLineData?symbol=" + sinaSymbol
                + "&scale=" + scale
                + "&ma=no&datalen=" + dataLength);
        RuntimeException jsonFailure;
        try {
            return fetchSinaKlineChannel(jsonUri, "JSON");
        } catch (RuntimeException exception) {
            jsonFailure = exception;
        }

        URI jsonpUri = URI.create("https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_" + sinaSymbol + "_" + scale
                + "=/CN_MarketDataService.getKLineData?symbol=" + sinaSymbol
                + "&scale=" + scale
                + "&ma=no&datalen=" + dataLength);
        try {
            return fetchSinaKlineChannel(jsonpUri, "JSONP");
        } catch (RuntimeException exception) {
            exception.addSuppressed(jsonFailure);
            throw new IllegalStateException("获取新浪 K 线行情失败：" + symbol
                    + "；" + failureSummary(jsonFailure)
                    + "；" + failureSummary(exception), exception);
        }
    }

    private List<KlinePointResponse> fetchSinaKlineChannel(URI uri, String channel) {
        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return parseSinaKlinePayload(getText(
                        uri, StandardCharsets.UTF_8, "https://finance.sina.com.cn/"));
            } catch (RuntimeException exception) {
                lastError = exception;
                if (attempt == 0) {
                    sleepBeforeRetry(attempt);
                }
            }
        }
        throw new IllegalStateException("新浪 K 线" + channel + "通道失败："
                + rootFailureMessage(lastError), lastError);
    }

    private static String failureSummary(RuntimeException exception) {
        return exception == null ? "未知通道失败" : exception.getMessage();
    }

    private static String rootFailureMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    private List<KlinePointResponse> parseSinaKlinePayload(String payload) {
        String normalized = payload == null ? "" : payload.strip();
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1).stripLeading();
        }
        String json;
        if (normalized.startsWith("[")) {
            json = normalized;
        } else {
            Matcher matcher = JSONP_ARRAY_PATTERN.matcher(normalized);
            if (!matcher.find()) {
                throw new IllegalStateException("新浪 K 线返回格式异常");
            }
            json = matcher.group(1);
        }
        JsonNode points;
        try {
            points = objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("新浪 K 线 JSON 无法解析", exception);
        }
        if (!points.isArray()) {
            throw new IllegalStateException("新浪 K 线返回格式异常");
        }
        List<KlinePointResponse> result = new ArrayList<>();
        for (JsonNode point : points) {
            result.add(new KlinePointResponse(
                    LocalDate.parse(point.path("day").asText()),
                    bd(point.path("open").asText("0")),
                    bd(point.path("close").asText("0")),
                    bd(point.path("low").asText("0")),
                    bd(point.path("high").asText("0")),
                    point.path("volume").asLong(0),
                    nullableDecimal(point.path("amount"))
            ));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("新浪 K 线返回为空");
        }
        return result;
    }

    private List<KlinePointResponse> fetchA50Kline(String period, int limit) {
        URI uri = URI.create("https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_CHA50CFD="
                + "/GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=CHA50CFD&_=" + System.currentTimeMillis());
        try {
            String text = getText(uri, StandardCharsets.UTF_8, "https://finance.sina.com.cn/");
            Matcher matcher = JSONP_ARRAY_PATTERN.matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException("新浪 A50 K 线返回格式异常");
            }
            JsonNode points = objectMapper.readTree(matcher.group(1));
            List<KlinePointResponse> daily = new ArrayList<>();
            for (JsonNode point : points) {
                daily.add(new KlinePointResponse(
                        LocalDate.parse(point.path("date").asText()),
                        bd(point.path("open").asText("0")),
                        bd(point.path("close").asText("0")),
                        bd(point.path("low").asText("0")),
                        bd(point.path("high").asText("0")),
                        point.path("volume").asLong(0),
                        BigDecimal.ZERO
                ));
            }
            List<KlinePointResponse> result = aggregateA50Kline(daily, period);
            int size = Math.max(1, Math.min(limit, 240));
            return result.size() > size ? result.subList(result.size() - size, result.size()) : result;
        } catch (Exception ex) {
            throw new IllegalStateException("获取新浪 A50 K 线行情失败：" + ex.getMessage(), ex);
        }
    }

    private static List<KlinePointResponse> aggregateA50Kline(List<KlinePointResponse> daily, String period) {
        String normalized = period == null ? "day" : period.toLowerCase(Locale.ROOT);
        if (!List.of("week", "weekly", "month", "monthly").contains(normalized)) {
            return daily;
        }
        Map<String, KlineAccumulator> grouped = new LinkedHashMap<>();
        for (KlinePointResponse point : daily) {
            String key = normalized.startsWith("month")
                    ? point.tradeDate().format(DateTimeFormatter.ofPattern("yyyy-MM"))
                    : point.tradeDate().getYear() + "-" + point.tradeDate().get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
            grouped.computeIfAbsent(key, ignored -> new KlineAccumulator()).add(point);
        }
        return grouped.values().stream().map(KlineAccumulator::toResponse).toList();
    }

    @Override
    public StockQuoteResponse fetchQuote(String stockCode) {
        try {
            return fetchTencentQuotes(List.of(normalizeStockCode(stockCode))).values().stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("腾讯行情返回为空：" + stockCode));
        } catch (Exception ignored) {
            // 线上服务器访问腾讯行情更稳定，失败时再走东方财富和新浪备源。
        }
        try {
            return fetchEastmoneyStockQuote(stockCode);
        } catch (Exception ignored) {
            // 东方财富单股 quote 接口偶发空响应，失败时再走新浪备源。
        }
        Quote quote = fetchSinaQuotes(List.of(toSinaSymbol(stockCode))).values().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未获取到股票行情：" + stockCode));
        return new StockQuoteResponse(stockCode, quote.name(), quote.price(), quote.change(), quote.percent(), BigDecimal.ZERO, marketOf(stockCode), "SINA", LocalDateTime.now());
    }

    @Override
    public Map<String, StockQuoteResponse> fetchQuotes(List<String> stockCodes) {
        List<String> normalizedCodes = stockCodes == null ? List.of() : stockCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(SinaMarketDataClient::normalizeStockCode)
                .distinct()
                .toList();
        if (normalizedCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, StockQuoteResponse> result = new LinkedHashMap<>();
        try {
            result.putAll(fetchTencentQuotes(normalizedCodes));
        } catch (RuntimeException ex) {
            log.warn("tencent batch quote failed, try eastmoney fallback, batchSize={}", normalizedCodes.size(), ex);
        }
        List<String> missingAfterTencent = normalizedCodes.stream()
                .filter(code -> !result.containsKey(code))
                .toList();
        if (missingAfterTencent.isEmpty()) {
            return result;
        }
        for (int start = 0; start < missingAfterTencent.size(); start += QUOTE_BATCH_SIZE) {
            List<String> batch = missingAfterTencent.subList(start, Math.min(start + QUOTE_BATCH_SIZE, missingAfterTencent.size()));
            try {
                result.putAll(fetchEastmoneyQuotes(batch));
            } catch (RuntimeException ex) {
                log.warn("eastmoney batch quote failed, try sina fallback, batchSize={}", batch.size(), ex);
            }
            List<String> missingCodes = batch.stream()
                    .filter(code -> !result.containsKey(code))
                    .toList();
            if (!missingCodes.isEmpty()) {
                try {
                    result.putAll(fetchSinaQuoteResponses(missingCodes));
                } catch (RuntimeException ex) {
                    log.warn("sina batch quote fallback failed, batchSize={}", missingCodes.size(), ex);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("批量实时行情返回为空");
        }
        return result;
    }

    private Map<String, StockQuoteResponse> fetchTencentQuotes(List<String> normalizedCodes) {
        if (normalizedCodes == null || normalizedCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, StockQuoteResponse> result = new LinkedHashMap<>();
        RuntimeException firstFailure = null;
        for (int start = 0; start < normalizedCodes.size(); start += QUOTE_BATCH_SIZE) {
            List<String> batch = normalizedCodes.subList(start, Math.min(start + QUOTE_BATCH_SIZE, normalizedCodes.size()));
            try {
                result.putAll(fetchTencentQuoteBatch(batch));
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
                log.warn("tencent quote batch failed, batchSize={}", batch.size(), ex);
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        throw new IllegalStateException("腾讯行情返回为空");
    }

    private Map<String, StockQuoteResponse> fetchTencentQuoteBatch(List<String> normalizedCodes) {
        URI uri = URI.create("https://qt.gtimg.cn/q=" + String.join(",", normalizedCodes.stream()
                .map(SinaMarketDataClient::toSinaSymbol)
                .toList()));
        try {
            String text = getTextWithRetry(uri, GBK, "https://gu.qq.com/");
            Matcher matcher = TENCENT_QUOTE_PATTERN.matcher(text);
            Map<String, StockQuoteResponse> result = new LinkedHashMap<>();
            while (matcher.find()) {
                String symbol = matcher.group(1);
                String[] fields = matcher.group(2).split("~", -1);
                if (fields.length < 33 || fields[3].isBlank()) {
                    continue;
                }
                String code = normalizeStockCode(symbol);
                BigDecimal price = bdOrZero(fields[3]);
                BigDecimal prevClose = bdOrZero(fields[4]);
                BigDecimal change = fields[31].isBlank() ? price.subtract(prevClose) : bdOrZero(fields[31]);
                BigDecimal percent = fields[32].isBlank() || prevClose.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : bdOrZero(fields[32]);
                BigDecimal volumeRatio = fields.length > 49 ? bdOrZero(fields[49]) : BigDecimal.ZERO;
                result.put(code, new StockQuoteResponse(
                        code,
                        fields[1].isBlank() ? code : fields[1],
                        price,
                        change,
                        percent,
                        volumeRatio,
                        marketOf(symbol),
                        "TENCENT",
                        LocalDateTime.now()
                ));
            }
            if (result.isEmpty()) {
                throw new IllegalStateException("腾讯行情返回为空");
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("获取腾讯实时行情失败：" + ex.getMessage(), ex);
        }
    }

    private Map<String, StockQuoteResponse> fetchEastmoneyQuotes(List<String> normalizedCodes) {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://push2.eastmoney.com/api/qt/ulist.np/get")
                .queryParam("secids", String.join(",", normalizedCodes.stream()
                        .map(code -> toEastmoneyCode(toSinaSymbol(code)))
                        .toList()))
                .queryParam("fields", "f12,f13,f14,f2,f3,f4,f10,f152")
                .build(false)
                .toUri();
        try {
            JsonNode rows = objectMapper.readTree(getTextWithRetry(uri, StandardCharsets.UTF_8, "https://quote.eastmoney.com/"))
                    .path("data")
                    .path("diff");
            if (!rows.isArray()) {
                throw new IllegalStateException("东方财富批量行情为空");
            }
            Map<String, StockQuoteResponse> result = new LinkedHashMap<>();
            for (JsonNode row : rows) {
                String code = row.path("f12").asText("");
                if (code.isBlank()) {
                    continue;
                }
                String market = row.path("f13").asInt() == 1 ? "SH" : "SZ";
                StockQuoteResponse quote = new StockQuoteResponse(
                        code,
                        row.path("f14").asText(code),
                        eastmoneyScaledValue(row, "f2"),
                        eastmoneyScaledValue(row, "f4"),
                        eastmoneyScaledValue(row, "f3"),
                        eastmoneyScaledValue(row, "f10"),
                        market,
                        "EASTMONEY",
                        LocalDateTime.now()
                );
                result.put(code, quote);
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("获取东方财富批量实时行情失败：" + ex.getMessage(), ex);
        }
    }

    private Map<String, StockQuoteResponse> fetchSinaQuoteResponses(List<String> normalizedCodes) {
        Map<String, Quote> sinaQuotes = fetchSinaQuotes(normalizedCodes.stream()
                .map(SinaMarketDataClient::toSinaSymbol)
                .toList());
        Map<String, StockQuoteResponse> result = new LinkedHashMap<>();
        for (String code : normalizedCodes) {
            Quote quote = sinaQuotes.get(toSinaSymbol(code));
            if (quote == null) {
                continue;
            }
            result.put(code, new StockQuoteResponse(
                    code,
                    quote.name(),
                    quote.price(),
                    quote.change(),
                    quote.percent(),
                    BigDecimal.ZERO,
                    marketOf(code),
                    "SINA",
                    LocalDateTime.now()
            ));
        }
        return result;
    }

    @Override
    public FinanceSnapshotResponse fetchFinance(String stockCode) {
        return fetchFinanceInternal(stockCode, LocalDateTime.now(), true);
    }

    @Override
    public FinanceSnapshotResponse fetchFinanceAt(String stockCode, LocalDateTime asOfTime) {
        return fetchFinanceInternal(stockCode, asOfTime, false);
    }

    private FinanceSnapshotResponse fetchFinanceInternal(
            String stockCode,
            LocalDateTime asOfTime,
            boolean includeLiveValuation
    ) {
        if (asOfTime == null) {
            throw new IllegalArgumentException("财务快照 asOfTime 不能为空");
        }
        String sinaSymbol = toSinaSymbol(stockCode);
        String eastmoneyCode = toEastmoneyCode(sinaSymbol);
        JsonNode quote = null;
        JsonNode finance = null;

        if (includeLiveValuation) {
            try {
                quote = fetchEastmoneyQuote(eastmoneyCode);
            } catch (Exception ignored) {
                // 估值接口偶发断连，不应影响财报类指标展示。
            }
        }
        try {
            finance = selectFinanceAt(fetchEastmoneyFinanceHistory(sinaSymbol), asOfTime);
        } catch (Exception ignored) {
            // 财报接口失败时仍尽量返回估值类指标。
        }

        if (quote == null && finance == null) {
            return FinanceSnapshotResponse.empty();
        }

        try {
            BigDecimal eps = nullableDecimal(path(finance, "EPSJB"));
            BigDecimal bps = nullableDecimal(path(finance, "BPS"));
            BigDecimal pe = eastmoneyScaledNullableValue(quote, "f162");
            BigDecimal pb = eastmoneyScaledNullableValue(quote, "f167");
            LocalDateTime fetchedAt = LocalDateTime.now();
            return new FinanceSnapshotResponse(
                    pe,
                    pb,
                    nullableDecimal(path(quote, "f116")),
                    nullableDecimal(path(quote, "f117")),
                    eps,
                    bps,
                    nullableDecimal(path(finance, "TOTALOPERATEREVE")),
                    nullableDecimal(path(finance, "TOTALOPERATEREVETZ")),
                    nullableDecimal(path(finance, "PARENTNETPROFIT")),
                    nullableDecimal(path(finance, "PARENTNETPROFITTZ")),
                    nullableDecimal(path(finance, "ROEJQ")),
                    nullableDecimal(path(finance, "XSMLL")),
                    nullableDecimal(path(finance, "XSJLL")),
                    nullableDecimal(path(finance, "ZCFZL")),
                    nullableDecimal(path(finance, "MGJYXJJE")),
                    parseOptionalDate(firstText(finance, "REPORT_DATE", "REPORTDATE")),
                    financeVisibilityTime(finance),
                    fetchedAt,
                    includeLiveValuation ? "EASTMONEY" : "EASTMONEY_PIT"
            );
        } catch (RuntimeException ex) {
            return FinanceSnapshotResponse.empty();
        }
    }

    private JsonNode fetchEastmoneyQuote(String eastmoneyCode) throws Exception {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://push2.eastmoney.com/api/qt/stock/get")
                .queryParam("secid", eastmoneyCode)
                .queryParam("fields", "f57,f58,f116,f117,f162,f167,f152")
                .build(true)
                .toUri();
        JsonNode root = objectMapper.readTree(getTextWithRetry(uri, StandardCharsets.UTF_8, "https://quote.eastmoney.com/"));
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("东方财富估值数据为空");
        }
        return data;
    }

    private JsonNode fetchEastmoneyFinanceHistory(String sinaSymbol) throws Exception {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://emweb.securities.eastmoney.com/PC_HSF10/NewFinanceAnalysis/ZYZBAjaxNew")
                .queryParam("type", "0")
                .queryParam("code", toEastmoneyFinanceCode(sinaSymbol))
                .build(true)
                .toUri();
        JsonNode data = objectMapper.readTree(
                getText(uri, StandardCharsets.UTF_8, "https://emweb.securities.eastmoney.com/"))
                .path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("东方财富财报数据为空");
        }
        return data;
    }

    private static JsonNode selectFinanceAt(JsonNode history, LocalDateTime asOfTime) {
        JsonNode selected = null;
        LocalDateTime selectedVisibilityTime = null;
        LocalDate selectedReportDate = null;
        for (JsonNode candidate : history) {
            LocalDateTime visibilityTime = financeVisibilityTime(candidate);
            LocalDate reportDate = parseOptionalDate(firstText(candidate, "REPORT_DATE", "REPORTDATE"));
            if (visibilityTime == null || reportDate == null || visibilityTime.isAfter(asOfTime)) {
                continue;
            }
            if (selected == null
                    || reportDate.isAfter(selectedReportDate)
                    || (reportDate.equals(selectedReportDate) && visibilityTime.isAfter(selectedVisibilityTime))) {
                selected = candidate;
                selectedVisibilityTime = visibilityTime;
                selectedReportDate = reportDate;
            }
        }
        return selected;
    }

    private static LocalDateTime financeVisibilityTime(JsonNode finance) {
        LocalDateTime noticeAt = parseOptionalDateTime(firstText(finance, "NOTICE_DATE", "PUBLISH_DATE"));
        LocalDateTime updatedAt = parseOptionalDateTime(firstText(finance, "UPDATE_DATE"));
        if (noticeAt == null) {
            return updatedAt;
        }
        if (updatedAt == null) {
            return noticeAt;
        }
        return noticeAt.isAfter(updatedAt) ? noticeAt : updatedAt;
    }

    private Map<String, Quote> fetchSinaQuotes(List<String> symbols) {
        URI uri = UriComponentsBuilder
                .fromHttpUrl(properties.getMarket().getSinaBaseUrl())
                .queryParam("list", String.join(",", symbols))
                .build(true)
                .toUri();
        try {
            String text = getText(uri, GBK, "https://finance.sina.com.cn/");
            Matcher matcher = QUOTE_PATTERN.matcher(text);
            java.util.HashMap<String, Quote> quotes = new java.util.HashMap<>();
            while (matcher.find()) {
                String symbol = matcher.group(1);
                String[] fields = matcher.group(2).split(",", -1);
                if (fields.length < 4 || fields[3].isBlank()) {
                    continue;
                }
                BigDecimal prevClose = bd(fields[2]);
                BigDecimal price = bd(fields[3]);
                BigDecimal change = price.subtract(prevClose);
                BigDecimal percent = prevClose.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : change.multiply(new BigDecimal("100")).divide(prevClose, 4, java.math.RoundingMode.HALF_UP);
                quotes.put(symbol, new Quote(fields[0], price, change, percent));
            }
            if (quotes.isEmpty()) {
                throw new IllegalStateException("新浪行情返回为空");
            }
            return quotes;
        } catch (Exception ex) {
            throw new IllegalStateException("获取新浪实时行情失败：" + ex.getMessage(), ex);
        }
    }

    private String getText(URI uri, Charset charset, String referer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0");
        headers.set(HttpHeaders.REFERER, referer);
        ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("远程接口返回空响应");
        }
        return new String(body, charset);
    }

    private String getTextWithRetry(URI uri, Charset charset, String referer) {
        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return getText(uri, charset, referer);
            } catch (RuntimeException ex) {
                lastError = ex;
                sleepBeforeRetry(attempt);
            }
        }
        throw lastError == null ? new IllegalStateException("远程接口请求失败") : lastError;
    }

    private static void sleepBeforeRetry(int attempt) {
        if (attempt >= 2) {
            return;
        }
        try {
            Thread.sleep(200L * (attempt + 1));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        return LocalDateTime.parse(value, NEWS_TIME);
    }

    private static String normalizeTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        }
        return value.length() >= 5 ? value.substring(0, 5) : value;
    }

    private static String toSinaSymbol(String symbol) {
        String normalized = symbol.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sh") || normalized.startsWith("sz")) {
            return normalized;
        }
        if (normalized.endsWith(".sh")) {
            return "sh" + normalized.substring(0, normalized.length() - 3);
        }
        if (normalized.endsWith(".sz")) {
            return "sz" + normalized.substring(0, normalized.length() - 3);
        }
        return (normalized.startsWith("6") || normalized.startsWith("9") || normalized.startsWith("5") ? "sh" : "sz") + normalized;
    }

    private static String normalizeStockCode(String code) {
        String symbol = toSinaSymbol(code);
        return symbol.length() > 2 ? symbol.substring(2) : code;
    }

    private static String marketOf(String code) {
        return toSinaSymbol(code).startsWith("sh") ? "SH" : "SZ";
    }

    private static String toEastmoneyCode(String sinaSymbol) {
        return (sinaSymbol.startsWith("sh") ? "1." : "0.") + sinaSymbol.substring(2);
    }

    private static String toEastmoneyFinanceCode(String sinaSymbol) {
        return (sinaSymbol.startsWith("sh") ? "SH" : "SZ") + sinaSymbol.substring(2);
    }

    private static BigDecimal eastmoneyScaledValue(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = decimal(node.path(field));
        int scale = node.path("f152").asInt(2);
        return value.divide(BigDecimal.TEN.pow(scale), 4, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal eastmoneyScaledNullableValue(JsonNode node, String field) {
        BigDecimal value = nullableDecimal(node == null ? null : node.path(field));
        if (value == null) {
            return null;
        }
        int scale = node.path("f152").asInt(2);
        return value.divide(BigDecimal.TEN.pow(scale), 4, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null) {
            return BigDecimal.ZERO;
        }
        if (node.isMissingNode() || node.isNull() || "-".equals(node.asText())) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(node.asText());
    }

    private static BigDecimal nullableDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText("").trim();
        if (text.isBlank() || "-".equals(text) || "--".equals(text)) {
            return null;
        }
        return new BigDecimal(text);
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank() && !"-".equals(value)) {
                return value;
            }
        }
        return null;
    }

    private static LocalDate parseOptionalDate(String value) {
        if (value == null || value.isBlank() || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static LocalDateTime parseOptionalDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('T', ' ');
        if (normalized.length() == 10) {
            LocalDate date = parseOptionalDate(normalized);
            return date == null ? null : date.atStartOfDay();
        }
        if (normalized.length() >= 19) {
            normalized = normalized.substring(0, 19);
        }
        try {
            return LocalDateTime.parse(normalized, NEWS_TIME);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static JsonNode path(JsonNode node, String field) {
        return node == null ? null : node.path(field);
    }

    private static BigDecimal bd(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    private static BigDecimal bdOrZero(String value) {
        try {
            return bd(value);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private record IndexSymbol(String name, String code, String sinaSymbol) {
    }

    private record Quote(String name, BigDecimal price, BigDecimal change, BigDecimal percent) {
    }

    private static class KlineAccumulator {
        private LocalDate tradeDate;
        private BigDecimal open;
        private BigDecimal close;
        private BigDecimal low;
        private BigDecimal high;
        private long volume;

        private void add(KlinePointResponse point) {
            if (tradeDate == null) {
                tradeDate = point.tradeDate();
                open = point.open();
                low = point.low();
                high = point.high();
            }
            close = point.close();
            low = low.min(point.low());
            high = high.max(point.high());
            volume += point.volume() == null ? 0 : point.volume();
            if (point.tradeDate().isAfter(tradeDate)) {
                tradeDate = point.tradeDate();
            }
        }

        private KlinePointResponse toResponse() {
            return new KlinePointResponse(tradeDate, open, close, low, high, volume, BigDecimal.ZERO);
        }
    }
}
