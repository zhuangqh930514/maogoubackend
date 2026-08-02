package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.maogou.stock.domain.entity.research.AiHistoricalBackfillRun;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.domain.entity.research.AiTrainingReadinessSnapshot;
import com.maogou.stock.mapper.research.AiHistoricalBackfillRunMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.mapper.research.AiTrainingReadinessSnapshotMapper;
import com.maogou.stock.service.research.AiTrainingDatasetFreezeService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Implements the FROZEN dataset quality gate without holding a long transaction. */
@Service
public class AiTrainingDatasetFreezeServiceImpl implements AiTrainingDatasetFreezeService {

    private static final int PAGE_SIZE = 500;
    private static final Set<Integer> REQUIRED_HORIZONS = Set.of(1, 2, 3, 5);
    private static final int MAX_REPORTED_GAPS = 100;

    private final AiHistoricalBackfillRunMapper runMapper;
    private final AiTrainingDatasetMapper datasetMapper;
    private final AiTrainingDatasetItemMapper itemMapper;
    private final AiTrainingReadinessSnapshotMapper readinessMapper;
    private final ObjectMapper objectMapper;

    public AiTrainingDatasetFreezeServiceImpl(
            AiHistoricalBackfillRunMapper runMapper,
            AiTrainingDatasetMapper datasetMapper,
            AiTrainingDatasetItemMapper itemMapper,
            AiTrainingReadinessSnapshotMapper readinessMapper,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.datasetMapper = datasetMapper;
        this.itemMapper = itemMapper;
        this.readinessMapper = readinessMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public FreezeResult freeze(FreezeRequest request) {
        validateRequest(request);
        LocalDateTime now = request.requestedAt() == null ? LocalDateTime.now() : request.requestedAt();
        AiHistoricalBackfillRun run = request.runId() == null ? null : runMapper.selectByRunId(request.runId());
        if (request.runId() != null && run == null) {
            throw new IllegalArgumentException("历史补齐运行不存在：" + request.runId());
        }
        AiTrainingDataset dataset = request.datasetId() == null
                ? datasetMapper.selectLatestReadyForFreeze(
                run.id, run.featureVersion, run.labelVersion, run.calendarVersion,
                run.effectiveSampleEndDate == null ? run.requestedEndDate : run.effectiveSampleEndDate,
                now)
                : datasetMapper.selectById(request.datasetId());
        if (run == null && dataset != null && dataset.backfillRunId != null) {
            run = runMapper.selectByRunId(dataset.backfillRunId);
        }
        List<String> gaps = new ArrayList<>();
        if (run == null) {
            gaps.add("HISTORICAL_RUN_REQUIRED");
        }
        if (dataset == null) {
            gaps.add("READY_DATASET_MISSING");
        } else if ("FROZEN".equals(dataset.status)) {
            gaps.addAll(frozenManifestGaps(dataset));
            if (run == null) {
                gaps.add("HISTORICAL_RUN_REQUIRED");
            } else if (!Objects.equals(dataset.backfillRunId, run.id)) {
                gaps.add("DATASET_BACKFILL_RUN_MISMATCH:" + dataset.backfillRunId + "/" + run.id);
            }
            if (!gaps.isEmpty()) {
                return rejected(run, dataset, gaps, now);
            }
            return frozen(run, dataset, now);
        } else {
            if (run != null) {
                AiTrainingReadinessSnapshot readiness = readinessMapper.selectLatestByRunId(run.id);
                gaps.addAll(readinessGaps(readiness));
                if (readiness == null || !"READY".equals(readiness.status)) {
                    gaps.add("READINESS_NOT_READY:" + (readiness == null ? "MISSING" : readiness.status));
                }
            }
            auditDataset(run, dataset, gaps);
        }
        if (!gaps.isEmpty()) {
            return rejected(run, dataset, gaps, now);
        }

        AuditSummary audit = auditRows(run, dataset);
        gaps.addAll(audit.gaps());
        if (request.requireAllHorizons() && !REQUIRED_HORIZONS.equals(audit.horizonCounts().keySet())) {
            gaps.add("HORIZON_COVERAGE_MUST_INCLUDE_T1_T2_T3_T5");
        }
        Set<Integer> requiredHorizons = request.requireAllHorizons()
                ? REQUIRED_HORIZONS : Set.of(value(dataset.maxHorizonDays));
        for (Integer horizon : requiredHorizons) {
            if (audit.horizonCounts().getOrDefault(horizon, 0) <= 0) {
                gaps.add("HORIZON_ROWS_MISSING:T+" + horizon);
            }
        }
        if (audit.rowCount() != value(dataset.rowCount)) {
            gaps.add("DATASET_ROW_COUNT_MISMATCH:" + audit.rowCount() + "/" + value(dataset.rowCount));
        }
        if (!gaps.isEmpty()) {
            return rejected(run, dataset, gaps, now, audit);
        }

        String manifestJson = manifest(run, dataset, audit, now);
        String checksum = sha256(manifestJson);
        int updated = datasetMapper.freezeImmutable(
                dataset.id, manifestJson, checksum, now, request.operatorUserId());
        AiTrainingDataset persisted = datasetMapper.selectById(dataset.id);
        if (updated != 1 && (persisted == null || !"FROZEN".equals(persisted.status)
                || !Objects.equals(checksum, persisted.freezeChecksum))) {
            throw new IllegalStateException("数据集冻结状态被并发修改或冻结写入失败：" + dataset.id);
        }
        return new FreezeResult(run == null ? null : run.id, dataset.id, dataset.datasetKey, dataset.versionNo,
                "FROZEN", audit.rowCount(), audit.horizonCounts(), List.of(), manifestJson,
                checksum, persisted == null ? now : persisted.frozenAt);
    }

    private FreezeResult frozen(AiHistoricalBackfillRun run, AiTrainingDataset dataset, LocalDateTime now) {
        Map<Integer, Integer> horizonCounts = horizonCounts(dataset.freezeManifestJson);
        return new FreezeResult(run.id, dataset.id, dataset.datasetKey, dataset.versionNo,
                "FROZEN", value(dataset.rowCount), horizonCounts, List.of(), dataset.freezeManifestJson,
                dataset.freezeChecksum, dataset.frozenAt == null ? now : dataset.frozenAt);
    }

    private List<String> frozenManifestGaps(AiTrainingDataset dataset) {
        List<String> gaps = new ArrayList<>();
        if (dataset.freezeManifestJson == null || dataset.freezeManifestJson.isBlank()
                || dataset.freezeChecksum == null || dataset.freezeChecksum.isBlank()) {
            gaps.add("FROZEN_MANIFEST_MISSING");
            return gaps;
        }
        if (!Objects.equals(dataset.freezeChecksum, sha256(dataset.freezeManifestJson))) {
            gaps.add("FROZEN_MANIFEST_CHECKSUM_MISMATCH");
        }
        try {
            var node = objectMapper.readTree(dataset.freezeManifestJson);
            if (node == null || !node.isObject()
                    || !"MAOGOU_FROZEN_DATASET_MANIFEST_V1".equals(node.path("format").asText())
                    || !node.path("horizonCounts").isObject()) {
                gaps.add("FROZEN_MANIFEST_INVALID");
            }
        } catch (JsonProcessingException exception) {
            gaps.add("FROZEN_MANIFEST_INVALID");
        }
        return gaps;
    }

    private Map<Integer, Integer> horizonCounts(String manifestJson) {
        try {
            var node = objectMapper.readTree(manifestJson);
            Map<Integer, Integer> counts = new TreeMap<>();
            node.path("horizonCounts").fields().forEachRemaining(entry -> {
                try {
                    counts.put(Integer.parseInt(entry.getKey()), entry.getValue().asInt());
                } catch (NumberFormatException ignored) {
                    // Ignore malformed legacy keys; the immutable checksum remains authoritative.
                }
            });
            return counts;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("已冻结数据集 manifest 无法解析：" + datasetManifestLabel(manifestJson), exception);
        }
    }

    private static String datasetManifestLabel(String manifestJson) {
        return manifestJson == null || manifestJson.isBlank() ? "空" : "内容无效";
    }

    private void auditDataset(AiHistoricalBackfillRun run, AiTrainingDataset dataset, List<String> gaps) {
        if (!"READY".equals(dataset.status)) {
            gaps.add("DATASET_STATUS_NOT_READY:" + dataset.status);
        }
        if (run != null && (!Objects.equals(dataset.featureVersion, run.featureVersion)
                || !Objects.equals(dataset.labelVersion, run.labelVersion)
                || !Objects.equals(dataset.calendarVersion, run.calendarVersion))) {
            gaps.add("DATASET_VERSION_MISMATCH");
        }
        if (run != null && !Objects.equals(dataset.backfillRunId, run.id)) {
            gaps.add("DATASET_BACKFILL_RUN_MISMATCH:" + dataset.backfillRunId + "/" + run.id);
        }
        if (dataset.artifactUri == null || dataset.artifactUri.isBlank()
                || dataset.artifactChecksum == null || dataset.artifactChecksum.isBlank()) {
            gaps.add("DATASET_ARTIFACT_CHECKSUM_MISSING");
        }
        if (dataset.purgeTradingDays == null || dataset.purgeTradingDays < 5
                || dataset.embargoTradingDays == null || dataset.embargoTradingDays < 5) {
            gaps.add("PURGE_OR_EMBARGO_BELOW_5");
        }
    }

    private AuditSummary auditRows(AiHistoricalBackfillRun run, AiTrainingDataset dataset) {
        long offset = 0;
        int rowCount = 0;
        Map<Integer, Integer> horizons = new TreeMap<>();
        List<String> gaps = new ArrayList<>();
        MessageDigest digest = sha256Digest();
        while (true) {
            List<AiTrainingDatasetItemMapper.DatasetFreezeAuditRow> page = itemMapper.selectFreezeAuditPage(
                    dataset.id, offset, PAGE_SIZE);
            if (page == null || page.isEmpty()) {
                break;
            }
            for (AiTrainingDatasetItemMapper.DatasetFreezeAuditRow row : page) {
                rowCount++;
                int horizon = value(row.labelHorizon);
                if (REQUIRED_HORIZONS.contains(horizon)) {
                    horizons.merge(horizon, 1, Integer::sum);
                } else {
                    addGap(gaps, "INVALID_HORIZON:" + horizon + ":" + row.sampleLabelId);
                }
                if (run != null && (row.sampleBackfillRunId == null
                        || !Objects.equals(row.sampleBackfillRunId, run.id))) {
                    addGap(gaps, "ROW_BACKFILL_RUN_MISMATCH:" + row.sampleId);
                }
                if (!"READY".equals(row.sampleQualityStatus)
                        || !"TRADABLE".equals(row.sampleTradableStatus)) {
                    addGap(gaps, "SAMPLE_NOT_EXECUTABLE:" + row.sampleId);
                }
                if (!"MATURED".equals(row.labelStatus) || !"EXECUTED".equals(row.executionStatus)
                        || !"FILLED".equals(row.fillStatus) || !Integer.valueOf(1).equals(row.labelIsCurrent)) {
                    addGap(gaps, "LABEL_NOT_MATURE_EXECUTABLE:" + row.sampleLabelId);
                }
                if (value(row.sourceBadCount) > 0) {
                    addGap(gaps, "SOURCE_QUALITY_NOT_FORMAL:" + row.sampleId);
                }
                if (row.sampleAsOfTime == null || row.labelAvailableAt == null || row.includedAt == null
                        || row.sampleAsOfTime.isAfter(row.includedAt)
                        || row.labelAvailableAt.isAfter(row.includedAt)) {
                    addGap(gaps, "POINT_IN_TIME_VIOLATION:" + row.sampleId);
                }
                if (blank(row.featureFingerprint) || blank(row.labelFingerprint)
                        || blank(row.universeFingerprint) || blank(row.tradingStateFingerprint)
                        || blank(row.sectorMembershipFingerprint)) {
                    addGap(gaps, "SOURCE_FINGERPRINT_MISSING:" + row.sampleId);
                }
                updateDigest(digest, row);
            }
            offset += page.size();
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        return new AuditSummary(rowCount, horizons, gaps,
                HexFormatHolder.hex(digest.digest()));
    }

    private FreezeResult rejected(
            AiHistoricalBackfillRun run, AiTrainingDataset dataset, List<String> gaps, LocalDateTime now) {
        return rejected(run, dataset, gaps, now, new AuditSummary(0, Map.of(), List.of(), null));
    }

    private FreezeResult rejected(
            AiHistoricalBackfillRun run,
            AiTrainingDataset dataset,
            List<String> gaps,
            LocalDateTime now,
            AuditSummary audit
    ) {
        return new FreezeResult(run == null ? null : run.id, dataset == null ? null : dataset.id,
                dataset == null ? null : dataset.datasetKey,
                dataset == null ? null : dataset.versionNo,
                "REJECTED", audit.rowCount(), audit.horizonCounts(), distinctGaps(gaps),
                null, null, now);
    }

    private List<String> readinessGaps(AiTrainingReadinessSnapshot readiness) {
        List<String> gaps = new ArrayList<>();
        if (readiness == null) {
            return gaps;
        }
        if (value(readiness.leakageViolationCount) > 0) gaps.add("READINESS_PIT_VIOLATIONS");
        if (value(readiness.duplicateCount) > 0) gaps.add("READINESS_DUPLICATES");
        if (value(readiness.mockSourceCount) > 0) gaps.add("READINESS_MOCK_ROWS");
        if (value(readiness.staleSourceCount) > 0) gaps.add("READINESS_STALE_ROWS");
        if (value(readiness.inferredFactCount) > 0) gaps.add("READINESS_INFERRED_ROWS");
        return gaps;
    }

    private String manifest(AiHistoricalBackfillRun run, AiTrainingDataset dataset,
                            AuditSummary audit, LocalDateTime now) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", "MAOGOU_FROZEN_DATASET_MANIFEST_V1");
        value.put("frozenAt", now);
        value.put("runKey", run.runKey);
        value.put("datasetKey", dataset.datasetKey);
        value.put("versionNo", dataset.versionNo);
        value.put("featureVersion", dataset.featureVersion);
        value.put("labelVersion", dataset.labelVersion);
        value.put("calendarVersion", dataset.calendarVersion);
        value.put("rowCount", audit.rowCount());
        value.put("horizonCounts", audit.horizonCounts());
        value.put("purgeTradingDays", dataset.purgeTradingDays);
        value.put("embargoTradingDays", dataset.embargoTradingDays);
        value.put("artifactChecksum", dataset.artifactChecksum);
        value.put("itemLineageChecksum", audit.itemLineageChecksum());
        try {
            return objectMapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成冻结数据集 manifest", exception);
        }
    }

    private static void updateDigest(MessageDigest digest,
                                     AiTrainingDatasetItemMapper.DatasetFreezeAuditRow row) {
        String value = String.join("|", String.valueOf(row.sampleId), String.valueOf(row.sampleLabelId),
                String.valueOf(row.splitType), String.valueOf(row.sequenceNo),
                String.valueOf(row.sampleAsOfTime), String.valueOf(row.labelAvailableAt),
                String.valueOf(row.featureFingerprint), String.valueOf(row.labelFingerprint),
                String.valueOf(row.universeFingerprint), String.valueOf(row.tradingStateFingerprint),
                String.valueOf(row.sectorMembershipFingerprint), "\n");
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String sha256(String value) {
        MessageDigest digest = sha256Digest();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return HexFormatHolder.hex(digest.digest());
    }

    private static void validateRequest(FreezeRequest request) {
        if (request == null || (request.runId() == null && request.datasetId() == null)
                || request.runId() != null && request.runId() <= 0
                || request.datasetId() != null && request.datasetId() <= 0) {
            throw new IllegalArgumentException("数据集冻结缺少历史运行或数据集 ID");
        }
    }

    private static List<String> distinctGaps(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct()
                .limit(MAX_REPORTED_GAPS).toList();
    }

    private static void addGap(List<String> gaps, String gap) {
        if (gaps.size() < MAX_REPORTED_GAPS) gaps.add(gap);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private record AuditSummary(
            int rowCount,
            Map<Integer, Integer> horizonCounts,
            List<String> gaps,
            String itemLineageChecksum
    ) {
        private AuditSummary {
            horizonCounts = horizonCounts == null ? Map.of() : Map.copyOf(horizonCounts);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    private static final class HexFormatHolder {
        private static String hex(byte[] value) {
            return java.util.HexFormat.of().formatHex(value);
        }
    }
}
