package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.mapper.research.AiModelVersionMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.service.research.AiChallengerReleaseService;
import com.maogou.stock.service.research.AiImportedModelQualificationService;
import com.maogou.stock.service.research.AiModelQualityGate;
import com.maogou.stock.service.research.OnnxModelHealthValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AiImportedModelQualificationServiceImpl implements AiImportedModelQualificationService {

    private final AppProperties properties;
    private final AiModelVersionMapper modelMapper;
    private final AiTrainingDatasetMapper datasetMapper;
    private final AiModelQualityGate qualityGate;
    private final OnnxModelHealthValidator onnxValidator;
    private final AiChallengerReleaseService challengerReleaseService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiImportedModelQualificationServiceImpl(
            AppProperties properties,
            AiModelVersionMapper modelMapper,
            AiTrainingDatasetMapper datasetMapper,
            AiModelQualityGate qualityGate,
            OnnxModelHealthValidator onnxValidator,
            AiChallengerReleaseService challengerReleaseService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.modelMapper = modelMapper;
        this.datasetMapper = datasetMapper;
        this.qualityGate = qualityGate;
        this.onnxValidator = onnxValidator;
        this.challengerReleaseService = challengerReleaseService;
        this.objectMapper = objectMapper;
    }

    @Override
    public QualificationResult qualifyAndCreateShadow(Long modelId, LocalDateTime now) {
        if (modelId == null || modelId <= 0) {
            throw new IllegalArgumentException("缺少有效模型版本 ID");
        }
        AiModelVersion model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new IllegalArgumentException("模型版本不存在：" + modelId);
        }
        if (!"CANDIDATE".equals(model.status) && !"VALIDATED".equals(model.status)) {
            throw new IllegalStateException("只有 CANDIDATE 或 VALIDATED 模型可以重新进入质量门：" + model.status);
        }
        AiTrainingDataset dataset = datasetMapper.selectById(model.trainingDatasetId);
        if (dataset == null) {
            throw new IllegalStateException("模型关联的训练数据集不存在：" + model.trainingDatasetId);
        }

        List<AiModelQualityGate.Check> checks = new ArrayList<>();
        JsonNode metrics = parse(model.metricsJson, "模型指标", checks);
        JsonNode calibration = parse(model.calibrationJson, "模型校准", checks);
        if (metrics == null || calibration == null) {
            return failed(model, checks, "模型指标或校准 JSON 无法解析，保持 CANDIDATE");
        }
        if (onnxValidator != null) {
            try {
                onnxValidator.verify(path(model.artifactUri));
                checks.add(new AiModelQualityGate.Check(
                        "ONNX_RUNTIME", true, "可被生产运行时加载", "verified", "通过"));
            } catch (RuntimeException exception) {
                checks.add(new AiModelQualityGate.Check(
                        "ONNX_RUNTIME", false, "可被生产运行时加载", "failed",
                        "ONNX 运行时校验失败：" + message(exception)));
            }
        }

        AiModelQualityGate.Evaluation evaluation = qualityGate.evaluate(
                dataset,
                model.sampleCount == null ? 0 : model.sampleCount,
                metrics,
                calibration,
                properties.getScheduler().getMonthlyMinimumSamples(),
                properties.getScheduler().getModelMinimumTestRocAuc());
        checks.addAll(evaluation.checks());
        boolean passed = checks.stream().allMatch(AiModelQualityGate.Check::passed);
        if (!passed) {
            return failed(model, checks, checks.stream().filter(check -> !check.passed())
                    .map(AiModelQualityGate.Check::message).reduce((left, right) -> left + "；" + right)
                    .orElse("质量门未通过，保持 CANDIDATE"));
        }

        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        if (!"VALIDATED".equals(model.status)) {
            model.status = "VALIDATED";
            model.updatedAt = effectiveNow;
            modelMapper.updateById(model);
        }
        var release = challengerReleaseService.createFromValidatedModel(model.id, effectiveNow);
        return new QualificationResult(model.id, "VALIDATED", true, checks, release.id, release.status,
                "质量门通过，已进入 SHADOW Challenger #" + release.id);
    }

    private QualificationResult failed(
            AiModelVersion model,
            List<AiModelQualityGate.Check> checks,
            String message
    ) {
        return new QualificationResult(model.id, model.status, false, checks, null, null, message);
    }

    private JsonNode parse(String value, String field, List<AiModelQualityGate.Check> checks) {
        if (value == null || value.isBlank()) {
            checks.add(new AiModelQualityGate.Check("JSON_" + field, false, "合法 JSON", "missing", field + "缺失"));
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            checks.add(new AiModelQualityGate.Check("JSON_" + field, false, "合法 JSON", "invalid",
                    field + "解析失败：" + message(exception)));
            return null;
        }
    }

    private static Path path(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("模型产物 URI 缺失");
        }
        try {
            URI uri = URI.create(value);
            return "file".equalsIgnoreCase(uri.getScheme()) ? Path.of(uri) : Path.of(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("模型产物 URI 无效", exception);
        }
    }

    private static String message(Throwable exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }
}
