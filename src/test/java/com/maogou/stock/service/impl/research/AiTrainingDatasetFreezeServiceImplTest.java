package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiHistoricalBackfillRun;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.domain.entity.research.AiTrainingReadinessSnapshot;
import com.maogou.stock.mapper.research.AiHistoricalBackfillRunMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.mapper.research.AiTrainingReadinessSnapshotMapper;
import com.maogou.stock.service.research.AiTrainingDatasetFreezeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTrainingDatasetFreezeServiceImplTest {

    private final AiHistoricalBackfillRunMapper runMapper = mock(AiHistoricalBackfillRunMapper.class);
    private final AiTrainingDatasetMapper datasetMapper = mock(AiTrainingDatasetMapper.class);
    private final AiTrainingDatasetItemMapper itemMapper = mock(AiTrainingDatasetItemMapper.class);
    private final AiTrainingReadinessSnapshotMapper readinessMapper = mock(AiTrainingReadinessSnapshotMapper.class);
    private AiTrainingDatasetFreezeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiTrainingDatasetFreezeServiceImpl(
                runMapper, datasetMapper, itemMapper, readinessMapper,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void rejectsDatasetWhenReadinessIsNotReady() {
        AiHistoricalBackfillRun run = run();
        AiTrainingDataset dataset = dataset("READY", 4);
        AiTrainingReadinessSnapshot readiness = new AiTrainingReadinessSnapshot();
        readiness.backfillRunId = run.id;
        readiness.status = "INSUFFICIENT_DATA";
        when(runMapper.selectByRunId(run.id)).thenReturn(run);
        when(datasetMapper.selectById(dataset.id)).thenReturn(dataset);
        when(readinessMapper.selectLatestByRunId(run.id)).thenReturn(readiness);

        AiTrainingDatasetFreezeService.FreezeResult result = service.freeze(request(run.id, dataset.id));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.blockingGaps()).contains("READINESS_NOT_READY:INSUFFICIENT_DATA");
        verify(itemMapper, never()).selectFreezeAuditPage(anyLong(), anyLong(), anyInt());
        verify(datasetMapper, never()).freezeImmutable(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsRowsWithNonFormalSourceEvidence() {
        AiHistoricalBackfillRun run = run();
        AiTrainingDataset dataset = dataset("READY", 4);
        when(runMapper.selectByRunId(run.id)).thenReturn(run);
        when(datasetMapper.selectById(dataset.id)).thenReturn(dataset);
        when(readinessMapper.selectLatestByRunId(run.id)).thenReturn(readyReadiness(run.id));
        AiTrainingDatasetItemMapper.DatasetFreezeAuditRow row = row(1);
        row.sourceBadCount = 1;
        when(itemMapper.selectFreezeAuditPage(eq(dataset.id), eq(0L), eq(500))).thenReturn(List.of(row));

        AiTrainingDatasetFreezeService.FreezeResult result = service.freeze(request(run.id, dataset.id));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.blockingGaps()).contains("SOURCE_QUALITY_NOT_FORMAL:102");
        verify(datasetMapper, never()).freezeImmutable(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsDatasetWhenT5RowsAreMissing() {
        AiHistoricalBackfillRun run = run();
        AiTrainingDataset dataset = dataset("READY", 3);
        when(runMapper.selectByRunId(run.id)).thenReturn(run);
        when(datasetMapper.selectById(dataset.id)).thenReturn(dataset);
        when(readinessMapper.selectLatestByRunId(run.id)).thenReturn(readyReadiness(run.id));
        when(itemMapper.selectFreezeAuditPage(eq(dataset.id), eq(0L), eq(500)))
                .thenReturn(List.of(row(1), row(2), row(3)));

        AiTrainingDatasetFreezeService.FreezeResult result = service.freeze(request(run.id, dataset.id));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.horizonCounts()).containsOnlyKeys(1, 2, 3);
        assertThat(result.blockingGaps()).contains("HORIZON_COVERAGE_MUST_INCLUDE_T1_T2_T3_T5")
                .contains("HORIZON_ROWS_MISSING:T+5");
        verify(datasetMapper, never()).freezeImmutable(any(), any(), any(), any(), any());
    }

    @Test
    void freezesCompleteDatasetWithAllRequiredHorizons() {
        AiHistoricalBackfillRun run = run();
        AiTrainingDataset dataset = dataset("READY", 4);
        AiTrainingDataset persisted = dataset("FROZEN", 4);
        persisted.freezeManifestJson = "{}";
        persisted.freezeChecksum = "persisted-checksum";
        persisted.frozenAt = LocalDateTime.of(2026, 8, 2, 16, 0);
        when(runMapper.selectByRunId(run.id)).thenReturn(run);
        when(datasetMapper.selectById(dataset.id)).thenReturn(dataset, persisted);
        when(readinessMapper.selectLatestByRunId(run.id)).thenReturn(readyReadiness(run.id));
        when(itemMapper.selectFreezeAuditPage(eq(dataset.id), eq(0L), eq(500)))
                .thenReturn(List.of(row(1), row(2), row(3), row(5)));
        when(datasetMapper.freezeImmutable(eq(dataset.id), any(), any(), any(), eq(99L))).thenReturn(1);

        AiTrainingDatasetFreezeService.FreezeResult result = service.freeze(
                new AiTrainingDatasetFreezeService.FreezeRequest(
                        run.id, dataset.id, 99L, LocalDateTime.of(2026, 8, 2, 16, 0), true));

        assertThat(result.status()).isEqualTo("FROZEN");
        assertThat(result.rowCount()).isEqualTo(4);
        assertThat(result.horizonCounts()).containsEntry(1, 1).containsEntry(2, 1)
                .containsEntry(3, 1).containsEntry(5, 1);
        assertThat(result.freezeChecksum()).hasSize(64);
        assertThat(result.manifestJson()).contains("MAOGOU_FROZEN_DATASET_MANIFEST_V1");
        verify(datasetMapper).freezeImmutable(eq(dataset.id), any(), any(), any(), eq(99L));
    }

    @Test
    void returnsExistingFrozenManifestIdempotently() {
        AiHistoricalBackfillRun run = run();
        AiTrainingDataset dataset = dataset("FROZEN", 4);
        dataset.freezeManifestJson = frozenManifest();
        dataset.freezeChecksum = sha256(dataset.freezeManifestJson);
        dataset.frozenAt = LocalDateTime.of(2026, 8, 2, 16, 0);
        when(runMapper.selectByRunId(run.id)).thenReturn(run);
        when(datasetMapper.selectById(dataset.id)).thenReturn(dataset);

        AiTrainingDatasetFreezeService.FreezeResult result = service.freeze(request(run.id, dataset.id));

        assertThat(result.status()).isEqualTo("FROZEN");
        assertThat(result.freezeChecksum()).isEqualTo(sha256(frozenManifest()));
        assertThat(result.horizonCounts()).containsEntry(5, 1);
        verify(readinessMapper, never()).selectLatestByRunId(any());
        verify(itemMapper, never()).selectFreezeAuditPage(anyLong(), anyLong(), anyInt());
        verify(datasetMapper, never()).freezeImmutable(any(), any(), any(), any(), any());
    }

    @Test
    void datasetOnlyRequestResolvesHistoricalRunFromDatasetLineage() {
        AiHistoricalBackfillRun run = run();
        AiTrainingDataset dataset = dataset("FROZEN", 4);
        dataset.freezeManifestJson = frozenManifest();
        dataset.freezeChecksum = sha256(frozenManifest());
        when(datasetMapper.selectById(dataset.id)).thenReturn(dataset);
        when(runMapper.selectByRunId(run.id)).thenReturn(run);

        AiTrainingDatasetFreezeService.FreezeResult result = service.freeze(
                new AiTrainingDatasetFreezeService.FreezeRequest(null, dataset.id, 99L,
                        LocalDateTime.of(2026, 8, 2, 16, 0), true));

        assertThat(result.status()).isEqualTo("FROZEN");
        assertThat(result.runId()).isEqualTo(run.id);
        verify(runMapper).selectByRunId(run.id);
    }

    @Test
    void rejectsFrozenDatasetWhenHistoricalRunLineageIsMissing() {
        AiTrainingDataset dataset = dataset("FROZEN", 4);
        dataset.backfillRunId = null;
        dataset.freezeManifestJson = frozenManifest();
        dataset.freezeChecksum = sha256(frozenManifest());
        when(datasetMapper.selectById(dataset.id)).thenReturn(dataset);

        AiTrainingDatasetFreezeService.FreezeResult result = service.freeze(
                new AiTrainingDatasetFreezeService.FreezeRequest(null, dataset.id, 99L,
                        LocalDateTime.of(2026, 8, 2, 16, 0), true));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.blockingGaps()).contains("HISTORICAL_RUN_REQUIRED");
        verify(itemMapper, never()).selectFreezeAuditPage(anyLong(), anyLong(), anyInt());
        verify(datasetMapper, never()).freezeImmutable(any(), any(), any(), any(), any());
    }

    private static AiTrainingDatasetFreezeService.FreezeRequest request(Long runId, Long datasetId) {
        return new AiTrainingDatasetFreezeService.FreezeRequest(
                runId, datasetId, 99L, LocalDateTime.of(2026, 8, 2, 16, 0), true);
    }

    private static AiHistoricalBackfillRun run() {
        AiHistoricalBackfillRun run = new AiHistoricalBackfillRun();
        run.id = 7L;
        run.runKey = "HISTORICAL:20260802";
        run.status = "SUCCESS";
        run.requestedEndDate = LocalDate.of(2026, 8, 1);
        run.effectiveSampleEndDate = LocalDate.of(2026, 8, 1);
        run.featureVersion = "FEATURE/1.0.0";
        run.labelVersion = "LABEL/1.1.0";
        run.calendarVersion = "CN_A/1.0.0";
        return run;
    }

    private static AiTrainingDataset dataset(String status, int rows) {
        AiTrainingDataset dataset = new AiTrainingDataset();
        dataset.id = 11L;
        dataset.backfillRunId = 7L;
        dataset.datasetKey = "MAOGOU_RANKER";
        dataset.versionNo = "20260802";
        dataset.featureVersion = "FEATURE/1.0.0";
        dataset.labelVersion = "LABEL/1.1.0";
        dataset.calendarVersion = "CN_A/1.0.0";
        dataset.asOfTime = LocalDateTime.of(2026, 8, 2, 16, 0);
        dataset.maxHorizonDays = 5;
        dataset.purgeTradingDays = 5;
        dataset.embargoTradingDays = 5;
        dataset.artifactUri = "file:///tmp/dataset.jsonl";
        dataset.artifactChecksum = "a".repeat(64);
        dataset.rowCount = rows;
        dataset.status = status;
        return dataset;
    }

    private static AiTrainingReadinessSnapshot readyReadiness(Long runId) {
        AiTrainingReadinessSnapshot readiness = new AiTrainingReadinessSnapshot();
        readiness.backfillRunId = runId;
        readiness.status = "READY";
        readiness.leakageViolationCount = 0;
        readiness.duplicateCount = 0;
        readiness.mockSourceCount = 0;
        readiness.staleSourceCount = 0;
        readiness.inferredFactCount = 0;
        return readiness;
    }

    private static AiTrainingDatasetItemMapper.DatasetFreezeAuditRow row(int horizon) {
        AiTrainingDatasetItemMapper.DatasetFreezeAuditRow row = new AiTrainingDatasetItemMapper.DatasetFreezeAuditRow();
        row.sampleId = 101L + horizon;
        row.sampleLabelId = 201L + horizon;
        row.sampleBackfillRunId = 7L;
        row.labelHorizon = horizon;
        row.sampleQualityStatus = "READY";
        row.sampleTradableStatus = "TRADABLE";
        row.labelStatus = "MATURED";
        row.executionStatus = "EXECUTED";
        row.fillStatus = "FILLED";
        row.labelIsCurrent = 1;
        row.sampleAsOfTime = LocalDateTime.of(2026, 7, 1, 15, 0);
        row.labelAvailableAt = LocalDateTime.of(2026, 7, 2, 16, 0);
        row.includedAt = LocalDateTime.of(2026, 8, 2, 16, 0);
        row.featureFingerprint = "f".repeat(64);
        row.labelFingerprint = "l".repeat(64);
        row.universeFingerprint = "u".repeat(64);
        row.tradingStateFingerprint = "t".repeat(64);
        row.sectorMembershipFingerprint = "s".repeat(64);
        return row;
    }

    private static String frozenManifest() {
        return "{\"format\":\"MAOGOU_FROZEN_DATASET_MANIFEST_V1\","
                + "\"horizonCounts\":{\"1\":1,\"2\":1,\"3\":1,\"5\":1}}";
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
