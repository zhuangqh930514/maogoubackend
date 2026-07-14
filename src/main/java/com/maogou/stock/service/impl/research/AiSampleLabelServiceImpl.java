package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.research.AiLabelCostEvidence;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.mapper.research.AiLabelCostEvidenceMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.service.research.AiSampleLabelService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class AiSampleLabelServiceImpl implements AiSampleLabelService {

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
        List<AiSampleLabel> labels = new ArrayList<>();
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
                AiSampleLabel stored = storeImmutableLabel(result.label());
                storeImmutableCostEvidence(stored, result.costEvidence());
                labels.add(stored);
            }
        }
        return List.copyOf(labels);
    }

    private AiSampleLabel storeImmutableLabel(AiSampleLabel candidate) {
        AiSampleLabel existing = findLabel(
                candidate.sampleId,
                candidate.horizonTradingDays,
                candidate.labelVersion
        );
        if (existing != null) {
            requireSameFingerprint("sample label", existing.inputFingerprint, candidate.inputFingerprint);
            return existing;
        }
        candidate.createdAt = candidate.createdAt == null ? LocalDateTime.now() : candidate.createdAt;
        try {
            labelMapper.insert(candidate);
            return candidate;
        } catch (DuplicateKeyException duplicate) {
            AiSampleLabel raced = findLabel(
                    candidate.sampleId,
                    candidate.horizonTradingDays,
                    candidate.labelVersion
            );
            if (raced == null) {
                throw duplicate;
            }
            requireSameFingerprint("sample label", raced.inputFingerprint, candidate.inputFingerprint);
            return raced;
        }
    }

    private void storeImmutableCostEvidence(AiSampleLabel label, AiLabelCostEvidence candidate) {
        if (candidate == null) {
            return;
        }
        AiLabelCostEvidence existing = costEvidenceMapper.selectOne(
                new QueryWrapper<AiLabelCostEvidence>()
                        .eq("sample_label_id", label.id)
                        .eq("cost_model_version", candidate.costModelVersion)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            requireSameFingerprint("label cost evidence", existing.sourceFingerprint, candidate.sourceFingerprint);
            return;
        }
        candidate.sampleLabelId = label.id;
        candidate.createdAt = candidate.createdAt == null ? LocalDateTime.now() : candidate.createdAt;
        try {
            costEvidenceMapper.insert(candidate);
        } catch (DuplicateKeyException duplicate) {
            AiLabelCostEvidence raced = costEvidenceMapper.selectOne(
                    new QueryWrapper<AiLabelCostEvidence>()
                            .eq("sample_label_id", label.id)
                            .eq("cost_model_version", candidate.costModelVersion)
                            .last("LIMIT 1")
            );
            if (raced == null) {
                throw duplicate;
            }
            requireSameFingerprint("label cost evidence", raced.sourceFingerprint, candidate.sourceFingerprint);
        }
    }

    private AiSampleLabel findLabel(Long sampleId, Integer horizon, String labelVersion) {
        return labelMapper.selectOne(
                new QueryWrapper<AiSampleLabel>()
                        .eq("sample_id", sampleId)
                        .eq("horizon_trading_days", horizon)
                        .eq("label_version", labelVersion)
                        .last("LIMIT 1")
        );
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
