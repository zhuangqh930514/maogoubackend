package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiSecurityDailyState;
import com.maogou.stock.mapper.research.AiSecurityDailyStateMapper;
import com.maogou.stock.service.research.AiSecurityDailyStateService;
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

class AiSecurityDailyStateServiceImplTest {

    @Test
    void preservesSameEvidenceAndCreatesRevisionWhenStateEvidenceChanges() {
        AiSecurityDailyStateMapper mapper = mock(AiSecurityDailyStateMapper.class);
        AtomicLong ids = new AtomicLong(10L);
        AiSecurityDailyState[] current = new AiSecurityDailyState[1];
        when(mapper.selectCurrentForUpdate(any(), any())).thenAnswer(ignored -> current[0]);
        when(mapper.selectCurrent(any(), any())).thenAnswer(ignored -> current[0]);
        when(mapper.markSuperseded(any())).thenAnswer(ignored -> {
            current[0].isCurrent = 0;
            return 1;
        });
        when(mapper.insertImmutable(any())).thenAnswer(invocation -> {
            AiSecurityDailyState candidate = invocation.getArgument(0);
            candidate.id = ids.incrementAndGet();
            current[0] = candidate;
            return 1;
        });
        AiSecurityDailyStateService service = new AiSecurityDailyStateServiceImpl(mapper);

        AiSecurityDailyState first = service.store(command("evidence-v1"));
        AiSecurityDailyState repeated = service.store(command("evidence-v1"));
        AiSecurityDailyState revised = service.store(command("evidence-v2"));

        assertThat(first.id).isEqualTo(repeated.id);
        assertThat(revised.id).isNotEqualTo(first.id);
        assertThat(revised.revisionNo).isEqualTo(2);
        assertThat(revised.supersedesStateId).isEqualTo(first.id);
        assertThat(revised.isCurrent).isEqualTo(1);
    }

    @Test
    void rejectsMalformedTradingStateBeforeAnyPersistence() {
        AiSecurityDailyStateMapper mapper = mock(AiSecurityDailyStateMapper.class);
        AiSecurityDailyStateService service = new AiSecurityDailyStateServiceImpl(mapper);
        AiSecurityDailyStateService.StateCommand invalid = new AiSecurityDailyStateService.StateCommand(
                "600519", LocalDate.of(2026, 7, 17), null, "", null, -1,
                "LISTED", "UNKNOWN", null, 0, new BigDecimal("-0.1"), null, null,
                null, null, null, null, "READY", null, "{}", "fingerprint", LocalDateTime.now());

        assertThatThrownBy(() -> service.store(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少");
    }

    private static AiSecurityDailyStateService.StateCommand command(String fingerprint) {
        return new AiSecurityDailyStateService.StateCommand(
                "600519", LocalDate.of(2026, 7, 17), 99L, "EASTMONEY/2026-07-17",
                LocalDate.of(2001, 8, 27), 5438, "LISTED", "UNKNOWN", null, 0,
                new BigDecimal("0.100000"), new BigDecimal("1500.00"), new BigDecimal("1200.00"),
                0, 0, 1, 1, "PARTIAL", "historical ST state unavailable", "{\"source\":\"KLINE\"}",
                fingerprint, LocalDateTime.of(2026, 7, 17, 15, 5));
    }
}
