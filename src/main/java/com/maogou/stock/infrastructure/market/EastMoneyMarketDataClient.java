package com.maogou.stock.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.NewsFlashResponse;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EastMoneyMarketDataClient implements ResearchMarketDataProvider {

    private static final Pattern NEWS_PATTERN = Pattern.compile("var ajaxResult=(\\{.*});?", Pattern.DOTALL);
    private static final DateTimeFormatter NEWS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public EastMoneyMarketDataClient(
            @Qualifier("marketRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerCode() {
        return "EASTMONEY";
    }

    @Override
    public boolean supports(String endpointType) {
        return ENDPOINT_KLINE.equals(endpointType)
                || ENDPOINT_INDUSTRY.equals(endpointType)
                || ENDPOINT_NEWS.equals(endpointType);
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
        String normalizedPeriod = period == null || period.isBlank() ? "day" : period.toLowerCase(Locale.ROOT);
        String klt = switch (normalizedPeriod) {
            case "week", "weekly" -> "102";
            case "month", "monthly" -> "103";
            default -> "101";
        };
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://push2his.eastmoney.com/api/qt/stock/kline/get")
                .queryParam("secid", eastMoneySecurityId(symbol))
                .queryParam("klt", klt)
                .queryParam("fqt", "0")
                .queryParam("lmt", Math.max(1, Math.min(limit, 500)))
                .queryParam("end", asOfTime.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE))
                .queryParam("fields1", "f1,f2,f3,f4,f5,f6")
                .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61")
                .build(false)
                .toUri();
        try {
            JsonNode rows = objectMapper.readTree(getText(uri, "https://quote.eastmoney.com/"))
                    .path("data").path("klines");
            if (!rows.isArray()) {
                throw new IllegalStateException("东方财富 K 线返回为空");
            }
            List<KlinePointResponse> points = new ArrayList<>();
            for (JsonNode row : rows) {
                String[] fields = row.asText("").split(",", -1);
                if (fields.length < 7) {
                    continue;
                }
                LocalDate tradeDate = LocalDate.parse(fields[0]);
                if (tradeDate.isAfter(asOfTime.toLocalDate())) {
                    continue;
                }
                long volumeInLots = decimal(fields[5]).longValue();
                points.add(new KlinePointResponse(
                        tradeDate,
                        decimal(fields[1]),
                        decimal(fields[2]),
                        decimal(fields[4]),
                        decimal(fields[3]),
                        Math.multiplyExact(volumeInLots, 100L),
                        decimal(fields[6])
                ));
            }
            boolean closingBarAvailable = !asOfTime.toLocalTime().isBefore(LocalTime.of(15, 5));
            List<KlinePointResponse> visible = points.stream()
                    .filter(point -> point.tradeDate().isBefore(asOfTime.toLocalDate())
                            || closingBarAvailable && point.tradeDate().isEqual(asOfTime.toLocalDate()))
                    .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                    .toList();
            if (visible.isEmpty()) {
                throw new IllegalStateException("指定研究时点前没有东方财富 K 线");
            }
            return KlineSeriesSnapshot.create(
                    symbol, normalizedPeriod, "NONE", providerCode(), asOfTime, LocalDateTime.now(), visible);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("获取东方财富 K 线失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public IndustryMembershipData fetchIndustryAt(String stockCode, LocalDateTime asOfTime) {
        if (asOfTime == null) {
            throw new IllegalArgumentException("行业归属 asOfTime 不能为空");
        }
        URI uri = UriComponentsBuilder
                .fromHttpUrl("https://emweb.securities.eastmoney.com/PC_HSF10/CoreConception/PageAjax")
                .queryParam("code", eastMoneyFinanceCode(stockCode))
                .build(true)
                .toUri();
        try {
            JsonNode root = objectMapper.readTree(getText(uri, "https://emweb.securities.eastmoney.com/"));
            JsonNode boards = firstArray(root, "ssbk", "SSBK", "data");
            if (boards == null) {
                throw new IllegalStateException("东方财富行业归属返回为空");
            }
            JsonNode selected = selectIndustryBoard(boards);
            if (selected != null) {
                String code = normalizeBoardCode(firstText(
                        selected, "BOARD_CODE", "BOARD_CODE_1", "boardCode", "BK_CODE"));
                String name = firstText(selected, "BOARD_NAME", "boardName", "BK_NAME");
                LocalDateTime fetchedAt = LocalDateTime.now();
                String fingerprint = sha256(String.join("|", stockCode, code, name,
                        asOfTime.toLocalDate().toString()));
                return new IndustryMembershipData(
                        normalizeStockCode(stockCode), code, name,
                        asOfTime.toLocalDate(), null, fetchedAt, fingerprint);
            }
            throw new IllegalStateException("东方财富没有返回有效行业板块代码");
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("获取东方财富行业归属失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    public List<NewsFlashResponse> fetchNewsAt(int limit, LocalDateTime asOfTime) {
        int size = Math.max(1, Math.min(100, limit));
        URI uri = URI.create("https://newsapi.eastmoney.com/kuaixun/v1/getlist_102_ajaxResult_"
                + Math.max(size, 50) + "_1_.html?r=" + UUID.randomUUID());
        try {
            String text = getText(uri, "https://kuaixun.eastmoney.com/");
            Matcher matcher = NEWS_PATTERN.matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException("东方财富资讯返回格式异常");
            }
            JsonNode rows = objectMapper.readTree(matcher.group(1)).path("LivesList");
            if (!rows.isArray()) {
                throw new IllegalStateException("东方财富资讯返回为空");
            }
            List<NewsFlashResponse> news = new ArrayList<>();
            for (JsonNode row : rows) {
                LocalDateTime publishedAt = parseDateTime(row.path("showtime").asText(null));
                if (publishedAt == null || publishedAt.isAfter(asOfTime)) {
                    continue;
                }
                news.add(new NewsFlashResponse(
                        publishedAt.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                        row.path("title").asText(""),
                        providerCode(),
                        row.path("url_m").asText(row.path("url_w").asText(null)),
                        publishedAt));
                if (news.size() >= size) {
                    break;
                }
            }
            if (news.isEmpty()) {
                throw new IllegalStateException("研究截止时间前没有可用东方财富资讯");
            }
            return List.copyOf(news);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("获取东方财富资讯失败：" + exception.getMessage(), exception);
        }
    }

    private String getText(URI uri, String referer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 MaogouResearch/1.0");
        headers.set(HttpHeaders.REFERER, referer);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("东方财富返回空响应");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static JsonNode firstArray(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.path(name);
            if (node.isArray()) {
                return node;
            }
            if (node.isObject()) {
                for (JsonNode child : node) {
                    if (child.isArray()) {
                        return child;
                    }
                }
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static JsonNode selectIndustryBoard(JsonNode boards) {
        JsonNode firstLevel = null;
        for (JsonNode board : boards) {
            String code = normalizeBoardCode(firstText(
                    board, "BOARD_CODE", "BOARD_CODE_1", "boardCode", "BK_CODE"));
            String name = firstText(board, "BOARD_NAME", "boardName", "BK_NAME");
            if (code == null || name == null || name.isBlank()) {
                continue;
            }
            int rank = board.path("BOARD_RANK").asInt(Integer.MAX_VALUE);
            if (rank == 2) {
                return board;
            }
            if (rank == 1 && firstLevel == null) {
                firstLevel = board;
            }
        }
        return firstLevel;
    }

    private static String normalizeBoardCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("BK")) {
            return normalized;
        }
        if (!normalized.matches("\\d{1,4}")) {
            return null;
        }
        return "BK" + "0".repeat(Math.max(0, 4 - normalized.length())) + normalized;
    }

    private static String eastMoneySecurityId(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("BK")) {
            return "90." + normalized;
        }
        String code = normalizeStockCode(normalized);
        boolean sh = normalized.startsWith("SH") || normalized.endsWith(".SH")
                || code.startsWith("6") || code.startsWith("5") || code.startsWith("9")
                || List.of("000001", "000016", "000300", "000688", "000852", "000905").contains(code);
        return (sh ? "1." : "0.") + code;
    }

    private static String eastMoneyFinanceCode(String stockCode) {
        String code = normalizeStockCode(stockCode);
        return (code.startsWith("6") ? "SH" : "SZ") + code;
    }

    private static String normalizeStockCode(String stockCode) {
        String digits = stockCode == null ? "" : stockCode.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(digits.length() - 6) : digits;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank() || "-".equals(value) || "--".equals(value)) {
            throw new IllegalStateException("东方财富数值字段缺失");
        }
        return new BigDecimal(value);
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, NEWS_TIME);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
