package com.maogou.stock.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.*;
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
public class SinaMarketDataClient implements MarketDataClient {

    private static final Charset GBK = Charset.forName("GBK");
    private static final DateTimeFormatter NEWS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern QUOTE_PATTERN = Pattern.compile("var hq_str_([a-z0-9]+)=\\\"(.*?)\\\";");
    private static final Pattern JSONP_ARRAY_PATTERN = Pattern.compile("\\((\\[.*])\\)", Pattern.DOTALL);
    private static final Pattern EASTMONEY_NEWS_PATTERN = Pattern.compile("var ajaxResult=(\\{.*});?", Pattern.DOTALL);
    private static final Pattern SUGGEST_PATTERN = Pattern.compile("var suggestvalue=\\\"(.*?)\\\";", Pattern.DOTALL);
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
    public List<StockSearchResponse> searchStocks(String keyword, int limit) {
        URI uri = URI.create("https://suggest3.sinajs.cn/suggest/type=11,12,13,14,15&key="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        try {
            String text = getText(uri, GBK, "https://finance.sina.com.cn/");
            Matcher matcher = SUGGEST_PATTERN.matcher(text);
            if (!matcher.find() || matcher.group(1).isBlank()) {
                return List.of();
            }
            Map<String, StockSearchResponse> results = new LinkedHashMap<>();
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
                if (results.size() >= limit) {
                    break;
                }
            }
            return new ArrayList<>(results.values());
        } catch (Exception ex) {
            throw new IllegalStateException("搜索新浪股票失败：" + ex.getMessage(), ex);
        }
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
        Map<String, Quote> quotes = fetchQuotes(CORE_INDEXES.stream().map(IndexSymbol::sinaSymbol).toList());
        return CORE_INDEXES.stream()
                .map(index -> {
                    Quote quote = quotes.get(index.sinaSymbol());
                    if (quote == null) {
                        throw new IllegalStateException("未获取到指数行情：" + index.name());
                    }
                    List<BigDecimal> trend = fetchIntraday(index.sinaSymbol()).stream()
                            .map(IntradayPointResponse::value)
                            .toList();
                    return new MarketIndexResponse(index.name(), index.code(), quote.price(), quote.change(), quote.percent(), trend);
                })
                .toList();
    }

