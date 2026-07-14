package com.maogou.stock.service.research;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.NewsFlashResponse;
import com.maogou.stock.infrastructure.market.ResearchMarketDataClient;
import com.maogou.stock.infrastructure.market.ResearchSourceResult;
import com.maogou.stock.infrastructure.market.ResearchSourceStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class NewsSentimentFeatureService {

    private static final Set<String> POSITIVE_WORDS = Set.of(
            "增长", "回暖", "突破", "上调", "增持", "中标", "盈利", "改善", "利好", "创新高", "扩产", "订单");
    private static final Set<String> NEGATIVE_WORDS = Set.of(
            "下滑", "亏损", "暴跌", "减持", "处罚", "风险", "退市", "违约", "利空", "调查", "终止", "下调");

    private final ResearchMarketDataClient marketDataClient;
    private final AppProperties properties;

    public NewsSentimentFeatureService(
            ResearchMarketDataClient marketDataClient,
            AppProperties properties
    ) {
        this.marketDataClient = marketDataClient;
        this.properties = properties;
    }

    public NewsBatch load(LocalDateTime asOfTime, int limit) {
        if (marketDataClient == null) {
            return new NewsBatch(List.of(), ResearchSourceStatus.UNAVAILABLE,
                    "UNAVAILABLE", null, "资讯数据源未接入");
        }
        ResearchSourceResult<List<NewsFlashResponse>> result = marketDataClient.fetchNewsAt(limit, asOfTime);
        if (!result.formalReady()) {
            return new NewsBatch(List.of(), result.sourceStatus(), result.providerCode(),
                    result.responseFingerprint(), result.message());
        }
        return new NewsBatch(result.data(), result.sourceStatus(), result.providerCode(),
                result.responseFingerprint(), null);
    }

    public Feature calculate(
            List<NewsFlashResponse> news,
            String stockCode,
            String stockName,
            String industryName,
            LocalDateTime asOfTime
    ) {
        if (asOfTime == null) {
            return Feature.unavailable("缺少资讯研究截止时间");
        }
        LocalDateTime cutoff = asOfTime.minusHours(Math.max(1, properties.getMarket().getNewsFeatureWindowHours()));
        List<NewsFlashResponse> visible = (news == null ? List.<NewsFlashResponse>of() : news).stream()
                .filter(item -> item != null && item.publishedAt() != null)
                .filter(item -> !item.publishedAt().isAfter(asOfTime) && !item.publishedAt().isBefore(cutoff))
                .filter(item -> item.title() != null && !item.title().isBlank())
                .sorted(Comparator.comparing(NewsFlashResponse::publishedAt))
                .toList();
        if (visible.isEmpty()) {
            return Feature.unavailable("允许时间窗口内没有已发布资讯");
        }

        List<NewsFlashResponse> relevant = visible.stream()
                .filter(item -> relevant(item.title(), stockCode, stockName, industryName))
                .toList();
        List<NewsFlashResponse> included = relevant.isEmpty() ? visible : relevant;
        BigDecimal weightedScore = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (NewsFlashResponse item : included) {
            long ageMinutes = Math.max(0, Duration.between(item.publishedAt(), asOfTime).toMinutes());
            BigDecimal weight = BigDecimal.ONE.divide(
                    BigDecimal.ONE.add(BigDecimal.valueOf(ageMinutes).divide(BigDecimal.valueOf(180), 8,
                            RoundingMode.HALF_UP)), 8, RoundingMode.HALF_UP);
            weightedScore = weightedScore.add(BigDecimal.valueOf(score(item.title())).multiply(weight));
            totalWeight = totalWeight.add(weight);
        }
        BigDecimal sentiment = totalWeight.signum() == 0 ? null
                : weightedScore.divide(totalWeight, 8, RoundingMode.HALF_UP)
                .max(BigDecimal.ONE.negate()).min(BigDecimal.ONE);
        if (sentiment == null) {
            return Feature.unavailable("资讯情绪无法计算");
        }
        LocalDateTime latest = included.stream().map(NewsFlashResponse::publishedAt)
                .max(Comparator.naturalOrder()).orElse(null);
        return new Feature(sentiment, latest, included, sha256(included.toString()), null);
    }

    private static boolean relevant(String title, String code, String name, String industry) {
        String normalized = title.toUpperCase(Locale.ROOT);
        return contains(normalized, code) || contains(normalized, name) || contains(normalized, industry)
                || normalized.contains("A股") || normalized.contains("市场") || normalized.contains("大盘");
    }

    private static boolean contains(String text, String candidate) {
        return candidate != null && !candidate.isBlank() && text.contains(candidate.toUpperCase(Locale.ROOT));
    }

    private static int score(String title) {
        int positive = (int) POSITIVE_WORDS.stream().filter(title::contains).count();
        int negative = (int) NEGATIVE_WORDS.stream().filter(title::contains).count();
        int raw = positive - negative;
        return Integer.compare(raw, 0);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record NewsBatch(
            List<NewsFlashResponse> news,
            ResearchSourceStatus sourceStatus,
            String providerCode,
            String sourceFingerprint,
            String missingReason
    ) {
        public NewsBatch {
            news = news == null ? List.of() : List.copyOf(news);
        }

        public boolean available() {
            return sourceStatus == ResearchSourceStatus.REALTIME && !news.isEmpty();
        }
    }

    public record Feature(
            BigDecimal sentiment,
            LocalDateTime latestPublishedAt,
            List<NewsFlashResponse> includedNews,
            String sourceFingerprint,
            String missingReason
    ) {
        public Feature {
            includedNews = includedNews == null ? List.of() : List.copyOf(includedNews);
        }

        public boolean available() {
            return sentiment != null && latestPublishedAt != null;
        }

        static Feature unavailable(String reason) {
            return new Feature(null, null, new ArrayList<>(), null, reason);
        }
    }
}
