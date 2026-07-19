package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiIndustryDailyBar;
import com.maogou.stock.mapper.research.AiIndustryDailyBarMapper;
import com.maogou.stock.service.research.AiIndustryDailyBarService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AiIndustryDailyBarServiceImpl implements AiIndustryDailyBarService {

    private final AiIndustryDailyBarMapper mapper;

    public AiIndustryDailyBarServiceImpl(AiIndustryDailyBarMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public AiIndustryDailyBar store(BarCommand command) {
        validate(command);
        AiIndustryDailyBar current = mapper.selectCurrentForUpdate(
                normalize(command.industryCode()), normalize(command.classificationStandard()),
                command.tradeDate());
        if (current != null && Objects.equals(current.sourceFingerprint, command.sourceFingerprint())) {
            return current;
        }
        AiIndustryDailyBar candidate = bar(command, current);
        if (current != null && current.id != null && mapper.markSuperseded(current.id) != 1) {
            throw new IllegalStateException("行业日线并发修订失败：" + command.industryCode()
                    + " " + command.tradeDate());
        }
        mapper.insertImmutable(candidate);
        AiIndustryDailyBar persisted = mapper.selectCurrent(
                candidate.industryCode, candidate.classificationStandard, candidate.tradeDate);
        if (persisted == null || !Objects.equals(persisted.sourceFingerprint, command.sourceFingerprint())) {
            throw new IllegalStateException("行业日线写入后与输入证据不一致");
        }
        return persisted;
    }

    private static AiIndustryDailyBar bar(BarCommand command, AiIndustryDailyBar current) {
        AiIndustryDailyBar value = new AiIndustryDailyBar();
        value.industryCode = normalize(command.industryCode());
        value.industryName = command.industryName().trim();
        value.classificationStandard = normalize(command.classificationStandard());
        value.tradeDate = command.tradeDate();
        value.openPrice = command.openPrice();
        value.highPrice = command.highPrice();
        value.lowPrice = command.lowPrice();
        value.closePrice = command.closePrice();
        value.volume = command.volume();
        value.amount = command.amount();
        value.sourceName = normalize(command.sourceName());
        value.sourceRevision = command.sourceRevision().trim();
        value.revisionNo = current == null ? 1 : number(current.revisionNo) + 1;
        value.isCurrent = 1;
        value.supersedesBarId = current == null ? null : current.id;
        value.qualityStatus = normalize(command.qualityStatus());
        value.sourceRef = command.sourceRef().trim();
        value.evidenceJson = command.evidenceJson();
        value.sourceFingerprint = command.sourceFingerprint();
        value.observedAt = command.observedAt();
        value.createdAt = LocalDateTime.now();
        return value;
    }

    private static void validate(BarCommand command) {
        if (command == null || blank(command.industryCode()) || blank(command.industryName())
                || blank(command.classificationStandard()) || command.tradeDate() == null
                || command.openPrice() == null || command.highPrice() == null
                || command.lowPrice() == null || command.closePrice() == null
                || command.volume() == null || command.amount() == null
                || blank(command.sourceName()) || blank(command.sourceRevision())
                || invalidSource(command.sourceName())
                || !"READY".equalsIgnoreCase(command.qualityStatus())
                || blank(command.sourceRef()) || blank(command.evidenceJson())
                || blank(command.sourceFingerprint()) || command.observedAt() == null) {
            throw new IllegalArgumentException("行业日线缺少价格、来源或质量证据");
        }
        if (nonPositive(command.openPrice()) || nonPositive(command.highPrice())
                || nonPositive(command.lowPrice()) || nonPositive(command.closePrice())
                || command.volume().signum() < 0 || command.amount().signum() < 0
                || command.highPrice().compareTo(command.openPrice()) < 0
                || command.highPrice().compareTo(command.closePrice()) < 0
                || command.highPrice().compareTo(command.lowPrice()) < 0
                || command.lowPrice().compareTo(command.openPrice()) > 0
                || command.lowPrice().compareTo(command.closePrice()) > 0) {
            throw new IllegalArgumentException("行业日线 OHLC、成交量或成交额不一致");
        }
    }

    private static boolean nonPositive(BigDecimal value) {
        return value.signum() <= 0;
    }

    private static int number(Integer value) {
        return value == null ? 1 : value;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean invalidSource(String value) {
        String normalized = normalize(value);
        return normalized != null && (normalized.contains("MOCK")
                || normalized.contains("FALLBACK")
                || normalized.contains("UNAVAILABLE"));
    }
}
