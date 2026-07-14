package com.maogou.stock.service.impl.research;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.NewsFlashResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.mapper.research.AiFactorValueMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.service.research.AiFactorEngine;
import com.maogou.stock.service.research.IndustryMembershipService;
import com.maogou.stock.service.research.NewsSentimentFeatureService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiFactorInputFreshnessTest {

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 7, 14, 16, 0);

    @Test
    void missingIndustryAndNewsRemainExplicitMissingValuesInsteadOfZeroSignals() {
        AiFactorEngine engine = new AiFactorEngineImpl(mock(AiFactorValueMapper.class));
        AiSample sample = sample();

        List<AiFactorValue> factors = engine.compute(new AiFactorEngine.FactorContext(
                sample,
                detail(List.of(
                        kline("2026-07-09", "10"),
                        kline("2026-07-10", "10"),
                        kline("2026-07-13", "10"),
                        kline("2026-07-14", "11"))),
                List.of(), List.of(), null, null));

        assertMissing(factors, "MARKET_RELATIVE_STRENGTH");
        assertMissing(factors, "SECTOR_RELATIVE_STRENGTH");
        assertMissing(factors, "NEWS_SENTIMENT");
    }

    @Test
    void futureExtremeBarsCannotChangePointInTimeTechnicalFactors() {
        AiFactorEngine engine = new AiFactorEngineImpl(mock(AiFactorValueMapper.class));
        AiSample sample = sample();
        List<KlinePointResponse> visible = new ArrayList<>(List.of(
                kline("2026-07-08", "8"),
                kline("2026-07-09", "9"),
                kline("2026-07-10", "10"),
                kline("2026-07-13", "11"),
                kline("2026-07-14", "12")));

        BigDecimal before = factor(engine.compute(sample, detail(visible)), "MOMENTUM_RETURN_3D").rawValue;
        visible.add(kline("2026-07-15", "999999"));
        BigDecimal after = factor(engine.compute(sample, detail(visible)), "MOMENTUM_RETURN_3D").rawValue;

        assertThat(after).isEqualByComparingTo(before);
    }

    @Test
    void industryMembershipUsesTheSnapshotEffectiveAtTheRequestedTime() {
        AiResearchUniverseItemMapper mapper = mock(AiResearchUniverseItemMapper.class);
        AiResearchUniverseItem oldMembership = membership("BK0477", "白酒");
        AiResearchUniverseItem newMembership = membership("BK1036", "食品饮料");
        when(mapper.selectIndustryMembershipAt("600519", LocalDate.of(2026, 6, 30)))
                .thenReturn(oldMembership);
        when(mapper.selectIndustryMembershipAt("600519", LocalDate.of(2026, 7, 14)))
                .thenReturn(newMembership);
        IndustryMembershipService service = new IndustryMembershipService(
                mapper, null, fixedClock());

        IndustryMembershipService.Membership june = service.resolve(
                "600519", LocalDateTime.of(2026, 6, 30, 16, 0));
        IndustryMembershipService.Membership july = service.resolve("600519", AS_OF);

        assertThat(june.industryCode()).isEqualTo("BK0477");
        assertThat(july.industryCode()).isEqualTo("BK1036");
    }

    @Test
    void newsFeatureRejectsFutureAndExpiredNews() {
        AppProperties properties = new AppProperties();
        properties.getMarket().setNewsFeatureWindowHours(36);
        NewsSentimentFeatureService service = new NewsSentimentFeatureService(null, properties);
        List<NewsFlashResponse> news = List.of(
                news("公司订单增长，行业景气回暖", AS_OF.minusHours(2)),
                news("未来消息不允许进入样本", AS_OF.plusMinutes(1)),
                news("过期利好不允许进入样本", AS_OF.minusHours(37)));

        NewsSentimentFeatureService.Feature feature = service.calculate(
                news, "600519", "贵州茅台", "白酒", AS_OF);

        assertThat(feature.includedNews()).singleElement()
                .extracting(NewsFlashResponse::title).isEqualTo("公司订单增长，行业景气回暖");
        assertThat(feature.latestPublishedAt()).isEqualTo(AS_OF.minusHours(2));
        assertThat(feature.sentiment()).isPositive();
    }

    private static void assertMissing(List<AiFactorValue> factors, String code) {
        AiFactorValue value = factor(factors, code);
        assertThat(value.missing).isEqualTo(1);
        assertThat(value.rawValue).isNull();
        assertThat(value.missingReason).isNotBlank();
    }

    private static AiFactorValue factor(List<AiFactorValue> factors, String code) {
        return factors.stream().filter(value -> code.equals(value.factorCode)).findFirst().orElseThrow();
    }

    private static AiSample sample() {
        AiSample sample = new AiSample();
        sample.id = 1L;
        sample.stockCode = "600519";
        sample.tradeDate = AS_OF.toLocalDate();
        sample.asOfTime = AS_OF;
        sample.universeVersion = "CN_A_SYSTEM_CORE/2026-07-14/R0001";
        return sample;
    }

    private static StockDetailResponse detail(List<KlinePointResponse> klines) {
        return new StockDetailResponse(null, FinanceSnapshotResponse.empty(), List.of(), klines, null, null);
    }

    private static KlinePointResponse kline(String date, String close) {
        BigDecimal price = new BigDecimal(close);
        return new KlinePointResponse(LocalDate.parse(date), price, price, price, price, 1000L,
                price.multiply(BigDecimal.valueOf(1000)));
    }

    private static AiResearchUniverseItem membership(String code, String name) {
        AiResearchUniverseItem item = new AiResearchUniverseItem();
        item.stockCode = "600519";
        item.industryCode = code;
        item.industryName = name;
        item.sourceFingerprint = code + "-fingerprint";
        return item;
    }

    private static NewsFlashResponse news(String title, LocalDateTime publishedAt) {
        return new NewsFlashResponse(publishedAt.toLocalTime().toString(), title, "EASTMONEY", null, publishedAt);
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-14T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }
}
