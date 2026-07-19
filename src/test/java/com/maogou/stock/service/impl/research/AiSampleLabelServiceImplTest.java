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
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSampleLabelServiceImplTest {

    @Test
    void duplicateSampleHorizonProducesOneImmutableMarketLabelAndOneCostEvidence() {
        AiSampleLabelMapper labelMapper = mock(AiSampleLabelMapper.class);
        AiLabelCostEvidenceMapper costMapper = mock(AiLabelCostEvidenceMapper.class);
        DefaultLabelPolicy policy = new DefaultLabelPolicy(new ObjectMapper().findAndRegisterModules());
        List<AiSampleLabel> labels = new ArrayList<>();
        List<AiLabelCostEvidence> costs = new ArrayList<>();
        when(labelMapper.selectCurrentForSamplesAndVersionForUpdate(any(), any()))
                .thenAnswer(ignored -> labels.stream().filter(value -> value.isCurrent == 1).toList());
        when(labelMapper.selectForSamplesAndVersion(any(), any()))
                .thenAnswer(ignored -> labels.stream().filter(value -> value.isCurrent == 1).toList());
        when(costMapper.selectForLabelsAndVersion(any(), any())).thenAnswer(ignored -> List.copyOf(costs));
        AtomicLong ids = new AtomicLong(100);
        when(labelMapper.insertBatchImmutable(any())).thenAnswer(invocation -> {
            List<AiSampleLabel> inserted = invocation.getArgument(0);
            inserted.forEach(value -> value.id = ids.getAndIncrement());
            labels.addAll(inserted);
            return inserted.size();
        });
        when(costMapper.insertBatchImmutable(any())).thenAnswer(invocation -> {
            List<AiLabelCostEvidence> inserted = invocation.getArgument(0);
            inserted.forEach(value -> value.id = ids.getAndIncrement());
            costs.addAll(inserted);
            return inserted.size();
        });
        AiSampleLabelService.SampleInput sample = sample();
        AiSampleLabelService service = new AiSampleLabelServiceImpl(labelMapper, costMapper, policy);

        List<AiSampleLabel> result = service.matureAndStore(batch(List.of(sample, sample)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).executionStatus).isEqualTo("EXECUTED");
        verify(labelMapper).insertBatchImmutable(any());
        verify(costMapper).insertBatchImmutable(any());
    }

    @Test
    void existingLabelWithDifferentFingerprintCreatesNextRevisionWithoutOverwritingHistory() {
        AiSampleLabelMapper labelMapper = mock(AiSampleLabelMapper.class);
        AiLabelCostEvidenceMapper costMapper = mock(AiLabelCostEvidenceMapper.class);
        DefaultLabelPolicy policy = new DefaultLabelPolicy(new ObjectMapper().findAndRegisterModules());
        AiSampleLabel existing = label("old-fingerprint");
        existing.id = 44L;
        existing.revisionNo = 1;
        existing.isCurrent = 1;
        List<AiSampleLabel> labels = new ArrayList<>(List.of(existing));
        List<AiLabelCostEvidence> costs = new ArrayList<>();
        when(labelMapper.selectCurrentForSamplesAndVersionForUpdate(any(), any()))
                .thenAnswer(ignored -> labels.stream().filter(value -> value.isCurrent == 1).toList());
        when(labelMapper.selectForSamplesAndVersion(any(), any()))
                .thenAnswer(ignored -> labels.stream().filter(value -> value.isCurrent == 1).toList());
        when(labelMapper.markSuperseded(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            labels.stream().filter(value -> ids.contains(value.id)).forEach(value -> value.isCurrent = 0);
            return ids.size();
        });
        when(labelMapper.insertBatchImmutable(any())).thenAnswer(invocation -> {
            List<AiSampleLabel> inserted = invocation.getArgument(0);
            inserted.forEach(value -> value.id = 100L + labels.size());
            labels.addAll(inserted);
            return inserted.size();
        });
        when(costMapper.selectForLabelsAndVersion(any(), any())).thenAnswer(ignored -> List.copyOf(costs));
        when(costMapper.insertBatchImmutable(any())).thenAnswer(invocation -> {
            List<AiLabelCostEvidence> inserted = invocation.getArgument(0);
            costs.addAll(inserted);
            return inserted.size();
        });
        AiSampleLabelService service = new AiSampleLabelServiceImpl(labelMapper, costMapper, policy);

        List<AiSampleLabel> result = service.matureAndStore(batch(List.of(sample())));

        assertThat(result).singleElement().satisfies(revision -> {
            assertThat(revision.id).isNotEqualTo(existing.id);
            assertThat(revision.revisionNo).isEqualTo(2);
            assertThat(revision.isCurrent).isEqualTo(1);
            assertThat(revision.supersedesLabelId).isEqualTo(existing.id);
            assertThat(revision.revisionReason).isEqualTo("SOURCE_EVIDENCE_CHANGED");
        });
        assertThat(existing.isCurrent).isZero();
        assertThat(labels).hasSize(2);
        verify(labelMapper).markSuperseded(List.of(existing.id));
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
        label.revisionNo = 1;
        label.isCurrent = 1;
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
