package com.maogou.stock.service.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiSourceObservation;
import com.maogou.stock.infrastructure.market.ResearchMarketDataClient;
import com.maogou.stock.infrastructure.market.ResearchMarketDataProvider;
import com.maogou.stock.infrastructure.market.ResearchSourceResult;
import com.maogou.stock.infrastructure.market.ResearchSourceStatus;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiSourceObservationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class IndustryMembershipService {

    private final AiResearchUniverseItemMapper itemMapper;
    private final AiSourceObservationMapper sourceObservationMapper;
    private final ObjectMapper objectMapper;
    private final ResearchMarketDataClient marketDataClient;
    private final Clock clock;

    public IndustryMembershipService(
            AiResearchUniverseItemMapper itemMapper,
            ResearchMarketDataClient marketDataClient
    ) {
        this(itemMapper, null, null, marketDataClient, Clock.systemDefaultZone());
    }

    @Autowired
    public IndustryMembershipService(
            AiResearchUniverseItemMapper itemMapper,
            AiSourceObservationMapper sourceObservationMapper,
            ObjectMapper objectMapper,
            ResearchMarketDataClient marketDataClient
    ) {
        this(itemMapper, sourceObservationMapper, objectMapper, marketDataClient, Clock.systemDefaultZone());
    }

    public IndustryMembershipService(
            AiResearchUniverseItemMapper itemMapper,
            ResearchMarketDataClient marketDataClient,
            Clock clock
    ) {
        this(itemMapper, null, null, marketDataClient, clock);
    }

    private IndustryMembershipService(
            AiResearchUniverseItemMapper itemMapper,
            AiSourceObservationMapper sourceObservationMapper,
            ObjectMapper objectMapper,
            ResearchMarketDataClient marketDataClient,
            Clock clock
    ) {
        this.itemMapper = itemMapper;
        this.sourceObservationMapper = sourceObservationMapper;
        this.objectMapper = objectMapper;
        this.marketDataClient = marketDataClient;
        this.clock = clock;
    }

    public Membership resolve(String stockCode, LocalDateTime asOfTime) {
        if (stockCode == null || stockCode.isBlank() || asOfTime == null) {
            return Membership.unavailable(stockCode, "缺少股票代码或研究截止时间");
        }
        LocalDate asOfDate = asOfTime.toLocalDate();
        AiResearchUniverseItem stored = itemMapper.selectIndustryMembershipAt(stockCode, asOfDate);
        if (stored != null && stored.industryCode != null && !stored.industryCode.isBlank()) {
            return new Membership(
                    stockCode, stored.industryCode, stored.industryName, stored.effectiveFrom, null,
                    ResearchSourceStatus.REALTIME, "SNAPSHOT", stored.sourceFingerprint, null);
        }

        Membership observed = observedMembership(stockCode, asOfTime);
        if (observed != null) {
            return observed;
        }

        if (asOfDate.isBefore(LocalDate.now(clock))) {
            return Membership.unavailable(stockCode, "历史时点缺少当时有效的行业归属，禁止使用当前行业回填");
        }
        if (marketDataClient == null) {
            return Membership.unavailable(stockCode, "行业数据源未接入");
        }
        ResearchSourceResult<ResearchMarketDataProvider.IndustryMembershipData> result =
                marketDataClient.fetchIndustryAt(stockCode, asOfTime);
        if (!result.formalReady()) {
            return new Membership(stockCode, null, null, null, null,
                    result.sourceStatus(), result.providerCode(), result.responseFingerprint(), result.message());
        }
        ResearchMarketDataProvider.IndustryMembershipData data = result.data();
        return new Membership(
                stockCode, data.industryCode(), data.industryName(), data.effectiveFrom(), data.effectiveTo(),
                result.sourceStatus(), result.providerCode(), data.sourceFingerprint(), null);
    }

    private Membership observedMembership(String stockCode, LocalDateTime asOfTime) {
        if (sourceObservationMapper == null || objectMapper == null) {
            return null;
        }
        AiSourceObservation observation = sourceObservationMapper.selectIndustryMembershipAt(stockCode, asOfTime);
        if (observation == null || observation.payloadJson == null || observation.payloadJson.isBlank()) {
            return null;
        }
        try {
            Membership membership = objectMapper.readValue(observation.payloadJson, Membership.class);
            if (membership.effectiveFrom() != null && membership.effectiveFrom().isAfter(asOfTime.toLocalDate())) {
                return null;
            }
            if (membership.effectiveTo() != null && membership.effectiveTo().isBefore(asOfTime.toLocalDate())) {
                return null;
            }
            return membership;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("历史行业归属证据无法解析：" + stockCode, exception);
        }
    }

    public record Membership(
            String stockCode,
            String industryCode,
            String industryName,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            ResearchSourceStatus sourceStatus,
            String providerCode,
            String sourceFingerprint,
            String missingReason
    ) {
        public boolean available() {
            return sourceStatus == ResearchSourceStatus.REALTIME
                    && industryCode != null && !industryCode.isBlank();
        }

        static Membership unavailable(String stockCode, String reason) {
            return new Membership(stockCode, null, null, null, null,
                    ResearchSourceStatus.UNAVAILABLE, "UNAVAILABLE", null, reason);
        }
    }
}
