package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetItem;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetSource;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetSourceQuery;
import com.maogou.stock.mapper.research.AiModelVersionMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.service.research.AiTrainingDatasetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AiTrainingDatasetServiceImpl implements AiTrainingDatasetService {

    private static final Map<String, Integer> SPLIT_ORDER = Map.of(
            "TRAIN", 1, "VALIDATION", 2, "TEST", 3);

    private final AiTrainingDatasetMapper datasetMapper;
    private final AiTrainingDatasetItemMapper itemMapper;
    private final AiModelVersionMapper modelMapper;
    private final ObjectMapper objectMapper;

    public AiTrainingDatasetServiceImpl(
            AiTrainingDatasetMapper datasetMapper,
            AiTrainingDatasetItemMapper itemMapper,
            AiModelVersionMapper modelMapper,
            ObjectMapper objectMapper
    ) {
        this.datasetMapper = datasetMapper;
        this.itemMapper = itemMapper;
        this.modelMapper = modelMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public DatasetBuildResult buildDataset(DatasetBuildRequest request) {
        validateBuildRequest(request);
        AiTrainingDatasetSourceQuery query = sourceQuery(request);
        List<AiTrainingDatasetSource> sources = itemMapper.selectEligibleSources(query);
        List<SelectedSource> selected = selectSources(request, sources == null ? List.of() : sources);
        String sourceQueryJson = json(sourceQueryEvidence(query));
        String selectionPolicyJson = json(selectionPolicyEvidence());
        String lineageFingerprint = lineageFingerprint(request, selected, sourceQueryJson, selectionPolicyJson);
        String artifact = artifact(selected);
        String artifactChecksum = sha256(artifact);
        writeArtifact(request.artifactPath(), artifact);

        AiTrainingDataset expected = dataset(request, sourceQueryJson, selectionPolicyJson,
                lineageFingerprint, artifactChecksum, selected.size());
        datasetMapper.insertImmutable(expected);
        AiTrainingDataset persisted = datasetMapper.selectByVersionForShare(
                request.userId(), request.datasetKey(), request.versionNo());
        validatePersistedDataset(expected, persisted);

        List<AiTrainingDatasetItem> expectedItems = datasetItems(persisted.id, selected, request.asOfTime());
        if (!expectedItems.isEmpty()) {
            itemMapper.insertBatchImmutable(expectedItems);
        }
        List<AiTrainingDatasetItem> persistedItems = itemMapper.selectByDatasetForShare(persisted.id);
        validatePersistedItems(expectedItems, persistedItems);
        return new DatasetBuildResult(persisted, List.copyOf(persistedItems));
    }

    @Override
    @Transactional
    public AiModelVersion registerModel(ModelRegistration registration) {
        validateModelRegistration(registration);
        AiTrainingDataset dataset = datasetMapper.selectById(registration.trainingDatasetId());
        if (dataset == null || !Objects.equals(dataset.userId, registration.userId())) {
            throw new IllegalArgumentException("trainingDatasetId 对应的数据集不存在或不属于当前用户");
        }
        if (!"READY".equals(dataset.status)) {
            throw new IllegalStateException("模型只能关联 READY 训练数据集");
        }
        if (!Objects.equals(dataset.featureVersion, registration.featureVersion())) {
            throw new IllegalArgumentException("模型 featureVersion 必须与训练数据集血缘一致");
        }
        boolean verifiedQualityGate = verifyModelArtifacts(registration, dataset);
        AiModelVersion expected = modelVersion(registration, dataset, verifiedQualityGate);
        modelMapper.insertImmutable(expected);
        AiModelVersion persisted = modelMapper.selectByVersionForShare(
                registration.userId(), registration.modelKey(), registration.versionNo());
        if (persisted == null) {
            throw new IllegalStateException("模型版本写入后未读取到记录");
        }
        if (!sameModelVersion(expected, persisted)) {
            throw new IllegalStateException("不可变模型版本冲突：" + registration.modelKey()
                    + "/" + registration.versionNo());
        }
        return persisted;
    }

    private List<SelectedSource> selectSources(
            DatasetBuildRequest request,
            List<AiTrainingDatasetSource> sources
    ) {
        List<SelectedSource> selected = new ArrayList<>();
        Set<String> lineageKeys = new HashSet<>();
        for (AiTrainingDatasetSource source : sources) {
            validateSource(request, source, lineageKeys);
            String split = split(request, source.tradeDate);
            if (split == null || !labelAvailableBeforeBoundary(request, source, split)) {
                continue;
            }
            JsonNode features = parseFeatures(source);
            rejectFutureFeatureEvidence(features, source.sampleAsOfTime, "features");
            selected.add(new SelectedSource(source, split, features));
        }
        selected.sort(Comparator
                .comparingInt((SelectedSource value) -> SPLIT_ORDER.get(value.split()))
                .thenComparing(value -> value.source().tradeDate)
                .thenComparing(value -> value.source().stockCode)
                .thenComparing(value -> value.source().sampleId)
                .thenComparing(value -> value.source().labelId));
        return selected;
    }

    private void validateSource(
            DatasetBuildRequest request,
            AiTrainingDatasetSource source,
            Set<String> lineageKeys
    ) {
        if (source == null || source.sampleId == null || source.labelId == null
                || source.userId == null || source.stockCode == null || source.stockCode.isBlank()
                || source.tradeDate == null || source.sampleAsOfTime == null
                || source.labelAvailableAt == null || source.featureSnapshot == null
                || source.featureFingerprint == null || source.featureFingerprint.isBlank()
                || source.labelFingerprint == null || source.labelFingerprint.isBlank()
                || source.horizonDays == null || source.horizonDays <= 0
                || !Objects.equals(request.userId(), source.userId)
                || !Objects.equals(request.featureVersion(), source.featureVersion)
                || !Objects.equals(request.labelVersion(), source.labelVersion)
                || !Objects.equals(request.calendarVersion(), source.calendarVersion)
                || !Objects.equals(source.horizonDays, request.maxHorizonDays())
                || source.sampleAsOfTime.isAfter(request.asOfTime())
                || source.labelAvailableAt.isAfter(request.asOfTime())) {
            throw new IllegalArgumentException("训练来源缺少不可变样本、成熟标签、目标周期或版本血缘");
        }
        if (!lineageKeys.add(source.sampleId + ":" + source.labelId)) {
            throw new IllegalArgumentException("训练来源包含重复样本标签血缘");
        }
    }

    private JsonNode parseFeatures(AiTrainingDatasetSource source) {
        try {
            return objectMapper.readTree(source.featureSnapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("样本特征快照不是有效 JSON：" + source.sampleId, ex);
        }
    }

    private void rejectFutureFeatureEvidence(JsonNode node, LocalDateTime sampleAsOf, String path) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String childPath = path + "." + entry.getKey();
                if (entry.getValue().isTextual() && isTemporalField(entry.getKey())
                        && afterSampleTime(entry.getValue().asText(), sampleAsOf)) {
                    throw new IllegalArgumentException("检测到未来特征：" + childPath);
                }
                rejectFutureFeatureEvidence(entry.getValue(), sampleAsOf, childPath);
            });
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                rejectFutureFeatureEvidence(node.get(index), sampleAsOf, path + "[" + index + "]");
            }
        }
    }

    private static boolean isTemporalField(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.endsWith("time") || normalized.endsWith("date")
                || normalized.endsWith("at") || normalized.endsWith("timestamp");
    }

    private static boolean afterSampleTime(String value, LocalDateTime sampleAsOf) {
        try {
            return LocalDateTime.parse(value).isAfter(sampleAsOf);
        } catch (DateTimeException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant().isAfter(sampleAsOf.toInstant(java.time.ZoneOffset.UTC));
            } catch (DateTimeException ignoredOffset) {
                try {
                    return Instant.parse(value).isAfter(sampleAsOf.toInstant(java.time.ZoneOffset.UTC));
                } catch (DateTimeException ignoredInstant) {
                    try {
                        return LocalDate.parse(value).isAfter(sampleAsOf.toLocalDate());
                    } catch (DateTimeException ignoredDate) {
                        return false;
                    }
                }
            }
        }
    }

    private String artifact(List<SelectedSource> sources) {
        StringBuilder output = new StringBuilder();
        for (SelectedSource selected : sources) {
            AiTrainingDatasetSource source = selected.source();
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("horizonDays", source.horizonDays);
            target.put("netReturn", source.netReturn);
            target.put("excessReturn", source.excessReturn);
            target.put("labelScore", source.labelScore);
            target.put("hitDirection", source.hitDirection);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sampleId", source.sampleId);
            row.put("labelId", source.labelId);
            row.put("stockCode", source.stockCode);
            row.put("tradeDate", source.tradeDate.toString());
            row.put("sampleAsOfTime", source.sampleAsOfTime.toString());
            row.put("labelAvailableAt", source.labelAvailableAt.toString());
            row.put("split", selected.split());
            row.put("featureFingerprint", source.featureFingerprint);
            row.put("labelFingerprint", source.labelFingerprint);
            row.put("features", selected.features());
            row.put("target", target);
            output.append(json(row)).append('\n');
        }
        return output.toString();
    }

    private static void writeArtifact(Path path, String content) {
        Path temporary = null;
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Path parent = absolute.getParent() == null ? Path.of(".").toAbsolutePath().normalize() : absolute.getParent();
            Files.createDirectories(parent);
            Path lockPath = parent.resolve("." + absolute.getFileName() + ".lock");
            try (FileChannel lockChannel = FileChannel.open(
                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                if (Files.exists(absolute)) {
                    String existing = Files.readString(absolute, StandardCharsets.UTF_8);
                    if (!Objects.equals(sha256(existing), sha256(content))) {
                        throw new IllegalStateException("不可变训练数据产物已存在且内容不同：" + absolute);
                    }
                    return;
                }
                temporary = Files.createTempFile(parent, "." + absolute.getFileName() + ".", ".tmp");
                try (FileChannel output = FileChannel.open(
                        temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.encode(content);
                    while (encoded.hasRemaining()) {
                        output.write(encoded);
                    }
                    output.force(true);
                }
                try {
                    Files.move(temporary, absolute,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
                temporary = null;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法写入训练数据产物", ex);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A complete target is already protected by the lock; stale temp cleanup can be retried later.
                }
            }
        }
    }

    private AiTrainingDataset dataset(
            DatasetBuildRequest request,
            String sourceQueryJson,
            String selectionPolicyJson,
            String lineageFingerprint,
            String artifactChecksum,
            int rowCount
    ) {
        AiTrainingDataset dataset = new AiTrainingDataset();
        dataset.userId = request.userId();
        dataset.datasetKey = request.datasetKey();
        dataset.versionNo = request.versionNo();
        dataset.purpose = request.purpose();
        dataset.featureVersion = request.featureVersion();
        dataset.labelVersion = request.labelVersion();
        dataset.calendarVersion = request.calendarVersion();
        dataset.asOfTime = request.asOfTime();
        dataset.trainStartDate = request.trainStartDate();
        dataset.trainEndDate = request.trainEndDate();
        dataset.validationStartDate = request.validationStartDate();
        dataset.validationEndDate = request.validationEndDate();
        dataset.testStartDate = request.testStartDate();
        dataset.testEndDate = request.testEndDate();
        dataset.maxHorizonDays = request.maxHorizonDays();
        dataset.sourceQueryJson = sourceQueryJson;
        dataset.selectionPolicyJson = selectionPolicyJson;
        dataset.lineageFingerprint = lineageFingerprint;
        dataset.artifactUri = request.artifactPath().toAbsolutePath().normalize().toUri().toString();
        dataset.artifactChecksum = artifactChecksum;
        dataset.rowCount = rowCount;
        dataset.status = "READY";
        dataset.finalizedAt = request.asOfTime();
        dataset.createdAt = request.asOfTime();
        return dataset;
    }

    private static List<AiTrainingDatasetItem> datasetItems(
            Long datasetId,
            List<SelectedSource> selected,
            LocalDateTime includedAt
    ) {
        Map<String, Integer> sequences = new LinkedHashMap<>();
        List<AiTrainingDatasetItem> items = new ArrayList<>();
        for (SelectedSource value : selected) {
            AiTrainingDatasetSource source = value.source();
            AiTrainingDatasetItem item = new AiTrainingDatasetItem();
            item.trainingDatasetId = datasetId;
            item.sampleId = source.sampleId;
            item.labelId = source.labelId;
            item.splitType = value.split();
            item.sequenceNo = sequences.merge(value.split(), 1, Integer::sum);
            item.sampleAsOfTime = source.sampleAsOfTime;
            item.labelAvailableAt = source.labelAvailableAt;
            item.featureFingerprint = source.featureFingerprint;
            item.labelFingerprint = source.labelFingerprint;
            item.includedAt = includedAt;
            item.createdAt = includedAt;
            items.add(item);
        }
        return items;
    }

    private static void validatePersistedDataset(
            AiTrainingDataset expected,
            AiTrainingDataset actual
    ) {
        if (actual == null) {
            throw new IllegalStateException("训练数据集写入后未读取到记录");
        }
        if (!Objects.equals(expected.lineageFingerprint, actual.lineageFingerprint)
                || !Objects.equals(expected.artifactChecksum, actual.artifactChecksum)
                || !Objects.equals(expected.artifactUri, actual.artifactUri)
                || !Objects.equals(expected.rowCount, actual.rowCount)
                || !Objects.equals(expected.sourceQueryJson, actual.sourceQueryJson)
                || !Objects.equals(expected.selectionPolicyJson, actual.selectionPolicyJson)
                || !Objects.equals("READY", actual.status)) {
            throw new IllegalStateException("不可变训练数据集冲突：" + expected.datasetKey
                    + "/" + expected.versionNo);
        }
    }

    private static void validatePersistedItems(
            List<AiTrainingDatasetItem> expected,
            List<AiTrainingDatasetItem> actual
    ) {
        if (actual == null || actual.size() != expected.size()) {
            throw new IllegalStateException("训练数据集明细写入数量不一致");
        }
        Map<String, AiTrainingDatasetItem> byLineage = new LinkedHashMap<>();
        actual.forEach(item -> byLineage.put(item.sampleId + ":" + item.labelId, item));
        for (AiTrainingDatasetItem item : expected) {
            AiTrainingDatasetItem persisted = byLineage.get(item.sampleId + ":" + item.labelId);
            if (persisted == null || !Objects.equals(item.splitType, persisted.splitType)
                    || !Objects.equals(item.sequenceNo, persisted.sequenceNo)
                    || !Objects.equals(item.sampleAsOfTime, persisted.sampleAsOfTime)
                    || !Objects.equals(item.labelAvailableAt, persisted.labelAvailableAt)
                    || !Objects.equals(item.featureFingerprint, persisted.featureFingerprint)
                    || !Objects.equals(item.labelFingerprint, persisted.labelFingerprint)) {
                throw new IllegalStateException("不可变训练明细冲突：" + item.sampleId + "/" + item.labelId);
            }
        }
    }

    private static AiTrainingDatasetSourceQuery sourceQuery(DatasetBuildRequest request) {
        AiTrainingDatasetSourceQuery query = new AiTrainingDatasetSourceQuery();
        query.userId = request.userId();
        query.featureVersion = request.featureVersion();
        query.labelVersion = request.labelVersion();
        query.calendarVersion = request.calendarVersion();
        query.startDate = request.trainStartDate();
        query.endDate = request.testEndDate();
        query.maxHorizonDays = request.maxHorizonDays();
        query.asOfTime = request.asOfTime();
        return query;
    }

    private static Map<String, Object> sourceQueryEvidence(AiTrainingDatasetSourceQuery query) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "ai_sample INNER JOIN ai_sample_label");
        evidence.put("userId", query.userId);
        evidence.put("featureVersion", query.featureVersion);
        evidence.put("labelVersion", query.labelVersion);
        evidence.put("calendarVersion", query.calendarVersion);
        evidence.put("startDate", query.startDate.toString());
        evidence.put("endDate", query.endDate.toString());
        evidence.put("targetHorizonDays", query.maxHorizonDays);
        evidence.put("asOfTime", query.asOfTime.toString());
        evidence.put("labelStatus", "VERIFIED");
        return evidence;
    }

    private static Map<String, Object> selectionPolicyEvidence() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", "TRAINING_SELECTION_V2_2");
        policy.put("horizonPolicy", "EXACT_TARGET_HORIZON");
        policy.put("splitBasis", "sample.tradeDate");
        policy.put("featureCutoff", "all feature timestamps <= sample.asOfTime");
        policy.put("trainLabelCutoff", "labelAvailableAt < validationStartDate");
        policy.put("validationLabelCutoff", "labelAvailableAt < testStartDate");
        policy.put("testLabelCutoff", "labelAvailableAt <= dataset.asOfTime");
        policy.put("ordering", List.of("split", "tradeDate", "stockCode", "sampleId", "labelId"));
        return policy;
    }

    private static String lineageFingerprint(
            DatasetBuildRequest request,
            List<SelectedSource> selected,
            String sourceQueryJson,
            String selectionPolicyJson
    ) {
        String rows = selected.stream().map(value -> String.join(":",
                        value.split(), String.valueOf(value.source().sampleId),
                        String.valueOf(value.source().labelId), value.source().featureFingerprint,
                        value.source().labelFingerprint))
                .reduce((left, right) -> left + "|" + right).orElse("");
        return sha256(String.join("|", request.datasetKey(), request.versionNo(),
                sourceQueryJson, selectionPolicyJson, rows));
    }

    private static String split(DatasetBuildRequest request, LocalDate tradeDate) {
        if (between(tradeDate, request.trainStartDate(), request.trainEndDate())) {
            return "TRAIN";
        }
        if (between(tradeDate, request.validationStartDate(), request.validationEndDate())) {
            return "VALIDATION";
        }
        if (between(tradeDate, request.testStartDate(), request.testEndDate())) {
            return "TEST";
        }
        return null;
    }

    private static boolean labelAvailableBeforeBoundary(
            DatasetBuildRequest request,
            AiTrainingDatasetSource source,
            String split
    ) {
        if ("TRAIN".equals(split)) {
            return source.labelAvailableAt.isBefore(request.validationStartDate().atStartOfDay());
        }
        if ("VALIDATION".equals(split)) {
            return source.labelAvailableAt.isBefore(request.testStartDate().atStartOfDay());
        }
        return !source.labelAvailableAt.isAfter(request.asOfTime());
    }

    private static boolean between(LocalDate value, LocalDate start, LocalDate end) {
        return !value.isBefore(start) && !value.isAfter(end);
    }

    private static void validateBuildRequest(DatasetBuildRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0
                || blank(request.datasetKey()) || blank(request.versionNo()) || blank(request.purpose())
                || blank(request.featureVersion()) || blank(request.labelVersion())
                || blank(request.calendarVersion()) || request.asOfTime() == null
                || request.trainStartDate() == null || request.trainEndDate() == null
                || request.validationStartDate() == null || request.validationEndDate() == null
                || request.testStartDate() == null || request.testEndDate() == null
                || request.maxHorizonDays() == null || request.maxHorizonDays() <= 0
                || request.artifactPath() == null) {
            throw new IllegalArgumentException("训练数据集请求缺少用户、版本、窗口或产物路径");
        }
        if (request.trainStartDate().isAfter(request.trainEndDate())
                || !request.trainEndDate().isBefore(request.validationStartDate())
                || request.validationStartDate().isAfter(request.validationEndDate())
                || !request.validationEndDate().isBefore(request.testStartDate())
                || request.testStartDate().isAfter(request.testEndDate())
                || request.testEndDate().isAfter(request.asOfTime().toLocalDate())) {
            throw new IllegalArgumentException("训练、验证、测试窗口必须按日期严格递增且不晚于可见性截止时间");
        }
    }

    private static void validateModelRegistration(ModelRegistration registration) {
        if (registration == null || registration.userId() == null || registration.userId() <= 0) {
            throw new IllegalArgumentException("模型注册缺少 userId");
        }
        if (registration.trainingDatasetId() == null || registration.trainingDatasetId() <= 0) {
            throw new IllegalArgumentException("模型注册缺少有效 trainingDatasetId");
        }
        if (blank(registration.modelKey()) || blank(registration.versionNo())
                || blank(registration.modelType()) || blank(registration.algorithm())
                || blank(registration.featureVersion()) || blank(registration.trainerVersion())
                || registration.randomSeed() == null || blank(registration.artifactUri())
                || blank(registration.artifactChecksum())
                || blank(registration.featureManifestUri())
                || blank(registration.featureManifestChecksum())
                || blank(registration.metricsJson()) || blank(registration.calibrationJson())
                || registration.sampleCount() == null || registration.sampleCount() < 0
                || registration.registeredAt() == null) {
            throw new IllegalArgumentException("模型注册缺少版本、训练器、随机种子或产物指纹");
        }
    }

    private static AiModelVersion modelVersion(
            ModelRegistration registration,
            AiTrainingDataset dataset,
            boolean verifiedQualityGate
    ) {
        AiModelVersion model = new AiModelVersion();
        model.userId = registration.userId();
        model.trainingDatasetId = registration.trainingDatasetId();
        model.modelKey = registration.modelKey();
        model.versionNo = registration.versionNo();
        model.modelType = registration.modelType();
        model.algorithm = registration.algorithm();
        model.featureVersion = registration.featureVersion();
        model.trainerVersion = registration.trainerVersion();
        model.randomSeed = registration.randomSeed();
        model.artifactUri = registration.artifactUri();
        model.artifactChecksum = registration.artifactChecksum();
        model.featureManifestUri = registration.featureManifestUri();
        model.featureManifestChecksum = registration.featureManifestChecksum();
        model.trainStartDate = dataset.trainStartDate;
        model.trainEndDate = dataset.trainEndDate;
        model.validationStartDate = dataset.validationStartDate;
        model.validationEndDate = dataset.validationEndDate;
        model.testStartDate = dataset.testStartDate;
        model.testEndDate = dataset.testEndDate;
        model.parametersJson = registration.parametersJson();
        model.metricsJson = registration.metricsJson();
        model.calibrationJson = registration.calibrationJson();
        model.sampleCount = registration.sampleCount();
        model.status = registration.qualityGatePassed() && verifiedQualityGate ? "VALIDATED" : "CANDIDATE";
        model.createdAt = registration.registeredAt();
        model.updatedAt = registration.registeredAt();
        return model;
    }

    private boolean verifyModelArtifacts(ModelRegistration registration, AiTrainingDataset dataset) {
        Path artifact = localArtifact(registration.artifactUri(), "模型产物");
        Path manifest = localArtifact(registration.featureManifestUri(), "特征清单");
        verifyChecksum(artifact, registration.artifactChecksum(), "模型产物");
        verifyChecksum(manifest, registration.featureManifestChecksum(), "特征清单");
        JsonNode manifestJson = readJsonFile(manifest, "特征清单");
        if (!manifestJson.path("features").isArray() || manifestJson.path("features").isEmpty()
                || !registration.trainerVersion().equals(manifestJson.path("trainerVersion").asText())
                || registration.randomSeed() != manifestJson.path("randomSeed").asLong()) {
            throw new IllegalArgumentException("特征清单与模型训练版本、随机种子或特征顺序不一致");
        }
        JsonNode onnxOutput = manifestJson.path("onnxOutput");
        JsonNode manifestCalibration = manifestJson.path("calibration");
        boolean inferenceContractReady = onnxOutput.isObject()
                && !onnxOutput.path("name").asText().isBlank()
                && onnxOutput.path("index").canConvertToInt()
                && onnxOutput.path("index").asInt(-1) >= 0
                && ("RAW_SCORE".equals(onnxOutput.path("kind").asText())
                || "PROBABILITY_UP".equals(onnxOutput.path("kind").asText()))
                && manifestCalibration.isObject();
        JsonNode metrics = readJson(registration.metricsJson(), "模型指标");
        JsonNode calibration = readJson(registration.calibrationJson(), "校准信息");
        if (!registration.trainerVersion().equals(metrics.path("trainerVersion").asText())
                || !registration.algorithm().equals(metrics.path("algorithm").asText())
                || registration.randomSeed() != metrics.path("randomSeed").asLong()
                || !registration.artifactChecksum().equalsIgnoreCase(
                metrics.path("artifacts").path("modelSha256").asText())
                || !registration.featureManifestChecksum().equalsIgnoreCase(
                metrics.path("artifacts").path("featureManifestSha256").asText())) {
            throw new IllegalArgumentException("模型指标中的版本或 artifact checksum 与注册产物不一致");
        }
        if (dataset.rowCount == null || !Objects.equals(dataset.rowCount, registration.sampleCount())) {
            throw new IllegalArgumentException("模型样本数与不可变训练数据集不一致");
        }
        String calibrationMethod = calibration.path("method").asText();
        if (calibrationMethod.isBlank()
                || !calibrationMethod.equals(metrics.path("calibration").path("method").asText())) {
            throw new IllegalArgumentException("模型校准方法与训练指标不一致");
        }
        double coefficient = calibration.path("coefficient").asDouble(Double.NaN);
        double intercept = calibration.path("intercept").asDouble(Double.NaN);
        inferenceContractReady = inferenceContractReady
                && Double.isFinite(coefficient)
                && Double.isFinite(intercept)
                && Double.compare(coefficient,
                manifestCalibration.path("coefficient").asDouble(Double.NaN)) == 0
                && Double.compare(intercept,
                manifestCalibration.path("intercept").asDouble(Double.NaN)) == 0
                && metrics.path("artifacts").path("onnxExported").asBoolean(false)
                && registration.artifactChecksum().equalsIgnoreCase(
                metrics.path("artifacts").path("onnxSha256").asText());
        double validationAuc = metrics.path("splits").path("validation").path("rocAuc").asDouble(Double.NaN);
        double testAuc = metrics.path("splits").path("test").path("rocAuc").asDouble(Double.NaN);
        boolean calibrationFitted = metrics.path("calibration").path("fitted").asBoolean(false)
                && calibration.path("fitted").asBoolean(false);
        return inferenceContractReady
                && registration.sampleCount() >= 100
                && Double.isFinite(validationAuc) && validationAuc >= 0.55d
                && Double.isFinite(testAuc) && testAuc >= 0.52d
                && calibrationFitted;
    }

    private Path localArtifact(String uriValue, String label) {
        try {
            URI uri = new URI(uriValue);
            Path path = uri.getScheme() == null ? Path.of(uriValue) : "file".equalsIgnoreCase(uri.getScheme())
                    ? Path.of(uri) : null;
            if (path == null || !Files.isRegularFile(path)) {
                throw new IllegalArgumentException(label + "不存在或当前不支持非 file URI：" + uriValue);
            }
            return path.toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException
                    && illegalArgumentException.getMessage() != null
                    && illegalArgumentException.getMessage().startsWith(label)) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException(label + " URI 无效：" + uriValue, exception);
        }
    }

    private void verifyChecksum(Path path, String expected, String label) {
        String actual = sha256(path);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalArgumentException(label + " checksum 不匹配：" + path);
        }
    }

    private JsonNode readJsonFile(Path path, String label) {
        try {
            return objectMapper.readTree(path.toFile());
        } catch (IOException exception) {
            throw new IllegalArgumentException(label + "不是有效 JSON：" + path, exception);
        }
    }

    private JsonNode readJson(String content, String label) {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(label + "不是有效 JSON", exception);
        }
    }

    private static String sha256(Path path) {
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
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算模型产物 checksum：" + path, exception);
        }
    }

    private static boolean sameModelVersion(AiModelVersion expected, AiModelVersion actual) {
        return Objects.equals(expected.trainingDatasetId, actual.trainingDatasetId)
                && Objects.equals(expected.modelType, actual.modelType)
                && Objects.equals(expected.algorithm, actual.algorithm)
                && Objects.equals(expected.featureVersion, actual.featureVersion)
                && Objects.equals(expected.trainerVersion, actual.trainerVersion)
                && Objects.equals(expected.randomSeed, actual.randomSeed)
                && Objects.equals(expected.artifactUri, actual.artifactUri)
                && Objects.equals(expected.artifactChecksum, actual.artifactChecksum)
                && Objects.equals(expected.featureManifestUri, actual.featureManifestUri)
                && Objects.equals(expected.featureManifestChecksum, actual.featureManifestChecksum)
                && Objects.equals(expected.parametersJson, actual.parametersJson)
                && Objects.equals(expected.metricsJson, actual.metricsJson)
                && Objects.equals(expected.calibrationJson, actual.calibrationJson)
                && Objects.equals(expected.sampleCount, actual.sampleCount)
                && Objects.equals(expected.status, actual.status);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化训练数据血缘", ex);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record SelectedSource(
            AiTrainingDatasetSource source,
            String split,
            JsonNode features
    ) {
    }
}
