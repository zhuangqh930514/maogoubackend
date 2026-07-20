package com.maogou.stock.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SinaTencentHistoricalMarketDataClient implements HistoricalMarketDataProvider {

    private static final int CATALOG_PAGE_SIZE = 100;
    private static final int CATALOG_PAGES_PER_BOARD = 3;
    private static final List<String> CATALOG_NODES = List.of("sh_a", "sz_a", "cyb", "kcb");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public SinaTencentHistoricalMarketDataClient(
            @Qualifier("marketRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerCode() {
        return "SINA_TENCENT";
    }

    @Override
    public UniverseCatalog fetchCurrentListedUniverse(int limit, LocalDateTime requestedAt) {
        if (requestedAt == null) {
            throw new IllegalArgumentException("历史股票目录缺少请求时间");
        }
        int size = Math.max(1, Math.min(limit, 1000));
        Map<String, Security> byCode = new LinkedHashMap<>();
        List<String> sourcePages = new ArrayList<>();
        for (String node : CATALOG_NODES) {
            for (int page = 1; page <= CATALOG_PAGES_PER_BOARD; page++) {
                URI uri = catalogUri(node, page);
                sourcePages.add(uri.toString());
                JsonNode rows = readJson(uri, "https://finance.sina.com.cn/");
                if (!rows.isArray()) {
                    throw new IllegalStateException("新浪 A 股目录返回格式异常：" + node);
                }
                for (JsonNode row : rows) {
                    String code = row.path("code").asText("").trim();
                    String name = row.path("name").asText("").trim();
                    String symbol = row.path("symbol").asText("").trim().toLowerCase(Locale.ROOT);
                    if (!code.matches("[036]\\d{5}") || name.isBlank()
                            || !(symbol.startsWith("sh") || symbol.startsWith("sz"))
                            || !hasPositiveMarketPrice(row, "trade")) {
                        continue;
                    }
                    String market = symbol.startsWith("sh") ? "SH" : "SZ";
                    byCode.putIfAbsent(code, new Security(code, name, market, null));
                }
            }
        }
        List<Security> securities = byCode.values().stream()
                .sorted(Comparator.comparing(value -> sha256(value.stockCode())))
                .limit(size)
                .toList();
        if (securities.size() < size) {
            throw new IllegalStateException("新浪 A 股目录不足：需要 " + size + " 只，实际 " + securities.size());
        }
        return new UniverseCatalog(
                providerCode(), LocalDateTime.now(),
                "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData",
                sha256(sourcePages + "|" + securities), securities);
    }

    private static boolean hasPositiveMarketPrice(JsonNode row, String... fields) {
        for (String field : fields) {
            String value = row.path(field).asText("").trim();
            try {
                if (!value.isBlank() && !"-".equals(value) && new BigDecimal(value).signum() > 0) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Current-listing evidence must come from a valid market price field.
            }
        }
        return false;
    }

    @Override
    public KlineSeriesSnapshot fetchHistoricalKline(
            String symbol,
            int limit,
            LocalDateTime asOfTime,
            String adjustmentMode
    ) {
        if (asOfTime == null) {
            throw new IllegalArgumentException("历史 K 线缺少截止时间");
        }
        String mode = adjustmentMode == null ? "NONE" : adjustmentMode.trim().toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "QFQ").contains(mode)) {
            throw new IllegalArgumentException("腾讯历史 K 线不支持复权模式：" + adjustmentMode);
        }
        int size = Math.max(1, Math.min(limit, 500));
        String security = tencentSymbol(symbol);
        LocalDate startDate = asOfTime.toLocalDate().minusDays(Math.max(365L, size * 3L));
        String suffix = "QFQ".equals(mode) ? "qfq" : "";
        String parameter = String.join(",",
                security, "day", startDate.toString(), asOfTime.toLocalDate().toString(),
                String.valueOf(size), suffix);
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get")
                .queryParam("param", parameter)
                .build(false)
                .toUri();
        JsonNode securityData = readJson(uri, "https://gu.qq.com/").path("data").path(security);
        JsonNode rows = securityData.path("QFQ".equals(mode) ? "qfqday" : "day");
        if (!rows.isArray()) {
            throw new IllegalStateException("腾讯历史 K 线返回为空：" + symbol + "/" + mode);
        }
        boolean closingBarAvailable = !asOfTime.toLocalTime().isBefore(LocalTime.of(15, 5));
        List<KlinePointResponse> points = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 6) {
                continue;
            }
            LocalDate tradeDate = LocalDate.parse(row.get(0).asText());
            if (tradeDate.isAfter(asOfTime.toLocalDate())
                    || tradeDate.isEqual(asOfTime.toLocalDate()) && !closingBarAvailable) {
                continue;
            }
            long volumeInLots = decimal(row.get(5).asText()).longValue();
            points.add(new KlinePointResponse(
                    tradeDate,
                    decimal(row.get(1).asText()),
                    decimal(row.get(2).asText()),
                    decimal(row.get(4).asText()),
                    decimal(row.get(3).asText()),
                    Math.multiplyExact(volumeInLots, 100L),
                    null
            ));
        }
        points.sort(Comparator.comparing(KlinePointResponse::tradeDate));
        if (points.isEmpty()) {
            throw new IllegalStateException("指定研究时点前没有腾讯历史 K 线：" + symbol);
        }
        List<KlinePointResponse> tail = points.subList(Math.max(0, points.size() - size), points.size());
        return KlineSeriesSnapshot.create(
                symbol, "day", mode, "TENCENT", asOfTime, LocalDateTime.now(), tail);
    }

    private JsonNode readJson(URI uri, String referer) {
        try {
            return objectMapper.readTree(getText(uri, referer));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("历史行情 JSON 解析失败：" + exception.getMessage(), exception);
        }
    }

    private String getText(URI uri, String referer) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 MaogouResearch/1.0");
                headers.set(HttpHeaders.REFERER, referer);
                ResponseEntity<byte[]> response = restTemplate.exchange(
                        uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
                byte[] body = response.getBody();
                if (body == null || body.length == 0) {
                    throw new IllegalStateException("历史行情来源返回空响应");
                }
                return new String(body, StandardCharsets.UTF_8);
            } catch (RestClientException | IllegalStateException exception) {
                lastFailure = exception;
                if (attempt < 3) {
                    sleep(attempt);
                }
            }
        }
        throw lastFailure == null ? new IllegalStateException("历史行情来源请求失败") : lastFailure;
    }

    private static URI catalogUri(String node, int page) {
        return UriComponentsBuilder
                .fromHttpUrl("https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData")
                .queryParam("page", page)
                .queryParam("num", CATALOG_PAGE_SIZE)
                .queryParam("sort", "symbol")
                .queryParam("asc", 1)
                .queryParam("node", node)
                .queryParam("symbol", "")
                .queryParam("_s_r_a", "page")
                .build(false)
                .toUri();
    }

    private static String tencentSymbol(String symbol) {
        String code = normalizeStockCode(symbol);
        if (!code.matches("[036]\\d{5}")) {
            throw new IllegalArgumentException("腾讯历史 K 线不支持证券代码：" + symbol);
        }
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        boolean sh = normalized.startsWith("SH") || normalized.endsWith(".SH")
                || code.startsWith("6")
                || List.of("000001", "000016", "000300", "000688", "000905").contains(code);
        return (sh ? "sh" : "sz") + code;
    }

    private static String normalizeStockCode(String symbol) {
        String digits = symbol == null ? "" : symbol.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(digits.length() - 6) : digits;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank() || "-".equals(value) || "--".equals(value)) {
            throw new IllegalStateException("历史行情数值字段缺失");
        }
        return new BigDecimal(value);
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("历史行情请求重试被中断", exception);
        }
    }

    private static String sha256(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
