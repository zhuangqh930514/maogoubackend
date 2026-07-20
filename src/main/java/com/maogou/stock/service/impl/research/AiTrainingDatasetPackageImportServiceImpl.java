package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiResearchUniverse;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetImportLineage;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetItem;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetSource;
import com.maogou.stock.mapper.research.AiResearchSchemaVersionMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.service.research.AiTrainingDatasetPackageImportService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Brings a local training dataset into production without trusting local row ids.
 * The archive is only accepted when each row can be resolved to current production
 * point-in-time facts by its immutable fingerprints.
 */
@Service
public class AiTrainingDatasetPackageImportServiceImpl implements AiTrainingDatasetPackageImportService {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,96}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern CHECKSUM_LINE = Pattern.compile("([0-9a-fA-F]{64}) {2}([A-Za-z0-9._-]+)");
    private static final Set<String> REQUIRED_FILES = Set.of(
            "dataset-manifest.json", "dataset-items.jsonl", "dataset.jsonl", "data-card.json",
            "package-manifest.json", "checksums.sha256");
    private static final Set<String> CHECKSUMMED_FILES = Set.of(
            "dataset-manifest.json", "dataset-items.jsonl", "dataset.jsonl", "data-card.json",
            "package-manifest.json");
    private static final long MAX_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_ARCHIVE_ENTRIES = 16;
    private static final int MAX_ROWS = 1_000_000;
    private static final int INSERT_BATCH_SIZE = 500;
    private static final int MAX_REJECTIONS = 20;

    private final AppProperties properties;
    private final AiTrainingDatasetMapper datasetMapper;
    private final AiTrainingDatasetItemMapper itemMapper;
    private final AiResearchUniverseMapper universeMapper;
    private final AiResearchSchemaVersionMapper schemaVersionMapper;
    private final ObjectMapper objectMapper;

    public AiTrainingDatasetPackageImportServiceImpl(
            AppProperties properties,
            AiTrainingDatasetMapper datasetMapper,
            AiTrainingDatasetItemMapper itemMapper,
            AiResearchUniverseMapper universeMapper,
            AiResearchSchemaVersionMapper schemaVersionMapper,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.datasetMapper = datasetMapper;
        this.itemMapper = itemMapper;
        this.universeMapper = universeMapper;
        this.schemaVersionMapper = schemaVersionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public PreviewResult preview(MultipartFile packageFile, Long operatorUserId) {
        return inspectUpload(packageFile, operatorUserId).preview();
    }

    @Override
    @Transactional
    public ImportResult importPackage(MultipartFile packageFile, Long operatorUserId) {
        InspectedPackage inspected = inspectUpload(packageFile, operatorUserId);
        PreviewResult preview = inspected.preview();
        if (!preview.compatible()) {
            throw new IllegalArgumentException("训练数据包预检未通过：匹配 " + preview.matchedRows()
                    + " / " + preview.declaredRows() + "，请先修复生产事实数据");
        }
        AiTrainingDataset existing = datasetMapper.selectByVersionForShare(
                inspected.dataset().datasetKey(), inspected.dataset().versionNo());
        if (existing != null) {
            verifyExisting(existing, inspected.dataset());
            return new ImportResult(existing.id, existing.datasetKey, existing.versionNo,
                    existing.lineageFingerprint, existing.status, inspected.packageChecksum(),
                    existing.rowCount, true);
        }

        AiResearchUniverse universe = universeMapper.selectOne(new QueryWrapper<AiResearchUniverse>()
                .eq("universe_code", inspected.dataset().universeCode())
                .eq("enabled", 1));
        if (universe == null || universe.id == null) {
            throw new IllegalArgumentException("生产环境不存在启用的研究股票池：" + inspected.dataset().universeCode());
        }

        Path published = null;
        boolean publishedByThisImport = false;
        try {
            Path target = datasetArtifactTarget(inspected);
            if (Files.exists(target)) {
                assertExistingArtifactMatches(target, inspected.root());
            } else {
                Files.createDirectories(target.getParent());
                moveAtomically(inspected.root(), target);
                published = target;
                publishedByThisImport = true;
            }

            AiTrainingDataset expected = dataset(inspected.dataset(), universe.id, target, inspected.packageChecksum());
            datasetMapper.insertImmutable(expected);
            AiTrainingDataset persisted = datasetMapper.selectByVersionForShare(expected.datasetKey, expected.versionNo);
            if (persisted == null || persisted.id == null) {
                throw new IllegalStateException("训练数据集写入后未读取到记录");
            }
            verifyExisting(persisted, inspected.dataset());
            List<AiTrainingDatasetItem> items = resolvedItems(persisted.id, inspected.resolved(), expected.createdAt);
            for (int start = 0; start < items.size(); start += INSERT_BATCH_SIZE) {
                itemMapper.insertBatchImmutable(items.subList(start, Math.min(start + INSERT_BATCH_SIZE, items.size())));
            }
            List<AiTrainingDatasetItem> persistedItems = itemMapper.selectByDatasetForShare(persisted.id);
            if (persistedItems == null || persistedItems.size() != items.size()) {
                throw new IllegalStateException("训练数据集明细写入数量不一致");
            }
            return new ImportResult(persisted.id, persisted.datasetKey, persisted.versionNo,
                    persisted.lineageFingerprint, persisted.status, inspected.packageChecksum(),
                    persisted.rowCount, false);
        } catch (IOException | RuntimeException exception) {
            if (publishedByThisImport) {
                deleteRecursively(published);
            }
            if (exception instanceof IOException ioException) {
                throw new IllegalStateException("训练数据包导入产物失败", ioException);
            }
            throw (RuntimeException) exception;
        } finally {
            deleteRecursively(inspected.staging());
        }
    }

    private InspectedPackage inspectUpload(MultipartFile packageFile, Long operatorUserId) {
        validateUpload(packageFile, operatorUserId);
        Path staging = null;
        try {
            Path root = artifactRoot();
            staging = Files.createTempDirectory(root.resolve(".dataset-package-staging"), "upload-");
            Path archive = staging.resolve("dataset.tar.gz");
            copyUpload(packageFile, archive, properties.getScheduler().getModelPackageMaxBytes());
            String packageChecksum = sha256(archive);
            Path unpacked = unpack(archive, staging.resolve("unpacked"));
            PackageDataset dataset = inspect(unpacked);
            verifyTargetSchema(dataset.sourceSchemaVersion());
            Resolution resolution = resolve(dataset);
            boolean alreadyImported = isAlreadyImported(dataset);
            PreviewResult preview = new PreviewResult(dataset.datasetKey(), dataset.versionNo(),
                    dataset.lineageFingerprint(), packageChecksum, dataset.items().size(), resolution.matched().size(),
                    resolution.rejectedCount(), resolution.rejectedCount() == 0, alreadyImported,
                    List.copyOf(resolution.rejections()));
            return new InspectedPackage(staging, unpacked, packageChecksum, dataset, resolution.matched(), preview);
        } catch (IOException | RuntimeException exception) {
            deleteRecursively(staging);
            if (exception instanceof IOException ioException) {
                throw new IllegalStateException("训练数据包预检失败", ioException);
            }
            throw (RuntimeException) exception;
        }
    }

    private Resolution resolve(PackageDataset dataset) {
        List<ResolvedItem> matched = new ArrayList<>();
        List<Rejection> rejections = new ArrayList<>();
        int rejectedCount = 0;
        for (PackageItem item : dataset.items()) {
            List<AiTrainingDatasetSource> sources = itemMapper.selectByImportLineage(item.lineage(dataset));
            if (sources == null || sources.size() != 1 || sources.get(0).sampleId == null
                    || sources.get(0).sampleLabelId == null) {
                rejectedCount++;
                if (rejections.size() < MAX_REJECTIONS) {
                    rejections.add(new Rejection(item.lineNumber(), sources == null || sources.isEmpty()
                            ? "生产环境缺少匹配的样本、标签或时间点事实"
                            : "生产环境存在多个匹配事实，血缘不唯一"));
                }
                continue;
            }
            AiTrainingDatasetSource source = sources.get(0);
            if (!Objects.equals(source.universeFingerprint, item.universeFingerprint())
                    || !Objects.equals(source.featureFingerprint, item.featureFingerprint())
                    || !Objects.equals(source.labelFingerprint, item.labelFingerprint())
                    || !Objects.equals(source.tradingStateFingerprint, item.tradingStateFingerprint())
                    || !Objects.equals(source.sectorMembershipFingerprint, item.sectorMembershipFingerprint())) {
                rejectedCount++;
                if (rejections.size() < MAX_REJECTIONS) {
                    rejections.add(new Rejection(item.lineNumber(), "生产事实指纹与数据包不一致"));
                }
                continue;
            }
            matched.add(new ResolvedItem(item, source));
        }
        return new Resolution(List.copyOf(matched), rejectedCount, List.copyOf(rejections));
    }

    private PackageDataset inspect(Path root) throws IOException {
        Map<String, String> checksums = readChecksums(root.resolve("checksums.sha256"));
        for (String name : CHECKSUMMED_FILES) {
            if (!Objects.equals(checksums.get(name), sha256(root.resolve(name)))) {
                throw new IllegalArgumentException("训练数据包 checksum 校验失败：" + name);
            }
        }
        JsonNode packageManifest = json(root.resolve("package-manifest.json"), "训练数据包清单");
        require("MAOGOU_TRAINING_DATASET_PACKAGE_V1", text(packageManifest, "format"), "训练数据包格式");
        String sourceSchemaVersion = safeIdentifier(text(packageManifest, "sourceSchemaVersion"), "来源 schema 版本");
        JsonNode manifest = json(root.resolve("dataset-manifest.json"), "训练数据集清单");
        require("MAOGOU_TRAINING_DATASET_MANIFEST_V1", text(manifest, "format"), "训练数据集清单格式");
        JsonNode dataset = object(manifest, "dataset");
        JsonNode universe = object(manifest, "researchUniverse");
        JsonNode sourceSnapshot = object(manifest, "sourceSnapshot");
        require(sourceSchemaVersion, text(sourceSnapshot, "schemaVersion"), "来源快照 schema 版本");
        List<PackageItem> items = items(root.resolve("dataset-items.jsonl"), dataset);
        int declared = positiveInt(dataset, "rowCount");
        if (items.size() != declared || lines(root.resolve("dataset.jsonl")) != declared) {
            throw new IllegalArgumentException("训练数据包行数与数据集清单不一致");
        }
        String artifactChecksum = text(dataset, "artifactChecksum");
        if (!SHA256.matcher(artifactChecksum).matches()
                || !artifactChecksum.equalsIgnoreCase(checksums.get("dataset.jsonl"))) {
            throw new IllegalArgumentException("训练数据集产物 checksum 不一致");
        }
        PackageDataset parsed = new PackageDataset(sourceSchemaVersion,
                safeIdentifier(text(dataset, "datasetKey"), "datasetKey"),
                safeIdentifier(text(dataset, "versionNo"), "versionNo"),
                safeIdentifier(text(dataset, "modelFamily"), "modelFamily"),
                text(dataset, "purpose"), text(dataset, "featureVersion"), text(dataset, "labelVersion"),
                text(dataset, "calendarVersion"), dateTime(dataset, "asOfTime"),
                date(dataset, "trainStartDate"), date(dataset, "trainEndDate"),
                date(dataset, "validationStartDate"), date(dataset, "validationEndDate"),
                date(dataset, "testStartDate"), date(dataset, "testEndDate"),
                positiveInt(dataset, "maxHorizonDays"), nonNegativeInt(dataset, "purgeTradingDays"),
                nonNegativeInt(dataset, "embargoTradingDays"), checksum(text(dataset, "lineageFingerprint"), "lineageFingerprint"),
                checksum(artifactChecksum, "artifactChecksum"), safeIdentifier(text(universe, "universeCode"), "universeCode"),
                items, sourceSnapshot);
        validateDatasetWindows(parsed);
        return parsed;
    }

    private List<PackageItem> items(Path file, JsonNode dataset) throws IOException {
        List<PackageItem> result = new ArrayList<>();
        Set<String> rows = new LinkedHashSet<>();
        Set<String> sequences = new LinkedHashSet<>();
        try (BufferedReader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = input.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    throw new IllegalArgumentException("训练数据包明细不允许空行：" + lineNumber);
                }
                if (result.size() >= MAX_ROWS) {
                    throw new IllegalArgumentException("训练数据包明细超过行数限制");
                }
                JsonNode node = objectMapper.readTree(line);
                if (node == null || !node.isObject() || node.has("sampleId") || node.has("labelId")) {
                    throw new IllegalArgumentException("训练数据包明细格式无效或携带本地 id：" + lineNumber);
                }
                String split = text(node, "splitType");
                if (!Set.of("TRAIN", "VALIDATION", "TEST").contains(split)) {
                    throw new IllegalArgumentException("训练数据包 splitType 无效：" + lineNumber);
                }
                int sequence = positiveInt(node, "sequenceNo");
                PackageItem item = new PackageItem(lineNumber, split, sequence,
                        dateTime(node, "sampleAsOfTime"), dateTime(node, "labelAvailableAt"),
                        checksum(text(node, "featureFingerprint"), "featureFingerprint"),
                        checksum(text(node, "labelFingerprint"), "labelFingerprint"),
                        checksum(text(node, "universeFingerprint"), "universeFingerprint"),
                        checksum(text(node, "tradingStateFingerprint"), "tradingStateFingerprint"),
                        checksum(text(node, "sectorMembershipFingerprint"), "sectorMembershipFingerprint"));
                if (!rows.add(item.fingerprintKey()) || !sequences.add(split + ":" + sequence)) {
                    throw new IllegalArgumentException("训练数据包明细存在重复血缘或序号：" + lineNumber);
                }
                if (item.sampleAsOfTime().isAfter(item.labelAvailableAt())) {
                    throw new IllegalArgumentException("训练数据包明细时间顺序无效：" + lineNumber);
                }
                result.add(item);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("训练数据包明细为空");
        }
        return List.copyOf(result);
    }

    private static List<AiTrainingDatasetItem> resolvedItems(
            Long datasetId, List<ResolvedItem> resolved, LocalDateTime now
    ) {
        List<AiTrainingDatasetItem> items = new ArrayList<>(resolved.size());
        for (ResolvedItem resolvedItem : resolved) {
            PackageItem item = resolvedItem.item();
            AiTrainingDatasetSource source = resolvedItem.source();
            AiTrainingDatasetItem value = new AiTrainingDatasetItem();
            value.trainingDatasetId = datasetId;
            value.sampleId = source.sampleId;
            value.sampleLabelId = source.sampleLabelId;
            value.splitType = item.splitType();
            value.sequenceNo = item.sequenceNo();
            value.sampleAsOfTime = item.sampleAsOfTime();
            value.labelAvailableAt = item.labelAvailableAt();
            value.featureFingerprint = item.featureFingerprint();
            value.labelFingerprint = item.labelFingerprint();
            value.universeFingerprint = item.universeFingerprint();
            value.tradingStateFingerprint = item.tradingStateFingerprint();
            value.sectorMembershipFingerprint = item.sectorMembershipFingerprint();
            value.includedAt = now;
            value.createdAt = now;
            items.add(value);
        }
        return items;
    }

    private static void validateDatasetWindows(PackageDataset value) {
        if (value.trainStartDate().isAfter(value.trainEndDate())
                || !value.trainEndDate().isBefore(value.validationStartDate())
                || value.validationStartDate().isAfter(value.validationEndDate())
                || !value.validationEndDate().isBefore(value.testStartDate())
                || value.testStartDate().isAfter(value.testEndDate())
                || value.testEndDate().isAfter(value.asOfTime().toLocalDate())
                || value.purgeTradingDays() < 5 || value.embargoTradingDays() < 5) {
            throw new IllegalArgumentException("训练数据包时间窗口、隔离窗口或可见性截止时间无效");
        }
    }

    private AiTrainingDataset dataset(PackageDataset source, Long universeId, Path artifactRoot, String packageChecksum)
            throws IOException {
        AiTrainingDataset value = new AiTrainingDataset();
        value.researchUniverseId = universeId;
        value.datasetKey = source.datasetKey();
        value.versionNo = source.versionNo();
        value.modelFamily = source.modelFamily();
        value.purpose = source.purpose();
        value.featureVersion = source.featureVersion();
        value.labelVersion = source.labelVersion();
        value.calendarVersion = source.calendarVersion();
        value.asOfTime = source.asOfTime();
        value.trainStartDate = source.trainStartDate();
        value.trainEndDate = source.trainEndDate();
        value.validationStartDate = source.validationStartDate();
        value.validationEndDate = source.validationEndDate();
        value.testStartDate = source.testStartDate();
        value.testEndDate = source.testEndDate();
        value.maxHorizonDays = source.maxHorizonDays();
        value.purgeTradingDays = source.purgeTradingDays();
        value.embargoTradingDays = source.embargoTradingDays();
        value.sourceQueryJson = objectMapper.writeValueAsString(Map.of(
                "format", "MAOGOU_IMPORTED_TRAINING_DATASET_V1",
                "sourceSchemaVersion", source.sourceSchemaVersion(),
                "sourceSnapshot", source.sourceSnapshot()));
        value.selectionPolicyJson = objectMapper.writeValueAsString(Map.of(
                "format", "MAOGOU_IMPORTED_TRAINING_DATASET_V1",
                "dataCardUri", artifactRoot.resolve("data-card.json").toUri().toString(),
                "dataCardChecksum", sha256(artifactRoot.resolve("data-card.json")),
                "packageChecksum", packageChecksum));
        value.lineageFingerprint = source.lineageFingerprint();
        value.artifactUri = artifactRoot.resolve("dataset.jsonl").toUri().toString();
        value.artifactChecksum = source.artifactChecksum();
        value.rowCount = source.items().size();
        value.status = "READY";
        value.finalizedAt = source.asOfTime();
        value.createdAt = LocalDateTime.now();
        return value;
    }

    private void verifyTargetSchema(String sourceSchemaVersion) {
        if (!"APPLIED".equals(schemaVersionMapper.selectStatus(sourceSchemaVersion))) {
            throw new IllegalStateException("目标环境未应用兼容的研究 schema，拒绝导入训练数据包");
        }
    }

    private boolean isAlreadyImported(PackageDataset source) {
        AiTrainingDataset existing = datasetMapper.selectOne(new QueryWrapper<AiTrainingDataset>()
                .eq("dataset_key", source.datasetKey()).eq("version_no", source.versionNo()));
        return existing != null && Objects.equals(existing.lineageFingerprint, source.lineageFingerprint());
    }

    private static void verifyExisting(AiTrainingDataset existing, PackageDataset source) {
        if (!Objects.equals(existing.lineageFingerprint, source.lineageFingerprint())
                || !Objects.equals(existing.modelFamily, source.modelFamily())
                || !Objects.equals(existing.featureVersion, source.featureVersion())
                || !Objects.equals(existing.labelVersion, source.labelVersion())
                || !Objects.equals(existing.calendarVersion, source.calendarVersion())
                || !Objects.equals(existing.rowCount, source.items().size())
                || !"READY".equals(existing.status)) {
            throw new IllegalStateException("不可变训练数据集冲突：" + source.datasetKey() + "/" + source.versionNo());
        }
    }

    private Path artifactRoot() throws IOException {
        Path root = Path.of(properties.getScheduler().getTrainingArtifactRoot()).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve(".dataset-package-staging"));
        return root;
    }

    private Path datasetArtifactTarget(InspectedPackage inspected) throws IOException {
        Path root = artifactRoot().resolve("imported-datasets").resolve(inspected.dataset().modelFamily())
                .resolve(inspected.dataset().datasetKey()).resolve(inspected.dataset().versionNo())
                .resolve(inspected.packageChecksum());
        return root.toAbsolutePath().normalize();
    }

    private void validateUpload(MultipartFile file, Long operatorUserId) {
        if (operatorUserId == null || operatorUserId <= 0) {
            throw new IllegalArgumentException("缺少研究运维操作者");
        }
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase().endsWith(".tar.gz")) {
            throw new IllegalArgumentException("请上传非空 .tar.gz 训练数据包");
        }
        long maxBytes = properties.getScheduler().getModelPackageMaxBytes();
        if (maxBytes <= 0 || file.getSize() > maxBytes) {
            throw new IllegalArgumentException("训练数据包超过大小限制");
        }
    }

    private static void copyUpload(MultipartFile file, Path target, long maxBytes) throws IOException {
        long copied = 0;
        try (InputStream source = file.getInputStream(); var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                copied += read;
                if (copied > maxBytes) {
                    throw new IllegalArgumentException("训练数据包超过大小限制");
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
                    throw new IllegalArgumentException("训练数据包包含不安全的归档条目");
                }
                String name = entry.getName();
                if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\\\")) {
                    throw new IllegalArgumentException("训练数据包路径无效");
                }
                String[] parts = name.split("/");
                if (Arrays.stream(parts).anyMatch(part -> part.isBlank() || ".".equals(part) || "..".equals(part))) {
                    throw new IllegalArgumentException("训练数据包存在路径穿越");
                }
                if (packageRoot == null) {
                    packageRoot = parts[0];
                    safeIdentifier(packageRoot, "训练数据包目录");
                }
                if (!packageRoot.equals(parts[0])) {
                    throw new IllegalArgumentException("训练数据包只能包含一个根目录");
                }
                if (entry.isDirectory()) {
                    if (parts.length != 1) {
                        throw new IllegalArgumentException("训练数据包不允许嵌套目录");
                    }
                    Files.createDirectories(destination.resolve(packageRoot));
                    continue;
                }
                if (!entry.isFile() || parts.length != 2 || !REQUIRED_FILES.contains(parts[1]) || !files.add(parts[1])) {
                    throw new IllegalArgumentException("训练数据包文件清单不合法");
                }
                Path target = destination.resolve(packageRoot).resolve(parts[1]).normalize();
                if (!target.startsWith(destination.resolve(packageRoot))) {
                    throw new IllegalArgumentException("训练数据包存在路径穿越");
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
                            throw new IllegalArgumentException("训练数据包解压后超过大小限制");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
        if (packageRoot == null || !files.equals(REQUIRED_FILES)) {
            throw new IllegalArgumentException("训练数据包缺少必需文件或包含额外文件");
        }
        return destination.resolve(packageRoot);
    }

    private static Map<String, String> readChecksums(Path file) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
            var matcher = CHECKSUM_LINE.matcher(line);
            if (!matcher.matches() || values.put(matcher.group(2), matcher.group(1).toLowerCase()) != null) {
                throw new IllegalArgumentException("训练数据包 checksums.sha256 格式无效");
            }
        }
        if (!values.keySet().equals(CHECKSUMMED_FILES)) {
            throw new IllegalArgumentException("训练数据包 checksum 文件清单不完整");
        }
        return values;
    }

    private JsonNode json(Path path, String label) throws IOException {
        JsonNode value = objectMapper.readTree(path.toFile());
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(label + "必须是 JSON 对象");
        }
        return value;
    }

    private static int lines(Path path) throws IOException {
        try (var input = Files.lines(path, StandardCharsets.UTF_8)) {
            return Math.toIntExact(input.count());
        }
    }

    private static JsonNode object(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        if (!value.isObject()) {
            throw new IllegalArgumentException("训练数据包缺少对象字段：" + name);
        }
        return value;
    }

    private static String text(JsonNode parent, String name) {
        String value = parent.path(name).asText();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("训练数据包缺少字段：" + name);
        }
        return value.trim();
    }

    private static int positiveInt(JsonNode parent, String name) {
        long value = number(parent, name);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("训练数据包正整数无效：" + name);
        }
        return (int) value;
    }

    private static int nonNegativeInt(JsonNode parent, String name) {
        long value = number(parent, name);
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("训练数据包非负整数无效：" + name);
        }
        return (int) value;
    }

    private static long number(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        if (!value.canConvertToLong()) {
            throw new IllegalArgumentException("训练数据包数值字段无效：" + name);
        }
        return value.asLong();
    }

    private static LocalDate date(JsonNode parent, String name) {
        try {
            return LocalDate.parse(text(parent, name));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("训练数据包日期字段无效：" + name);
        }
    }

    private static LocalDateTime dateTime(JsonNode parent, String name) {
        try {
            return LocalDateTime.parse(text(parent, name));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("训练数据包时间字段无效：" + name);
        }
    }

    private static String checksum(String value, String label) {
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("训练数据包 " + label + "无效");
        }
        return value.toLowerCase();
    }

    private static String safeIdentifier(String value, String label) {
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "包含不安全字符");
        }
        return value;
    }

    private static void require(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(label + "不一致");
        }
    }

    private static void assertExistingArtifactMatches(Path target, Path unpacked) throws IOException {
        for (String file : REQUIRED_FILES) {
            Path expected = unpacked.resolve(file);
            Path actual = target.resolve(file);
            if (!Files.isRegularFile(actual) || !sha256(expected).equalsIgnoreCase(sha256(actual))) {
                throw new IllegalStateException("相同训练数据包版本已存在不同受控产物，拒绝覆盖");
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
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

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var files = Files.walk(path)) {
            files.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException ignored) {
                    // Best effort staging cleanup; database state is still transactionally protected.
                }
            });
        } catch (IOException ignored) {
            // Best effort staging cleanup.
        }
    }

    private record PackageDataset(
            String sourceSchemaVersion,
            String datasetKey,
            String versionNo,
            String modelFamily,
            String purpose,
            String featureVersion,
            String labelVersion,
            String calendarVersion,
            LocalDateTime asOfTime,
            LocalDate trainStartDate,
            LocalDate trainEndDate,
            LocalDate validationStartDate,
            LocalDate validationEndDate,
            LocalDate testStartDate,
            LocalDate testEndDate,
            int maxHorizonDays,
            int purgeTradingDays,
            int embargoTradingDays,
            String lineageFingerprint,
            String artifactChecksum,
            String universeCode,
            List<PackageItem> items,
            JsonNode sourceSnapshot
    ) {
    }

    private record PackageItem(
            int lineNumber,
            String splitType,
            int sequenceNo,
            LocalDateTime sampleAsOfTime,
            LocalDateTime labelAvailableAt,
            String featureFingerprint,
            String labelFingerprint,
            String universeFingerprint,
            String tradingStateFingerprint,
            String sectorMembershipFingerprint
    ) {
        AiTrainingDatasetImportLineage lineage(PackageDataset dataset) {
            AiTrainingDatasetImportLineage value = new AiTrainingDatasetImportLineage();
            value.featureFingerprint = featureFingerprint;
            value.labelFingerprint = labelFingerprint;
            value.universeFingerprint = universeFingerprint;
            value.tradingStateFingerprint = tradingStateFingerprint;
            value.sectorMembershipFingerprint = sectorMembershipFingerprint;
            value.sampleAsOfTime = sampleAsOfTime;
            value.labelAvailableAt = labelAvailableAt;
            value.featureVersion = dataset.featureVersion;
            value.labelVersion = dataset.labelVersion;
            value.calendarVersion = dataset.calendarVersion;
            value.horizonTradingDays = dataset.maxHorizonDays;
            return value;
        }

        String fingerprintKey() {
            return String.join(":", featureFingerprint, labelFingerprint, universeFingerprint,
                    tradingStateFingerprint, sectorMembershipFingerprint);
        }
    }

    private record ResolvedItem(PackageItem item, AiTrainingDatasetSource source) {
    }

    private record Resolution(List<ResolvedItem> matched, int rejectedCount, List<Rejection> rejections) {
    }

    private record InspectedPackage(
            Path staging,
            Path root,
            String packageChecksum,
            PackageDataset dataset,
            List<ResolvedItem> resolved,
            PreviewResult preview
    ) {
    }
}