    @Override
    public List<IntradayPointResponse> fetchIntraday(String symbol) {
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

    @Override
    public List<KlinePointResponse> fetchKline(String symbol, String period, int limit) {
        String sinaSymbol = toSinaSymbol(symbol);
        String scale = switch (period == null ? "day" : period.toLowerCase(Locale.ROOT)) {
            case "week", "weekly" -> "1200";
            case "month", "monthly" -> "7200";
            default -> "240";
        };
        URI uri = URI.create("https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_" + sinaSymbol + "_" + scale
                + "=/CN_MarketDataService.getKLineData?symbol=" + sinaSymbol
                + "&scale=" + scale
                + "&ma=no&datalen=" + Math.max(1, Math.min(limit, 240)));
        try {
            String text = getText(uri, StandardCharsets.UTF_8, "https://finance.sina.com.cn/");
            Matcher matcher = JSONP_ARRAY_PATTERN.matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException("新浪 K 线返回格式异常");
            }
            JsonNode points = objectMapper.readTree(matcher.group(1));
            List<KlinePointResponse> result = new ArrayList<>();
            for (JsonNode point : points) {
                result.add(new KlinePointResponse(
                        LocalDate.parse(point.path("day").asText()),
                        bd(point.path("open").asText("0")),
                        bd(point.path("close").asText("0")),
                        bd(point.path("low").asText("0")),
                        bd(point.path("high").asText("0")),
                        point.path("volume").asLong(0),
                        BigDecimal.ZERO
                ));
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("获取新浪 K 线行情失败：" + symbol + "，" + ex.getMessage(), ex);
        }
    }

    @Override
    public StockQuoteResponse fetchQuote(String stockCode) {
        Quote quote = fetchQuotes(List.of(toSinaSymbol(stockCode))).values().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未获取到股票行情：" + stockCode));
        return new StockQuoteResponse(stockCode, quote.name(), quote.price(), quote.change(), quote.percent(), BigDecimal.ZERO, marketOf(stockCode));
    }

    @Override
    public FinanceSnapshotResponse fetchFinance(String stockCode) {
        String sinaSymbol = toSinaSymbol(stockCode);
        String eastmoneyCode = toEastmoneyCode(sinaSymbol);
        JsonNode quote = null;
        JsonNode finance = null;

        try {
            quote = fetchEastmoneyQuote(eastmoneyCode);
        } catch (Exception ignored) {
            // 估值接口偶发断连，不应影响财报类指标展示。
        }
        try {
            finance = fetchEastmoneyFinance(sinaSymbol);
        } catch (Exception ignored) {
            // 财报接口失败时仍尽量返回估值类指标。
        }

        if (quote == null && finance == null) {
            return FinanceSnapshotResponse.empty();
        }

        try {
            BigDecimal eps = decimal(path(finance, "EPSJB"));
            BigDecimal bps = decimal(path(finance, "BPS"));
            BigDecimal pe = eastmoneyScaledValue(quote, "f162");
            BigDecimal pb = eastmoneyScaledValue(quote, "f167");
            if ((pe.signum() == 0 || pb.signum() == 0) && finance != null) {
                StockQuoteResponse stockQuote = fetchQuote(stockCode);
                pe = pe.signum() == 0 ? estimatePe(stockQuote.price(), eps) : pe;
                pb = pb.signum() == 0 ? divide(stockQuote.price(), bps) : pb;
            }
            return new FinanceSnapshotResponse(
                    pe,
                    pb,
                    decimal(path(quote, "f116")),
                    decimal(path(quote, "f117")),
                    eps,
                    bps,
                    decimal(path(finance, "TOTALOPERATEREVE")),
                    decimal(path(finance, "TOTALOPERATEREVETZ")),
                    decimal(path(finance, "PARENTNETPROFIT")),
                    decimal(path(finance, "PARENTNETPROFITTZ")),
                    decimal(path(finance, "ROEJQ")),
                    decimal(path(finance, "XSMLL")),
                    decimal(path(finance, "XSJLL")),
                    decimal(path(finance, "ZCFZL")),
                    decimal(path(finance, "MGJYXJJE"))
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

    private JsonNode fetchEastmoneyFinance(String sinaSymbol) throws Exception {
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://emweb.securities.eastmoney.com/PC_HSF10/NewFinanceAnalysis/ZYZBAjaxNew")
                .queryParam("type", "0")
                .queryParam("code", toEastmoneyFinanceCode(sinaSymbol))
                .build(true)
                .toUri();
        JsonNode data = objectMapper.readTree(getText(uri, StandardCharsets.UTF_8, "https://emweb.securities.eastmoney.com/")).path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("东方财富财报数据为空");
        }
        return data.get(0);
    }

    private Map<String, Quote> fetchQuotes(List<String> symbols) {
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
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return getText(uri, charset, referer);
            } catch (RuntimeException ex) {
                lastError = ex;
            }
        }
        throw lastError == null ? new IllegalStateException("远程接口请求失败") : lastError;
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

    private static BigDecimal decimal(JsonNode node) {
        if (node == null) {
            return BigDecimal.ZERO;
        }
        if (node.isMissingNode() || node.isNull() || "-".equals(node.asText())) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(node.asText());
    }

    private static JsonNode path(JsonNode node, String field) {
        return node == null ? null : node.path(field);
    }

    private static BigDecimal estimatePe(BigDecimal price, BigDecimal quarterlyEps) {
        if (price == null || quarterlyEps == null || price.signum() <= 0 || quarterlyEps.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return divide(price, quarterlyEps.multiply(new BigDecimal("4")));
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || numerator.signum() <= 0 || denominator.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, 4, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal bd(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    private record IndexSymbol(String name, String code, String sinaSymbol) {
    }

    private record Quote(String name, BigDecimal price, BigDecimal change, BigDecimal percent) {
    }
}
