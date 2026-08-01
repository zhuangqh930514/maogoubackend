package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.mapper.research.AiModelVersionMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.service.research.AiChallengerReleaseService;
import com.maogou.stock.service.research.AiResearchContract;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AiChallengerReleaseServiceImpl implements AiChallengerReleaseService {

    private final AiModelVersionMapper modelMapper;
    private final AiTrainingDatasetMapper datasetMapper;
    private final AiStrategyReleaseMapper releaseMapper;
    private final ObjectMapper objectMapper;

    public AiChallengerReleaseServiceImpl(
            AiModelVersionMapper modelMapper,
            AiTrainingDatasetMapper datasetMapper,
            AiStrategyReleaseMapper releaseMapper,
            ObjectMapper objectMapper
    ) {
        this.modelMapper = modelMapper;
        this.datasetMapper = datasetMapper;
        this.releaseMapper = releaseMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiStrategyRelease createFromValidatedModel(Long modelId, LocalDateTime now) {
        if (modelId == null || modelId <= 0) {
            throw new IllegalArgumentException("缺少有效模型版本 ID");
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        AiModelVersion model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new IllegalArgumentException("模型版本不存在：" + modelId);
        }
        if (!"VALIDATED".equals(model.status)) {
            throw new IllegalStateException("只有通过质量门的 VALIDATED 模型才能进入 Challenger");
        }
        AiTrainingDataset dataset = datasetMapper.selectById(model.trainingDatasetId);
        if (dataset == null || dataset.researchUniverseId == null) {
            throw new IllegalStateException("模型缺少可追溯的研究股票池血缘，不能进入 Challenger");
        }

        AiStrategyRelease existing = releaseMapper.selectOne(new QueryWrapper<AiStrategyRelease>()
                .eq("research_universe_id", dataset.researchUniverseId)
                .eq("model_family", model.modelFamily)
                .eq("model_version_id", model.id)
                .eq("release_role", "CHALLENGER")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        AiStrategyRelease champion = releaseMapper.selectOne(new QueryWrapper<AiStrategyRelease>()
                .eq("research_universe_id", dataset.researchUniverseId)
                .eq("model_family", model.modelFamily)
                .eq("release_role", "CHAMPION")
                .eq("status", "ACTIVE")
                .last("LIMIT 1"));
        if (champion == null) {
            throw new IllegalStateException("缺少 active Champion，模型已验证但暂不能进入影子运行");
        }

        AiStrategyRelease challenger = new AiStrategyRelease();
        challenger.researchUniverseId = dataset.researchUniverseId;
        challenger.modelFamily = model.modelFamily;
        challenger.versionNo = "CHALLENGER-" + model.versionNo;
        challenger.title = "导入模型 Challenger " + model.versionNo;
        challenger.modelVersionId = model.id;
        challenger.status = "SHADOW";
        challenger.releaseRole = "CHALLENGER";
        challenger.configJson = json("ONNX", "DECISION/2.0.0");
        challenger.factorSnapshotJson = "{\"source\":\"validated-model-package\",\"status\":\"FROZEN\"}";
        challenger.validationMetricsJson = model.metricsJson;
        challenger.promotionReason = "模型包通过统一质量门，进入 SHADOW；禁止自动晋级";
        challenger.shadowStartedAt = effectiveNow;
        challenger.createdAt = effectiveNow;
        challenger.updatedAt = effectiveNow;
        try {
            releaseMapper.insert(challenger);
            return challenger;
        } catch (DuplicateKeyException exception) {
            AiStrategyRelease concurrent = releaseMapper.selectOne(new QueryWrapper<AiStrategyRelease>()
                    .eq("research_universe_id", dataset.researchUniverseId)
                    .eq("model_family", model.modelFamily)
                    .eq("version_no", challenger.versionNo)
                    .last("LIMIT 1"));
            if (concurrent != null && Objects.equals(concurrent.modelVersionId, model.id)) {
                return concurrent;
            }
            throw exception;
        }
    }

    private String json(String engine, String policyVersion) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "engine", engine,
                    "policyVersion", policyVersion,
                    "releaseRole", "CHALLENGER",
                    "inferenceMode", "SHADOW"));
        } catch (Exception exception) {
            throw new IllegalStateException("Challenger 配置序列化失败", exception);
        }
    }
}
