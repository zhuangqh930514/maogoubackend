package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiLabelCostEvidence;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.mapper.research.AiLabelCostEvidenceMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.service.research.AiSampleLabelService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSampleLabelServiceImplTest {

    @Test
    void duplicateSampleHorizonProducesOneImmutableMarketLabelAndOneCostEvidence() {
        AiSampleLabelMapper labelMapper = mock(AiSampleLabelMapper.class);
        AiLabelCostEvidenceMapper costMapper = mock(AiLabelCostEvidenceMapper.class);
        DefaultLabelPolicy policy = new DefaultLabelPolicy(new ObjectMapper().findAndRegisterModules());
        when(labelMapper.selectOne(any())).thenReturn(null);
        when(costMapper.selectOne(any())).thenReturn(null);
        AtomicLong ids = new AtomicLong(100);
        when(labelMapper.insert(any(AiSampleLabel.class))).thenAnswer(invocation -> {
            AiSampleLabel inserted = invocation.getArgument(0);
            inserted.id = ids.getAndIncrement();
            return 1;
        });
        AiSampleLabelService.SampleInput sample = sample();
        AiSampleLabelService service = new AiSampleLabelServiceImpl(labelMapper, costMapper, policy);

        List<AiSampleLabel> result = service.matureAndStore(batch(List.of(sample, sample)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).executionStatus).isEqualTo("EXECUTED");
        verify(labelMapper, times(1)).insert(any(AiSampleLabel.class));
        verify(costMapper, times(1)).insert(any(AiLabelCostEvidence.class));
    }

    @Test
    void existingLabelWithDifferentFingerprintFailsInsteadOfOverwritingHistory() {
        AiSampleLabelMapper labelMapper = mock(AiSampleLabelMapper.class);
        AiLabelCostEvidenceMapper costMapper = mock(AiLabelCostEvidenceMapper.class);
        DefaultLabelPolicy policy = new DefaultLabelPolicy(new ObjectMapper().findAndRegisterModules());
        AiSampleLabel existing = label("old-fingerprint");
        existing.id = 44L;
        when(labelMapper.selectOne(any())).thenReturn(existing);
        AiSampleLabelService service = new AiSampleLabelServiceImpl(labelMapper, costMapper, policy);

        assertThatThrownBy(() -> service.matureAndStore(batch(List.of(sample()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable fingerprint conflict");
    }

    private static AiSampleLabelService.LabelBatch batch(List<AiSampleLabelService.SampleInput> samples) {
        return new AiSampleLabelService.LabelBatch(
                samples,
                List.of(new AiSampleLabelService.TradingDay(
                        1L,
                        LocalDate.parse("2026-07-13"),
                        true,
                        LocalTime.of(15, 0),
                        "CN_A_CALENDAR/2026.1",
                        "calendar-fingerprint"
                )),
                "CN_A_CALENDAR/2026.1",
                DefaultLabelPolicy.VERSION,
                List.of(1),
                LocalDateTime.parse("2026-07-20T16:00:00")
        );
    }

    private static AiSampleLabelService.SampleInput sample() {
        return new AiSampleLabelService.SampleInput(
                88L,
                "600519",
                LocalDate.parse("2026-07-10"),
                "NORMAL",
                "sample-fingerprint",
                KlineSeriesSnapshot.create(
                        "600519",
                        "DAY",
                        "NONE",
                        "AKSHARE_HTTP",
                        LocalDateTime.parse("2026-07-13T15:10:00"),
                        LocalDateTime.parse("2026-07-13T15:11:00"),
                        List.of(
                                bar("2026-07-10", "10", "10", "9.8", "10.2"),
                                bar("2026-07-13", "10", "10.2", "9.9", "10.3")
                        )
                ),
                null,
                null
        );
    }

    private static AiSampleLabel label(String fingerprint) {
        AiSampleLabel label = new AiSampleLabel();
        label.sampleId = 88L;
        label.horizonTradingDays = 1;
        label.labelVersion = DefaultLabelPolicy.VERSION;
        label.inputFingerprint = fingerprint;
        label.labelStatus = "MATURED";
        label.executionStatus = "EXECUTED";
        return label;
    }

    private static KlinePointResponse bar(
            String date,
            String open,
            String close,
            String low,
            String high
    ) {
        return new KlinePointResponse(
                LocalDate.parse(date),
                new java.math.BigDecimal(open),
                new java.math.BigDecimal(close),
                new java.math.BigDecimal(low),
                new java.math.BigDecimal(high),
                1000L,
                java.math.BigDecimal.ZERO
        );
    }
}
