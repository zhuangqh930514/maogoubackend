package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiLabelCostEvidence;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.mapper.research.AiLabelCostEvidenceMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.service.research.AiSampleLabelService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AiSampleLabelServiceImpl implements AiSampleLabelService {

    private static final int WRITE_BATCH_SIZE = 200;

    private final AiSampleLabelMapper labelMapper;
    private final AiLabelCostEvidenceMapper costEvidenceMapper;
    private final DefaultLabelPolicy labelPolicy;

    public AiSampleLabelServiceImpl(
            AiSampleLabelMapper labelMapper,
            AiLabelCostEvidenceMapper costEvidenceMapper,
            DefaultLabelPolicy labelPolicy
    ) {
        this.labelMapper = labelMapper;
        this.costEvidenceMapper = costEvidenceMapper;
        this.labelPolicy = labelPolicy;
    }

    @Override
    @Transactional
    public List<AiSampleLabel> matureAndStore(LabelBatch batch) {
        validate(batch);
        List<DefaultLabelPolicy.BuildResult> built = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        for (SampleInput sample : batch.samples()) {
            for (Integer horizon : batch.horizons()) {
                String key = sample.sampleId() + "|" + horizon + "|" + batch.labelVersion();
                if (!processed.add(key)) {
                    continue;
                }
                DefaultLabelPolicy.BuildResult result = labelPolicy.build(
                        sample,
                        horizon,
                        batch.calendars(),
                        batch.calendarVersion(),
                        batch.labelVersion(),
                        batch.verifiedAt()
                );
                if (result == null) {
                    continue;
                }
                built.add(result);
            }
        }
        if (built.isEmpty()) {
            return List.of();
        }

        List<Long> sampleIds = built.stream().map(result -> result.label().sampleId)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, AiSampleLabel> existingByKey = labelIndex(
                labelMapper.selectCurrentForSamplesAndVersionForUpdate(sampleIds, batch.labelVersion()));
        List<AiSampleLabel> pending = new ArrayList<>();
        List<Long> supersededIds = new ArrayList<>();
        for (DefaultLabelPolicy.BuildResult result : built) {
            AiSampleLabel candidate = result.label();
            AiSampleLabel existing = existingByKey.get(labelKey(candidate));
            if (existing == null) {
                candidate.revisionNo = 1;
                candidate.isCurrent = 1;
                candidate.createdAt = candidate.createdAt == null ? LocalDateTime.now() : candidate.createdAt;
                pending.add(candidate);
            } else if (!Objects.equals(existing.inputFingerprint, candidate.inputFingerprint)) {
                candidate.revisionNo = value(existing.revisionNo, 1) + 1;
                candidate.isCurrent = 1;
                candidate.supersedesLabelId = existing.id;
                candidate.revisionReason = "SOURCE_EVIDENCE_CHANGED";
                candidate.createdAt = candidate.createdAt == null ? LocalDateTime.now() : candidate.createdAt;
                pending.add(candidate);
                supersededIds.add(existing.id);
            }
        }
        if (!supersededIds.isEmpty()) {
            labelMapper.markSuperseded(supersededIds.stream().distinct().toList());
        }
        for (List<AiSampleLabel> chunk : chunks(pending, WRITE_BATCH_SIZE)) {
            labelMapper.insertBatchImmutable(chunk);
        }

        Map<String, AiSampleLabel> persistedByKey = labelIndex(
                labelMapper.selectForSamplesAndVersion(sampleIds, batch.labelVersion()));
        List<AiSampleLabel> stored = new ArrayList<>(built.size());
        for (DefaultLabelPolicy.BuildResult result : built) {
            AiSampleLabel actual = persistedByKey.get(labelKey(result.label()));
            if (actual == null || actual.id == null) {
                throw new IllegalStateException("标签批量写入后未读取到记录：" + labelKey(result.label()));
            }
            requireSameFingerprint("sample label", actual.inputFingerprint, result.label().inputFingerprint);
            stored.add(actual);
        }
        storeCostEvidence(built, persistedByKey);
        return List.copyOf(stored);
    }

    private void storeCostEvidence(
            List<DefaultLabelPolicy.BuildResult> built,
            Map<String, AiSampleLabel> labelsByKey
    ) {
        Map<String, List<AiLabelCostEvidence>> byVersion = new LinkedHashMap<>();
        for (DefaultLabelPolicy.BuildResult result : built) {
            AiLabelCostEvidence candidate = result.costEvidence();
            if (candidate == null) {
                continue;
            }
            AiSampleLabel label = labelsByKey.get(labelKey(result.label()));
            candidate.sampleLabelId = label.id;
            candidate.createdAt = candidate.createdAt == null ? LocalDateTime.now() : candidate.createdAt;
            byVersion.computeIfAbsent(candidate.costModelVersion, ignored -> new ArrayList<>()).add(candidate);
        }
        for (Map.Entry<String, List<AiLabelCostEvidence>> entry : byVersion.entrySet()) {
            storeCostVersion(entry.getKey(), entry.getValue());
        }
    }

    private void storeCostVersion(String costModelVersion, List<AiLabelCostEvidence> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        List<Long> labelIds = candidates.stream().map(value -> value.sampleLabelId).distinct().toList();
        Map<Long, AiLabelCostEvidence> existingByLabel = costIndex(
                costEvidenceMapper.selectForLabelsAndVersion(labelIds, costModelVersion));
        List<AiLabelCostEvidence> pending = new ArrayList<>();
        for (AiLabelCostEvidence candidate : candidates) {
            AiLabelCostEvidence existing = existingByLabel.get(candidate.sampleLabelId);
            if (existing == null) {
                pending.add(candidate);
            } else {
                requireSameFingerprint(
                        "label cost evidence", existing.sourceFingerprint, candidate.sourceFingerprint);
            }
        }
        for (List<AiLabelCostEvidence> chunk : chunks(pending, WRITE_BATCH_SIZE)) {
            costEvidenceMapper.insertBatchImmutable(chunk);
        }
        Map<Long, AiLabelCostEvidence> persistedByLabel = costIndex(
                costEvidenceMapper.selectForLabelsAndVersion(labelIds, costModelVersion));
        for (AiLabelCostEvidence candidate : candidates) {
            AiLabelCostEvidence actual = persistedByLabel.get(candidate.sampleLabelId);
            if (actual == null) {
                throw new IllegalStateException(
                        "交易成本证据批量写入后未读取到记录：" + candidate.sampleLabelId);
            }
            requireSameFingerprint(
                    "label cost evidence", actual.sourceFingerprint, candidate.sourceFingerprint);
        }
    }

    private static Map<String, AiSampleLabel> labelIndex(List<AiSampleLabel> labels) {
        Map<String, AiSampleLabel> result = new LinkedHashMap<>();
        if (labels != null) {
            labels.forEach(label -> {
                AiSampleLabel previous = result.putIfAbsent(labelKey(label), label);
                if (previous != null) {
                    throw new IllegalStateException("同一标签业务键存在多个当前版本：" + labelKey(label));
                }
            });
        }
        return result;
    }

    private static Map<Long, AiLabelCostEvidence> costIndex(List<AiLabelCostEvidence> costs) {
        Map<Long, AiLabelCostEvidence> result = new LinkedHashMap<>();
        if (costs != null) {
            costs.forEach(cost -> result.put(cost.sampleLabelId, cost));
        }
        return result;
    }

    private static String labelKey(AiSampleLabel label) {
        return label.sampleId + "|" + label.horizonTradingDays + "|" + label.labelVersion;
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static <T> List<List<T>> chunks(List<T> values, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int offset = 0; offset < values.size(); offset += size) {
            chunks.add(values.subList(offset, Math.min(offset + size, values.size())));
        }
        return chunks;
    }

    private static void validate(LabelBatch batch) {
        if (batch == null || batch.samples().isEmpty() || batch.calendars().isEmpty()
                || batch.horizons().isEmpty() || batch.calendarVersion() == null
                || batch.calendarVersion().isBlank() || batch.labelVersion() == null
                || batch.labelVersion().isBlank() || batch.verifiedAt() == null) {
            throw new IllegalArgumentException("标签批次缺少样本、日历、周期或版本");
        }
        if (batch.samples().stream().anyMatch(Objects::isNull)
                || batch.horizons().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("标签批次不允许空样本或空周期");
        }
    }

    private static void requireSameFingerprint(String type, String existing, String candidate) {
        if (!Objects.equals(existing, candidate)) {
            throw new IllegalStateException(type + " immutable fingerprint conflict");
        }
    }
}
