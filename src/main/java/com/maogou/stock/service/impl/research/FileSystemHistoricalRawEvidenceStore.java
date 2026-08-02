package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.service.research.HistoricalRawEvidenceStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Stores the canonical provider response before any research fact is written.
 * Files are checksum-addressed and moved into place only after the compressed
 * object has been fully written, so EOF/truncated responses cannot become
 * READY evidence.
 */
@Component
public class FileSystemHistoricalRawEvidenceStore implements HistoricalRawEvidenceStore {

    private static final String PACKAGE_SCHEMA = "MAOGOU_HISTORICAL_RAW_EVIDENCE_V1";

    private final ObjectMapper objectMapper;
    private final Path root;

    public FileSystemHistoricalRawEvidenceStore(ObjectMapper objectMapper, AppProperties properties) {
        this(objectMapper, Path.of(properties.getScheduler().getHistoricalRawEvidenceRoot()));
    }

    FileSystemHistoricalRawEvidenceStore(ObjectMapper objectMapper, Path root) {
        this.objectMapper = objectMapper;
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public RawArtifact stage(
            Long backfillRunId,
            String providerCode,
            String datasetCode,
            String sourceRevision,
            Object payload,
            LocalDateTime observedAt
    ) {
        String provider = normalize(providerCode);
        String dataset = normalize(datasetCode);
        String revision = safePathPart(sourceRevision, "UNKNOWN_REVISION");
        LocalDateTime observed = observedAt == null ? LocalDateTime.now() : observedAt;
        if (provider.contains("MOCK") || provider.contains("FIXTURE") || provider.contains("LOCAL")) {
            throw new IllegalArgumentException("拒绝将演示或 local provider 写入原始证据：" + provider);
        }
        EvidenceMetadata metadata = validateAndDescribe(dataset, payload, observed);
        byte[] canonical = jsonBytes(payload);
        String canonicalChecksum = sha256(canonical);
        Path directory = root.resolve(provider).resolve(revision).resolve(dataset);
        Path target = directory.resolve(canonicalChecksum + ".json.gz");
        try {
            Files.createDirectories(directory);
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(directory, "." + canonicalChecksum + ".", ".tmp");
                try {
                    try (OutputStream output = Files.newOutputStream(temporary);
                         GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                        gzip.write(canonical);
                    }
                    moveAtomically(temporary, target);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            long size = Files.size(target);
            String objectChecksum = sha256(target);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("packageSchema", PACKAGE_SCHEMA);
            manifest.put("backfillRunId", backfillRunId);
            manifest.put("providerCode", provider);
            manifest.put("datasetCode", dataset);
            manifest.put("sourceRevision", revision);
            manifest.put("objectUri", target.toUri().toString());
            manifest.put("objectSize", size);
            manifest.put("objectChecksum", objectChecksum);
            manifest.put("canonicalPayloadChecksum", canonicalChecksum);
            manifest.put("schemaVersion", metadata.schemaVersion());
            manifest.put("rowCount", metadata.rowCount());
            manifest.put("rangeStartDate", metadata.rangeStartDate());
            manifest.put("rangeEndDate", metadata.rangeEndDate());
            manifest.put("observedAt", observed);
            return new RawArtifact(
                    target.toUri().toString(), size, objectChecksum, metadata.schemaVersion(),
                    metadata.rowCount(), metadata.rangeStartDate(), metadata.rangeEndDate(), observed,
                    json(manifest));
        } catch (IOException exception) {
            throw new IllegalStateException("原始历史证据落盘失败：provider=" + provider
                    + ", dataset=" + dataset + ", reason=" + exception.getMessage(), exception);
        }
    }

    private EvidenceMetadata validateAndDescribe(String dataset, Object payload, LocalDateTime observedAt) {
        if (payload == null) {
            throw new IllegalArgumentException("原始证据 payload 为空：" + dataset);
        }
        if (payload instanceof HistoricalMarketDataProvider.UniverseCatalog catalog) {
            if (!"SECURITY_CATALOG".equals(dataset)) {
                throw new IllegalArgumentException("目录 payload 与 dataset 不匹配：" + dataset);
            }
            if (catalog.securities().isEmpty() || blank(catalog.sourceFingerprint())
                    || blank(catalog.sourceUri())) {
                throw new IllegalArgumentException("目录缺少证券、sourceFingerprint 或 sourceUri");
            }
            for (HistoricalMarketDataProvider.Security security : catalog.securities()) {
                if (security == null || security.stockCode() == null
                        || !security.stockCode().matches("[036]\\d{5}")
                        || blank(security.stockName())) {
                    throw new IllegalArgumentException("目录包含非法证券行，已拒绝进入证据区");
                }
            }
            return new EvidenceMetadata(
                    PACKAGE_SCHEMA + "/SECURITY_CATALOG", catalog.securities().size(), null, null);
        }
        if (payload instanceof KlineSeriesSnapshot series) {
            if (!("DAILY_BAR_NONE".equals(dataset) || "DAILY_BAR_QFQ".equals(dataset))) {
                throw new IllegalArgumentException("K 线 payload 与 dataset 不匹配：" + dataset);
            }
            String expectedAdjustment = dataset.endsWith("QFQ") ? "QFQ" : "NONE";
            if (!series.fingerprintMatches() || !expectedAdjustment.equalsIgnoreCase(series.adjustmentMode())
                    || blank(series.source()) || blank(series.sourceFingerprint())
                    || series.points() == null || series.points().isEmpty()) {
                throw new IllegalArgumentException("K 线缺少合法来源、指纹或数据行");
            }
            List<KlinePointResponse> points = new ArrayList<>(series.points());
            points.sort(Comparator.comparing(KlinePointResponse::tradeDate));
            LocalDate previous = null;
            for (KlinePointResponse point : points) {
                if (point == null || point.tradeDate() == null || point.tradeDate().isAfter(series.asOfTime().toLocalDate())
                        || previous != null && !point.tradeDate().isAfter(previous)
                        || !validPrices(point)) {
                    throw new IllegalArgumentException("K 线包含未来日期、重复日期或非法 OHLC");
                }
                previous = point.tradeDate();
            }
            return new EvidenceMetadata(
                    PACKAGE_SCHEMA + "/" + dataset, points.size(), points.get(0).tradeDate(),
                    points.get(points.size() - 1).tradeDate());
        }
        throw new IllegalArgumentException("不支持的历史证据 payload 类型：" + payload.getClass().getName());
    }

    private static boolean validPrices(KlinePointResponse point) {
        return positive(point.open()) && positive(point.close()) && positive(point.high())
                && positive(point.low()) && point.high().compareTo(point.low()) >= 0;
    }

    private static boolean positive(java.math.BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private byte[] jsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("原始证据无法序列化：" + exception.getMessage(), exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("原始证据 manifest 无法序列化", exception);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safePathPart(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (!normalized.matches("[A-Za-z0-9._/-]+") || normalized.contains("..")) {
            throw new IllegalArgumentException("sourceRevision 含非法路径字符");
        }
        return normalized.replace('/', '_');
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record EvidenceMetadata(
            String schemaVersion,
            long rowCount,
            LocalDate rangeStartDate,
            LocalDate rangeEndDate
    ) {
    }
}
