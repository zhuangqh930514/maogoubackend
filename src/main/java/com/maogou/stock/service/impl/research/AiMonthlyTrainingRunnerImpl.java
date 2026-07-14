package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.domain.entity.research.AiTrainingSourceSummary;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import com.maogou.stock.service.research.AiEvolutionAutomationService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiModelTrainer;
import com.maogou.stock.service.research.AiMonthlyTrainingRunner;
import com.maogou.stock.service.research.AiTrainingDatasetService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
public class AiMonthlyTrainingRunnerImpl implements AiMonthlyTrainingRunner {

    private static final long RANDOM_SEED = 930514L;
    private static final int HORIZON_DAYS = 3;
    private static final String DATASET_KEY = "MAOGOU_RANKER_T3";
    private static final String MODEL_KEY = "MAOGOU_RANKER";
    private static final String TRAINER_VERSION = "TRAIN_RANKER_V2_1";

    private final AppProperties properties;
    private final AiTrainingDatasetItemMapper datasetItemMapper;
    private final AiTrainingDatasetService datasetService;
    private final AiModelTrainer modelTrainer;
    private final AiStrategyReleaseMapper strategyReleaseMapper;
    private final ObjectMapper objectMapper;

    public AiMonthlyTrainingRunnerImpl(
            AppProperties properties,
            AiTrainingDatasetItemMapper datasetItemMapper,
            AiTrainingDatasetService datasetService,
            AiModelTrainer modelTrainer,
            AiStrategyReleaseMapper strategyReleaseMapper,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.datasetItemMapper = datasetItemMapper;
        this.datasetService = datasetService;
        this.modelTrainer = modelTrainer;
        this.strategyReleaseMapper = strategyReleaseMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiEvolutionAutomationService.CycleResult run(Long userId, LocalDateTime triggeredAt) {
        validate(userId, triggeredAt);
        AppProperties.Scheduler scheduler = properties.getScheduler();
        AiTrainingSourceSummary summary = datasetItemMapper.selectDominantSourceSummary(
                userId, AiResearchContract.LABEL_VERSION, HORIZON_DAYS, triggeredAt);
        if (summary == null || value(summary.rowCount) < scheduler.getMonthlyMinimumSamples()) {
            int available = summary == null ? 0 : value(summary.rowCount);
            return skipped("成熟训练样本不足：" + available + " / " + scheduler.getMonthlyMinimumSamples());
        }
        List<LocalDate> dates = datasetItemMapper.selectEligibleTradeDates(
                userId, summary.featureVersion, summary.labelVersion, summary.calendarVersion,
                HORIZON_DAYS, triggeredAt);
        if (dates == null || dates.size() < 15) {
            return skipped("有效交易日不足：" + (dates == null ? 0 : dates.size()) + " / 15");
        }
        TrainingWindows windows = windows(dates);
        String version = triggeredAt.format(DateTimeFormatter.BASIC_ISO_DATE);
        Path root = Path.of(scheduler.getTrainingArtifactRoot()).toAbsolutePath().normalize()
                .resolve(String.valueOf(userId)).resolve(version);
        Path datasetPath = root.resolve("dataset.jsonl");
        AiTrainingDatasetService.DatasetBuildResult datasetResult = datasetService.buildDataset(
                new AiTrainingDatasetService.DatasetBuildRequest(
                        userId, DATASET_KEY, version, "MONTHLY_MODEL_TRAINING",
                        summary.featureVersion, summary.labelVersion, summary.calendarVersion,
                        triggeredAt, windows.trainStart(), windows.trainEnd(),
                        windows.validationStart(), windows.validationEnd(),
                        windows.testStart(), windows.testEnd(), HORIZON_DAYS, datasetPath));
        AiTrainingDataset dataset = datasetResult.dataset();
        if (dataset == null || dataset.id == null
                || value(dataset.rowCount) < scheduler.getMonthlyMinimumSamples()) {
            int selected = dataset == null ? 0 : value(dataset.rowCount);
            return skipped("通过时间可见性校验的样本不足：" + selected + " / "
                    + scheduler.getMonthlyMinimumSamples());
        }

        AiModelTrainer.TrainingArtifacts artifacts = modelTrainer.train(
                new AiModelTrainer.TrainingRequest(datasetPath, root.resolve("model"), RANDOM_SEED));
        JsonNode metrics = readJson(artifacts.metricsPath(), "模型指标");
        JsonNode calibration = metrics.path("calibration");
        boolean qualityGatePassed = qualityGatePassed(metrics, scheduler.getModelMinimumTestRocAuc());
        AiModelVersion model = datasetService.registerModel(new AiTrainingDatasetService.ModelRegistration(
                userId, dataset.id, MODEL_KEY, version, "RANKER", artifacts.algorithm(),
                summary.featureVersion, metrics.path("trainerVersion").asText(TRAINER_VERSION),
                RANDOM_SEED, artifacts.onnxPath().toUri().toString(), sha256(artifacts.onnxPath()),
                artifacts.featureManifestPath().toUri().toString(), sha256(artifacts.featureManifestPath()),
                metrics.path("parameters").toString(), metrics.toString(), calibration.toString(),
                dataset.rowCount, qualityGatePassed, triggeredAt));
        if (!"VALIDATED".equals(model.status)) {
            return new AiEvolutionAutomationService.CycleResult(
                    "SUCCESS", dataset.rowCount, 1, 0,
                    "模型已注册为 CANDIDATE，样本外质量门未通过，不创建 Challenger");
        }
        AiStrategyRelease challenger = createOrGetChallenger(userId, model, metrics.toString(), triggeredAt);
        return new AiEvolutionAutomationService.CycleResult(
                "SUCCESS", dataset.rowCount, 1, 0,
                "已生成 VALIDATED 模型和 SHADOW Challenger #" + challenger.id);
    }

    private AiStrategyRelease createOrGetChallenger(
            Long userId,
            AiModelVersion model,
            String metricsJson,
            LocalDateTime now
    ) {
        AiStrategyRelease existing = strategyReleaseMapper.selectOne(new QueryWrapper<AiStrategyRelease>()
                .eq("user_id", userId)
                .eq("model_version_id", model.id)
                .eq("release_role", "CHALLENGER")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        AiStrategyRelease champion = strategyReleaseMapper.selectOne(new QueryWrapper<AiStrategyRelease>()
                .eq("user_id", userId)
                .eq("release_role", "CHAMPION")
                .eq("status", "ACTIVE")
                .last("LIMIT 1"));
        if (champion == null) {
            throw new IllegalStateException("缺少 active Champion，VALIDATED 模型暂不能进入影子运行");
        }
        AiStrategyRelease challenger = new AiStrategyRelease();
        challenger.userId = userId;
        challenger.versionNo = "CHALLENGER-" + model.versionNo;
        challenger.title = "月度训练 Challenger " + model.versionNo;
        challenger.modelVersionId = model.id;
        challenger.status = "SHADOW";
        challenger.releaseRole = "CHALLENGER";
        challenger.configJson = "{\"engine\":\"ONNX\",\"policyVersion\":\"DECISION_V2.1\"}";
        challenger.factorSnapshotJson = "{\"factorVersion\":\""
                + AiResearchContract.FACTOR_VERSION + "\",\"featureVersion\":\""
                + model.featureVersion + "\"}";
        challenger.validationMetricsJson = metricsJson;
        challenger.promotionReason = "月度训练质量门通过，进入影子验证，禁止自动晋级";
        challenger.shadowStartedAt = now;
        challenger.createdAt = now;
        challenger.updatedAt = now;
        try {
            strategyReleaseMapper.insert(challenger);
            return challenger;
        } catch (DuplicateKeyException exception) {
            AiStrategyRelease concurrent = strategyReleaseMapper.selectOne(new QueryWrapper<AiStrategyRelease>()
                    .eq("user_id", userId)
                    .eq("version_no", challenger.versionNo)
                    .last("LIMIT 1"));
            if (concurrent != null && Objects.equals(concurrent.modelVersionId, model.id)) {
                return concurrent;
            }
            throw exception;
        }
    }

    private JsonNode readJson(Path path, String label) {
        try {
            return objectMapper.readTree(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取" + label + "：" + path, exception);
        }
    }

    private static boolean qualityGatePassed(JsonNode metrics, double configuredMinimumTestAuc) {
        double validationAuc = metrics.path("splits").path("validation").path("rocAuc")
                .asDouble(Double.NaN);
        double testAuc = metrics.path("splits").path("test").path("rocAuc")
                .asDouble(Double.NaN);
        return metrics.path("artifacts").path("onnxExported").asBoolean(false)
                && metrics.path("calibration").path("fitted").asBoolean(false)
                && Double.isFinite(validationAuc) && validationAuc >= 0.55d
                && Double.isFinite(testAuc) && testAuc >= Math.max(0.52d, configuredMinimumTestAuc);
    }

    private static TrainingWindows windows(List<LocalDate> dates) {
        int size = dates.size();
        int trainEnd = Math.max(0, Math.min(size - 3, (int) Math.floor(size * 0.60d) - 1));
        int validationEnd = Math.max(trainEnd + 1,
                Math.min(size - 2, (int) Math.floor(size * 0.80d) - 1));
        return new TrainingWindows(
                dates.get(0), dates.get(trainEnd),
                dates.get(trainEnd + 1), dates.get(validationEnd),
                dates.get(validationEnd + 1), dates.get(size - 1));
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    if (length > 0) {
                        digest.update(buffer, 0, length);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("无法计算模型产物 SHA-256：" + path, exception);
        }
    }

    private static void validate(Long userId, LocalDateTime triggeredAt) {
        if (userId == null || userId <= 0 || triggeredAt == null) {
            throw new IllegalArgumentException("月度训练缺少用户或触发时间");
        }
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static AiEvolutionAutomationService.CycleResult skipped(String message) {
        return new AiEvolutionAutomationService.CycleResult("SKIPPED", 0, 0, 0, message);
    }

    private record TrainingWindows(
            LocalDate trainStart,
            LocalDate trainEnd,
            LocalDate validationStart,
            LocalDate validationEnd,
            LocalDate testStart,
            LocalDate testEnd
    ) {
    }
}
