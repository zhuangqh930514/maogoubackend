package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.mapper.research.AiResearchSchemaVersionMapper;
import com.maogou.stock.service.research.AiModelPackageImportService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiTrainingDatasetService;
import com.maogou.stock.service.research.OnnxModelHealthValidator;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Imports an immutable, locally-trained candidate without accepting local database identifiers.
 */
@Service
public class AiModelPackageImportServiceImpl implements AiModelPackageImportService {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,96}");
    private static final Pattern CHECKSUM_LINE = Pattern.compile("([0-9a-fA-F]{64}) {2}([A-Za-z0-9._-]+)");
    private static final long MAX_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_ARCHIVE_ENTRIES = 32;
    private static final Set<String> REQUIRED_FILES = Set.of(
            "model.onnx", "feature-manifest.json", "metrics.json", "calibration.json",
            "dataset-manifest.json", "runtime-manifest.json", "model-card.md",
            "package-manifest.json", "checksums.sha256");
    private static final Set<String> CHECKSUMMED_FILES = Set.of(
            "model.onnx", "feature-manifest.json", "metrics.json", "calibration.json",
            "dataset-manifest.json", "runtime-manifest.json", "model-card.md",
            "package-manifest.json");

    private final AppProperties properties;
    private final AiTrainingDatasetMapper datasetMapper;
    private final AiResearchSchemaVersionMapper schemaVersionMapper;
    private final AiTrainingDatasetService datasetService;
    private final OnnxModelHealthValidator onnxValidator;
    private final ObjectMapper objectMapper;

    public AiModelPackageImportServiceImpl(
            AppProperties properties,
            AiTrainingDatasetMapper datasetMapper,
            AiResearchSchemaVersionMapper schemaVersionMapper,
            AiTrainingDatasetService datasetService,
            OnnxModelHealthValidator onnxValidator,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.datasetMapper = datasetMapper;
        this.schemaVersionMapper = schemaVersionMapper;
        this.datasetService = datasetService;
        this.onnxValidator = onnxValidator;
        this.objectMapper = objectMapper;
    }

    @Override
    public ImportResult importCandidate(MultipartFile packageFile, Long operatorUserId) {
        validateUpload(packageFile, operatorUserId);
        Path staging = null;
        Path published = null;
        boolean publishedByThisImport = false;
        try {
            Path artifactRoot = artifactRoot();
            staging = Files.createTempDirectory(artifactRoot.resolve(".model-package-staging"), "upload-");
            Path archive = staging.resolve("candidate.tar.gz");
            copyUpload(packageFile, archive, properties.getScheduler().getModelPackageMaxBytes());
            String packageChecksum = sha256(archive);
            Path unpacked = unpack(archive, staging.resolve("unpacked"));
            CandidatePackage candidate = inspect(unpacked, packageChecksum);
            verifyTargetSchema(candidate.sourceSchemaVersion());
            AiTrainingDataset dataset = findProductionDataset(candidate);
            onnxValidator.verify(candidate.modelPath());

            Path target = artifactRoot.resolve("imported-models")
                    .resolve(candidate.modelFamily()).resolve(candidate.modelKey())
                    .resolve(candidate.versionNo()).resolve(packageChecksum);
            if (Files.exists(target)) {
                assertExistingArtifactMatches(target, candidate);
                candidate = candidate.relocate(target);
            } else {
                Files.createDirectories(target.getParent());
                moveAtomically(unpacked, target);
                published = target;
                publishedByThisImport = true;
                candidate = candidate.relocate(target);
            }

            AiModelVersion registered = datasetService.registerModel(new AiTrainingDatasetService.ModelRegistration(
                    dataset.id, candidate.modelFamily(), candidate.modelKey(), candidate.versionNo(),
                    candidate.modelType(), candidate.algorithm(), candidate.featureVersion(),
                    candidate.trainerVersion(), candidate.randomSeed(), candidate.modelPath().toUri().toString(),
                    candidate.modelChecksum(), candidate.featureManifestPath().toUri().toString(),
                    candidate.featureManifestChecksum(), candidate.metrics().path("parameters").toString(),
                    candidate.metrics().toString(), candidate.calibration().toString(), candidate.sampleCount(),
                    false, LocalDateTime.now()));
            if (!"CANDIDATE".equals(registered.status)) {
                throw new IllegalStateException("导入模型必须保持 CANDIDATE 状态");
            }
            return new ImportResult(registered.id, dataset.id, registered.modelFamily, registered.modelKey,
                    registered.versionNo, registered.status, packageChecksum);
        } catch (IOException | RuntimeException exception) {
            if (publishedByThisImport) {
                deleteRecursively(published);
            }
            if (exception instanceof IOException ioException) {
                throw new IllegalStateException("候选模型包导入失败", ioException);
            }
            throw (RuntimeException) exception;
        } finally {
            deleteRecursively(staging);
        }
    }

    private Path artifactRoot() throws IOException {
        Path root = Path.of(properties.getScheduler().getTrainingArtifactRoot()).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve(".model-package-staging"));
        return root;
    }

    private void validateUpload(MultipartFile packageFile, Long operatorUserId) {
        if (operatorUserId == null || operatorUserId <= 0) {
            throw new IllegalArgumentException("缺少研究运维操作者");
        }
        if (packageFile == null || packageFile.isEmpty()) {
            throw new IllegalArgumentException("请上传非空候选模型包");
        }
        String filename = packageFile.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".tar.gz")) {
            throw new IllegalArgumentException("仅支持 .tar.gz 候选模型包");
        }
        long maxBytes = properties.getScheduler().getModelPackageMaxBytes();
        if (maxBytes <= 0 || packageFile.getSize() > maxBytes) {
            throw new IllegalArgumentException("候选模型包超过大小限制");
        }
    }

    private static void copyUpload(MultipartFile file, Path target, long maxBytes) throws IOException {
        long copied = 0;
        try (InputStream input = file.getInputStream(); var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                copied += read;
                if (copied > maxBytes) {
                    throw new IllegalArgumentException("候选模型包超过大小限制");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private Path unpack(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        String packageRoot = null;
        Set<String> files = new LinkedHashSet<>();
        int entries = 0;
        long extracted = 0;
        try (InputStream source = Files.newInputStream(archive);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(source);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES || entry.isSymbolicLink() || entry.isLink()
                        || entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
                    throw new IllegalArgumentException("候选模型包包含不安全的归档条目");
                }
                String name = entry.getName();
                if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\\\")) {
                    throw new IllegalArgumentException("候选模型包路径无效");
                }
                String[] parts = name.split("/");
                if (Arrays.stream(parts).anyMatch(part -> part.isBlank() || ".".equals(part) || "..".equals(part))) {
                    throw new IllegalArgumentException("候选模型包存在路径穿越");
                }
                if (packageRoot == null) {
                    packageRoot = parts[0];
                    requireSafeIdentifier(packageRoot, "模型包目录");
                }
                if (!packageRoot.equals(parts[0])) {
                    throw new IllegalArgumentException("候选模型包只能包含一个根目录");
                }
                if (entry.isDirectory()) {
                    if (parts.length != 1) {
                        throw new IllegalArgumentException("候选模型包不允许嵌套目录");
                    }
                    Files.createDirectories(destination.resolve(packageRoot));
                    continue;
                }
                if (!entry.isFile() || parts.length != 2 || !REQUIRED_FILES.contains(parts[1]) || !files.add(parts[1])) {
                    throw new IllegalArgumentException("候选模型包文件清单不合法");
                }
                Path target = destination.resolve(packageRoot).resolve(parts[1]).normalize();
                if (!target.startsWith(destination.resolve(packageRoot))) {
                    throw new IllegalArgumentException("候选模型包存在路径穿越");
                }
                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = tar.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        extracted += read;
                        if (extracted > MAX_UNCOMPRESSED_BYTES) {
                            throw new IllegalArgumentException("候选模型包解压后超过大小限制");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
        if (packageRoot == null || !files.equals(REQUIRED_FILES)) {
            throw new IllegalArgumentException("候选模型包缺少必需文件或包含额外文件");
        }
        return destination.resolve(packageRoot);
    }

    private CandidatePackage inspect(Path root, String packageChecksum) throws IOException {
        Map<String, String> checksums = readChecksums(root.resolve("checksums.sha256"));
        for (String filename : CHECKSUMMED_FILES) {
            String expected = checksums.get(filename);
            if (expected == null || !expected.equalsIgnoreCase(sha256(root.resolve(filename)))) {
                throw new IllegalArgumentException("候选模型包 checksum 校验失败：" + filename);
            }
        }
        JsonNode packageManifest = json(root.resolve("package-manifest.json"), "模型包清单");
        JsonNode datasetManifest = json(root.resolve("dataset-manifest.json"), "训练数据集清单");
        JsonNode featureManifest = json(root.resolve("feature-manifest.json"), "特征清单");
        JsonNode metrics = json(root.resolve("metrics.json"), "模型指标");
        JsonNode calibration = json(root.resolve("calibration.json"), "校准信息");
        require("MAOGOU_MODEL_PACKAGE_V1", text(packageManifest, "format"), "模型包格式");
        require("CANDIDATE", text(packageManifest, "requiredRegistrationStatus"), "模型注册状态");
        JsonNode model = object(packageManifest, "model");
        String modelFamily = text(model, "modelFamily");
        String modelKey = text(model, "modelKey");
        String versionNo = text(model, "versionNo");
        String modelType = text(model, "modelType");
        String algorithm = text(model, "algorithm");
        String featureVersion = text(model, "featureVersion");
        String trainerVersion = text(model, "trainerVersion");
        long randomSeed = number(model, "randomSeed");
        int sampleCount = positiveInt(model, "sampleCount");
        require("CANDIDATE", text(model, "status"), "本地模型状态");
        requireSafeIdentifier(modelFamily, "modelFamily");
        requireSafeIdentifier(modelKey, "modelKey");
        requireSafeIdentifier(versionNo, "versionNo");

        require("MAOGOU_DATASET_MANIFEST_V1", text(datasetManifest, "format"), "训练数据集清单格式");
        JsonNode businessKey = object(packageManifest, "datasetBusinessKey");
        JsonNode dataset = object(datasetManifest, "dataset");
        JsonNode sourceSnapshot = object(datasetManifest, "sourceSnapshot");
        String sourceSchemaVersion = text(sourceSnapshot, "schemaVersion");
        require(AiResearchContract.RESEARCH_SCHEMA_VERSION, sourceSchemaVersion, "研究快照 schema 版本");
        String datasetKey = text(businessKey, "datasetKey");
        String datasetVersion = text(businessKey, "versionNo");
        String lineage = text(businessKey, "lineageFingerprint");
        require(datasetKey, text(dataset, "datasetKey"), "训练数据集 key");
        require(datasetVersion, text(dataset, "versionNo"), "训练数据集 version");
        require(lineage, text(dataset, "lineageFingerprint"), "训练数据集血缘");
        require(featureVersion, text(dataset, "featureVersion"), "训练数据集特征版本");
        require("READY", text(dataset, "status"), "本地训练数据集状态");
        if (sampleCount != positiveInt(dataset, "rowCount")) {
            throw new IllegalArgumentException("模型样本数与训练数据集行数不一致");
        }
        require(trainerVersion, text(featureManifest, "trainerVersion"), "特征清单训练器版本");
        if (randomSeed != number(featureManifest, "randomSeed") || !featureManifest.path("features").isArray()
                || featureManifest.path("features").isEmpty()) {
            throw new IllegalArgumentException("特征清单缺少训练版本、随机种子或特征");
        }
        require(trainerVersion, text(metrics, "trainerVersion"), "模型指标训练器版本");
        require(algorithm, text(metrics, "algorithm"), "模型指标算法");
        if (randomSeed != number(metrics, "randomSeed")
                || !metrics.path("artifacts").path("onnxParity").path("verified").asBoolean(false)
                || !metrics.path("artifacts").path("onnxExported").asBoolean(false)
                || !checksums.get("model.onnx").equalsIgnoreCase(metrics.path("artifacts").path("onnxSha256").asText())
                || !checksums.get("model.onnx").equalsIgnoreCase(metrics.path("artifacts").path("modelSha256").asText())
                || !checksums.get("feature-manifest.json").equalsIgnoreCase(
                metrics.path("artifacts").path("featureManifestSha256").asText())) {
            throw new IllegalArgumentException("模型指标缺少有效 ONNX 一致性或产物指纹");
        }
        require(text(calibration, "method"), text(metrics.path("calibration"), "method"), "模型校准方法");
        if (!calibration.path("fitted").asBoolean(false) || !metrics.path("calibration").path("fitted").asBoolean(false)) {
            throw new IllegalArgumentException("模型校准尚未完成");
        }
        return new CandidatePackage(root, packageChecksum, modelFamily, modelKey, versionNo, modelType, algorithm,
                featureVersion, trainerVersion, randomSeed, sampleCount, datasetKey, datasetVersion, lineage,
                checksums.get("model.onnx"), checksums.get("feature-manifest.json"), sourceSchemaVersion,
                metrics, calibration);
    }

    private void verifyTargetSchema(String sourceSchemaVersion) {
        String targetStatus = schemaVersionMapper.selectStatus(sourceSchemaVersion);
        if (!"APPLIED".equals(targetStatus)) {
            throw new IllegalStateException("目标环境未应用兼容的研究 schema，拒绝导入模型包");
        }
    }

    private AiTrainingDataset findProductionDataset(CandidatePackage candidate) {
        AiTrainingDataset dataset = datasetMapper.selectOne(new QueryWrapper<AiTrainingDataset>()
                .eq("dataset_key", candidate.datasetKey())
                .eq("version_no", candidate.datasetVersion())
                .eq("lineage_fingerprint", candidate.datasetLineageFingerprint())
                .eq("model_family", candidate.modelFamily())
                .eq("feature_version", candidate.featureVersion())
                .eq("row_count", candidate.sampleCount())
                .eq("status", "READY"));
        if (dataset == null || dataset.id == null) {
            throw new IllegalArgumentException("生产环境不存在完全匹配的 READY 训练数据集血缘，拒绝导入");
        }
        return dataset;
    }

    private void assertExistingArtifactMatches(Path target, CandidatePackage candidate) throws IOException {
        for (String file : REQUIRED_FILES) {
            Path expected = candidate.root().resolve(file);
            Path actual = target.resolve(file);
            if (!Files.isRegularFile(actual) || !sha256(expected).equalsIgnoreCase(sha256(actual))) {
                throw new IllegalStateException("相同模型包版本已存在不同受控产物，拒绝覆盖");
            }
        }
    }

    private static Map<String, String> readChecksums(Path file) throws IOException {
        Map<String, String> entries = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
            var matcher = CHECKSUM_LINE.matcher(line);
            if (!matcher.matches() || entries.put(matcher.group(2), matcher.group(1)) != null) {
                throw new IllegalArgumentException("候选模型包 checksums.sha256 格式无效");
            }
        }
        if (!entries.keySet().equals(CHECKSUMMED_FILES)) {
            throw new IllegalArgumentException("候选模型包 checksum 文件清单不完整");
        }
        return entries;
    }

    private JsonNode json(Path file, String label) throws IOException {
        JsonNode node = objectMapper.readTree(file.toFile());
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(label + "必须是 JSON 对象");
        }
        return node;
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isObject()) {
            throw new IllegalArgumentException("候选模型包缺少对象字段：" + field);
        }
        return node;
    }

    private static String text(JsonNode parent, String field) {
        String value = parent.path(field).asText();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("候选模型包缺少字段：" + field);
        }
        return value;
    }

    private static long number(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.canConvertToLong()) {
            throw new IllegalArgumentException("候选模型包数值字段无效：" + field);
        }
        return node.asLong();
    }

    private static int positiveInt(JsonNode parent, String field) {
        long value = number(parent, field);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("候选模型包正整数无效：" + field);
        }
        return (int) value;
    }

    private static void require(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(label + "不一致");
        }
    }

    private static void requireSafeIdentifier(String value, String label) {
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "包含不安全字符");
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
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
                    // The original validation or registration failure is more actionable.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup only.
        }
    }

    private record CandidatePackage(
            Path root,
            String packageChecksum,
            String modelFamily,
            String modelKey,
            String versionNo,
            String modelType,
            String algorithm,
            String featureVersion,
            String trainerVersion,
            long randomSeed,
            int sampleCount,
            String datasetKey,
            String datasetVersion,
            String datasetLineageFingerprint,
            String modelChecksum,
            String featureManifestChecksum,
            String sourceSchemaVersion,
            JsonNode metrics,
            JsonNode calibration
    ) {
        Path modelPath() {
            return root.resolve("model.onnx");
        }

        Path featureManifestPath() {
            return root.resolve("feature-manifest.json");
        }

        CandidatePackage relocate(Path newRoot) {
            return new CandidatePackage(newRoot, packageChecksum, modelFamily, modelKey, versionNo, modelType,
                    algorithm, featureVersion, trainerVersion, randomSeed, sampleCount, datasetKey,
                    datasetVersion, datasetLineageFingerprint, modelChecksum, featureManifestChecksum,
                    sourceSchemaVersion, metrics, calibration);
        }
    }
}
