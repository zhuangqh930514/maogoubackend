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
import com.maogou.stock.service.research.AiResearchCycleResult;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiModelTrainer;
import com.maogou.stock.service.research.AiMonthlyTrainingRunner;
import com.maogou.stock.service.research.AiTrainingDatasetService;
import com.maogou.stock.service.research.AiTrainingReadinessService;
import com.maogou.stock.service.research.ExternalIoTransactionGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class AiMonthlyTrainingServiceImpl implements AiMonthlyTrainingRunner {

    private static final long RANDOM_SEED = 930514L;
    private static final int HORIZON_DAYS = 3;
    private static final String DATASET_KEY = "MAOGOU_RANKER_T3";
    private static final String MODEL_KEY = "MAOGOU_RANKER";
    private static final String TRAINER_VERSION = "TRAIN_RANKER_V2_1";
    private static final DateTimeFormatter VERSION_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AppProperties properties;
    private final AiTrainingDatasetItemMapper datasetItemMapper;
    private final AiTrainingReadinessService readinessService;
    private final AiTrainingDatasetService datasetService;
    private final AiModelTrainer modelTrainer;
    private final AiStrategyReleaseMapper strategyReleaseMapper;
    private final ObjectMapper objectMapper;

    public AiMonthlyTrainingServiceImpl(
            AppProperties properties,
            AiTrainingDatasetItemMapper datasetItemMapper,
            AiTrainingReadinessService readinessService,
            AiTrainingDatasetService datasetService,
            AiModelTrainer modelTrainer,
            AiStrategyReleaseMapper strategyReleaseMapper,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.datasetItemMapper = datasetItemMapper;
        this.readinessService = readinessService;
        this.datasetService = datasetService;
        this.modelTrainer = modelTrainer;
        this.strategyReleaseMapper = strategyReleaseMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiResearchCycleResult run(Long ignoredActorUserId, LocalDateTime triggeredAt) {
        validate(triggeredAt);
        AppProperties.Scheduler scheduler = properties.getScheduler();
        AiTrainingReadinessGate.Readiness readiness = readinessService.assess(triggeredAt);
        if (!"READY".equals(readiness.status())) {
            return new AiResearchCycleResult(
                    "INSUFFICIENT_DATA", readiness.tradingDays(), 0, 0,
                    readinessMessage(readiness));
        }
        AiStrategyRelease champion = strategyReleaseMapper.selectGlobalActiveChampion(
                AiResearchContract.SYSTEM_UNIVERSE_CODE, AiResearchContract.MODEL_FAMILY);
        if (champion == null || champion.researchUniverseId == null) {
            throw new IllegalStateException("统一研究域缺少 active Champion，不能创建全局训练数据集");
        }
        AiTrainingSourceSummary summary = datasetItemMapper.selectDominantSourceSummary(
                AiResearchContract.LABEL_VERSION, HORIZON_DAYS, triggeredAt);
        int minimumSamples = Math.max(
                AiTrainingReadinessGate.MINIMUM_LABELS_PER_HORIZON,
                scheduler.getMonthlyMinimumSamples());
        if (summary == null || value(summary.rowCount) < minimumSamples) {
            int available = summary == null ? 0 : value(summary.rowCount);
            return skipped("T+3 成熟可执行训练样本不足：" + available + " / " + minimumSamples);
        }
        List<LocalDate> dates = datasetItemMapper.selectEligibleTradeDates(
                summary.featureVersion, summary.labelVersion, summary.calendarVersion,
                HORIZON_DAYS, triggeredAt);
        if (dates == null || dates.size() < AiTrainingReadinessGate.MINIMUM_TRADING_DAYS) {
            return skipped("有效交易日不足：" + (dates == null ? 0 : dates.size()) + " / "
                    + AiTrainingReadinessGate.MINIMUM_TRADING_DAYS);
        }
        TrainingWindows windows = windows(dates);
        String version = triggeredAt.format(VERSION_FORMATTER);
        Path root = Path.of(scheduler.getTrainingArtifactRoot()).toAbsolutePath().normalize()
                .resolve(AiResearchContract.MODEL_FAMILY).resolve(version);
        Path datasetPath = root.resolve("dataset.jsonl");
        AiTrainingDatasetService.DatasetBuildResult datasetResult = datasetService.buildDataset(
                new AiTrainingDatasetService.DatasetBuildRequest(
                        champion.researchUniverseId, DATASET_KEY, version,
                        AiResearchContract.MODEL_FAMILY, "MONTHLY_MODEL_TRAINING",
                        summary.featureVersion, summary.labelVersion, summary.calendarVersion,
                        triggeredAt, windows.trainStart(), windows.trainEnd(),
                        windows.validationStart(), windows.validationEnd(),
                        windows.testStart(), windows.testEnd(), HORIZON_DAYS,
                        5, 5, datasetPath));
        AiTrainingDataset dataset = datasetResult.dataset();
        if (dataset == null || dataset.id == null
                || value(dataset.rowCount) < minimumSamples) {
            int selected = dataset == null ? 0 : value(dataset.rowCount);
            return skipped("通过时间可见性校验的样本不足：" + selected + " / "
                    + minimumSamples);
        }

        AiModelTrainer.TrainingArtifacts artifacts = trainAndPublish(datasetPath, root);
        JsonNode metrics = readJson(artifacts.metricsPath(), "模型指标");
        JsonNode calibration = metrics.path("calibration");
        boolean qualityGatePassed = qualityGatePassed(metrics, scheduler.getModelMinimumTestRocAuc());
        AiModelVersion model = datasetService.registerModel(new AiTrainingDatasetService.ModelRegistration(
                dataset.id, AiResearchContract.MODEL_FAMILY, MODEL_KEY, version,
                "RANKER", artifacts.algorithm(),
                summary.featureVersion, metrics.path("trainerVersion").asText(TRAINER_VERSION),
                RANDOM_SEED, artifacts.onnxPath().toUri().toString(), sha256(artifacts.onnxPath()),
                artifacts.featureManifestPath().toUri().toString(), sha256(artifacts.featureManifestPath()),
                metrics.path("parameters").toString(), metrics.toString(), calibration.toString(),
                dataset.rowCount, qualityGatePassed, triggeredAt));
        if (!"VALIDATED".equals(model.status)) {
            return new AiResearchCycleResult(
                    "SUCCESS", dataset.rowCount, 1, 0,
                    "模型已注册为 CANDIDATE，样本外质量门未通过，不创建 Challenger");
        }
        AiStrategyRelease challenger = createOrGetChallenger(
                champion.researchUniverseId, model, metrics.toString(), triggeredAt);
        return new AiResearchCycleResult(
                "SUCCESS", dataset.rowCount, 1, 0,
                "已生成 VALIDATED 模型和 SHADOW Challenger #" + challenger.id);
    }

    private AiModelTrainer.TrainingArtifacts trainAndPublish(Path datasetPath, Path versionRoot) {
        Path temporaryDirectory = null;
        try {
            Files.createDirectories(versionRoot);
            temporaryDirectory = Files.createTempDirectory(versionRoot, ".model.tmp-");
            Path trainingDirectory = temporaryDirectory;
            AiModelTrainer.TrainingArtifacts temporaryArtifacts = ExternalIoTransactionGuard.call(
                    "模型训练调用",
                    () -> modelTrainer.train(
                            new AiModelTrainer.TrainingRequest(datasetPath, trainingDirectory, RANDOM_SEED)));
            VerifiedArtifacts verified = verifyTrainingArtifacts(temporaryArtifacts, temporaryDirectory);
            Path finalDirectory = versionRoot.resolve("model");
            if (Files.exists(finalDirectory)) {
                VerifiedArtifacts existing = verifyPublishedArtifacts(finalDirectory, verified);
                deleteRecursively(temporaryDirectory);
                temporaryDirectory = null;
                return existing.artifacts();
            }
            Files.move(temporaryDirectory, finalDirectory, StandardCopyOption.ATOMIC_MOVE);
            temporaryDirectory = null;
            return verifyPublishedArtifacts(finalDirectory, verified).artifacts();
        } catch (IOException exception) {
            throw new IllegalStateException("模型产物无法原子发布", exception);
        } finally {
            if (temporaryDirectory != null) {
                deleteRecursively(temporaryDirectory);
            }
        }
    }

    private VerifiedArtifacts verifyTrainingArtifacts(
            AiModelTrainer.TrainingArtifacts artifacts,
            Path outputDirectory
    ) throws IOException {
        if (artifacts == null || artifacts.algorithm() == null || artifacts.algorithm().isBlank()) {
            throw new IllegalStateException("训练器未返回有效算法或产物");
        }
        Path realOutput = outputDirectory.toRealPath();
        Path model = verifiedFile(artifacts.modelPath(), realOutput, "模型文件");
        Path onnx = verifiedFile(artifacts.onnxPath(), realOutput, "ONNX");
        Path manifest = verifiedFile(artifacts.featureManifestPath(), realOutput, "特征清单");
        Path metricsPath = verifiedFile(artifacts.metricsPath(), realOutput, "模型指标");
        JsonNode manifestJson = readJson(manifest, "特征清单");
        JsonNode metrics = readJson(metricsPath, "模型指标");
        if (!manifestJson.path("features").isArray() || manifestJson.path("features").isEmpty()) {
            throw new IllegalStateException("特征清单缺少非空 features");
        }
        String onnxChecksum = sha256(onnx);
        String manifestChecksum = sha256(manifest);
        if (!onnxChecksum.equalsIgnoreCase(metrics.path("artifacts").path("onnxSha256").asText())
                || !onnxChecksum.equalsIgnoreCase(
                metrics.path("artifacts").path("modelSha256").asText())
                || !manifestChecksum.equalsIgnoreCase(
                metrics.path("artifacts").path("featureManifestSha256").asText())) {
            throw new IllegalStateException("模型产物与 metrics SHA256 不一致");
        }
        return new VerifiedArtifacts(
                new AiModelTrainer.TrainingArtifacts(
                        artifacts.algorithm(), model, onnx, manifest, metricsPath),
                sha256(model), onnxChecksum, manifestChecksum, sha256(metricsPath));
    }

    private VerifiedArtifacts verifyPublishedArtifacts(
            Path finalDirectory,
            VerifiedArtifacts expected
    ) throws IOException {
        Path sourceDirectory = expected.artifacts().onnxPath().getParent();
        AiModelTrainer.TrainingArtifacts published = new AiModelTrainer.TrainingArtifacts(
                expected.artifacts().algorithm(),
                finalDirectory.resolve(sourceDirectory.relativize(expected.artifacts().modelPath())),
                finalDirectory.resolve(sourceDirectory.relativize(expected.artifacts().onnxPath())),
                finalDirectory.resolve(sourceDirectory.relativize(expected.artifacts().featureManifestPath())),
                finalDirectory.resolve(sourceDirectory.relativize(expected.artifacts().metricsPath())));
        VerifiedArtifacts actual = verifyTrainingArtifacts(published, finalDirectory);
        if (!Objects.equals(expected.modelChecksum(), actual.modelChecksum())
                || !Objects.equals(expected.onnxChecksum(), actual.onnxChecksum())
                || !Objects.equals(expected.manifestChecksum(), actual.manifestChecksum())
                || !Objects.equals(expected.metricsChecksum(), actual.metricsChecksum())) {
            throw new IllegalStateException("最终模型目录与已校验临时产物不一致");
        }
        return actual;
    }

    private static Path verifiedFile(Path path, Path outputDirectory, String label) throws IOException {
        if (path == null || !Files.isRegularFile(path) || Files.size(path) <= 0) {
            throw new IllegalStateException(label + "不存在或为空");
        }
        Path real = path.toRealPath();
        if (!real.startsWith(outputDirectory)) {
            throw new IllegalStateException(label + "越出训练临时目录");
        }
        return real;
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The original training/publish failure remains the actionable error.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    private AiStrategyRelease createOrGetChallenger(
            Long researchUniverseId,
            AiModelVersion model,
            String metricsJson,
            LocalDateTime now
    ) {
        AiStrategyRelease existing = strategyReleaseMapper.selectOne(new QueryWrapper<AiStrategyRelease>()
                .eq("research_universe_id", researchUniverseId)
                .eq("model_family", AiResearchContract.MODEL_FAMILY)
                .eq("model_version_id", model.id)
                .eq("release_role", "CHALLENGER")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        AiStrategyRelease champion = strategyReleaseMapper.selectOne(new QueryWrapper<AiStrategyRelease>()
                .eq("research_universe_id", researchUniverseId)
                .eq("model_family", AiResearchContract.MODEL_FAMILY)
                .eq("release_role", "CHAMPION")
                .eq("status", "ACTIVE")
                .last("LIMIT 1"));
        if (champion == null) {
            throw new IllegalStateException("缺少 active Champion，VALIDATED 模型暂不能进入影子运行");
        }
        AiStrategyRelease challenger = new AiStrategyRelease();
        challenger.researchUniverseId = researchUniverseId;
        challenger.modelFamily = AiResearchContract.MODEL_FAMILY;
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
                    .eq("research_universe_id", researchUniverseId)
                    .eq("model_family", AiResearchContract.MODEL_FAMILY)
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
        int isolationDays = 10;
        int usableDays = size - isolationDays * 2;
        if (usableDays < 3) {
            throw new IllegalArgumentException("训练日期不足以建立 5 日 purge 和额外 5 日 embargo");
        }
        int trainDays = Math.max(1, (int) Math.floor(usableDays * 0.60d));
        int validationDays = Math.max(1, (int) Math.floor(usableDays * 0.20d));
        int testDays = usableDays - trainDays - validationDays;
        if (testDays <= 0) {
            throw new IllegalArgumentException("训练日期无法形成非空测试集");
        }
        int trainEnd = trainDays - 1;
        int validationStart = trainEnd + isolationDays + 1;
        int validationEnd = validationStart + validationDays - 1;
        int testStart = validationEnd + isolationDays + 1;
        return new TrainingWindows(
                dates.get(0), dates.get(trainEnd),
                dates.get(validationStart), dates.get(validationEnd),
                dates.get(testStart), dates.get(size - 1));
    }

    private static String readinessMessage(AiTrainingReadinessGate.Readiness readiness) {
        return "训练数据尚未就绪：remainingTradingDays=" + readiness.remainingTradingDays()
                + ", remainingStocks=" + readiness.remainingStocks()
                + ", remainingLabels=" + readiness.remainingLabels()
                + ", missingRegimes=" + readiness.missingRegimes();
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

    private static void validate(LocalDateTime triggeredAt) {
        if (triggeredAt == null) {
            throw new IllegalArgumentException("月度训练缺少触发时间");
        }
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static AiResearchCycleResult skipped(String message) {
        return new AiResearchCycleResult("SKIPPED", 0, 0, 0, message);
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

    private record VerifiedArtifacts(
            AiModelTrainer.TrainingArtifacts artifacts,
            String modelChecksum,
            String onnxChecksum,
            String manifestChecksum,
            String metricsChecksum
    ) {
    }
}
