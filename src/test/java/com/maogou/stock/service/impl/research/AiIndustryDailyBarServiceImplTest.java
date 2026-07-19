package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiIndustryDailyBar;
import com.maogou.stock.mapper.research.AiIndustryDailyBarMapper;
import com.maogou.stock.service.research.AiIndustryDailyBarService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiIndustryDailyBarServiceImplTest {

    @Test
    void reusesSameEvidenceAndCreatesRevisionForVendorCorrection() {
        AiIndustryDailyBarMapper mapper = mock(AiIndustryDailyBarMapper.class);
        AiIndustryDailyBar[] current = new AiIndustryDailyBar[1];
        AtomicLong ids = new AtomicLong(10);
        when(mapper.selectCurrentForUpdate(any(), any(), any())).thenAnswer(ignored -> current[0]);
        when(mapper.selectCurrent(any(), any(), any())).thenAnswer(ignored -> current[0]);
        when(mapper.markSuperseded(any())).thenAnswer(ignored -> {
            current[0].isCurrent = 0;
            return 1;
        });
        when(mapper.insertImmutable(any())).thenAnswer(invocation -> {
            AiIndustryDailyBar value = invocation.getArgument(0);
            value.id = ids.incrementAndGet();
            current[0] = value;
            return 1;
        });
        AiIndustryDailyBarService service = new AiIndustryDailyBarServiceImpl(mapper);

        AiIndustryDailyBar first = service.store(command("fingerprint-v1", "101"));
        AiIndustryDailyBar repeated = service.store(command("fingerprint-v1", "101"));
        AiIndustryDailyBar revised = service.store(command("fingerprint-v2", "102"));

        assertThat(repeated.id).isEqualTo(first.id);
        assertThat(revised.id).isNotEqualTo(first.id);
        assertThat(revised.revisionNo).isEqualTo(2);
        assertThat(revised.supersedesBarId).isEqualTo(first.id);
    }

    @Test
    void rejectsImpossibleOhlcBeforePersistence() {
        AiIndustryDailyBarService service = new AiIndustryDailyBarServiceImpl(
                mock(AiIndustryDailyBarMapper.class));
        AiIndustryDailyBarService.BarCommand invalid = new AiIndustryDailyBarService.BarCommand(
                "801120.SI", "食品饮料", "SW2021", LocalDate.of(2026, 7, 17),
                new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("98"),
                new BigDecimal("101"), BigDecimal.ZERO, BigDecimal.ZERO,
                "TUSHARE", "v1", "READY", "TUSHARE/sw_daily", "{}",
                "fingerprint", LocalDateTime.of(2026, 7, 19, 9, 0));

        assertThatThrownBy(() -> service.store(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OHLC");
    }

    @Test
    void rejectsMockOrFallbackSourcesBeforePersistence() {
        AiIndustryDailyBarService service = new AiIndustryDailyBarServiceImpl(
                mock(AiIndustryDailyBarMapper.class));
        AiIndustryDailyBarService.BarCommand invalid = new AiIndustryDailyBarService.BarCommand(
                "801120.SI", "食品饮料", "SW2021", LocalDate.of(2026, 7, 17),
                new BigDecimal("100"), new BigDecimal("103"), new BigDecimal("98"),
                new BigDecimal("101"), BigDecimal.ZERO, BigDecimal.ZERO,
                "ARCHIVE_MOCK", "v1", "READY", "fixture", "{}",
                "fingerprint", LocalDateTime.of(2026, 7, 19, 9, 0));

        assertThatThrownBy(() -> service.store(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("来源或质量证据");
    }

    private static AiIndustryDailyBarService.BarCommand command(String fingerprint, String close) {
        return new AiIndustryDailyBarService.BarCommand(
                "801120.SI", "食品饮料", "SW2021", LocalDate.of(2026, 7, 17),
                new BigDecimal("100"), new BigDecimal("103"), new BigDecimal("98"),
                new BigDecimal(close), new BigDecimal("1000"), new BigDecimal("2000"),
                "TUSHARE", "20260719", "READY", "TUSHARE/sw_daily", "{}",
                fingerprint, LocalDateTime.of(2026, 7, 19, 9, 0));
    }
}
