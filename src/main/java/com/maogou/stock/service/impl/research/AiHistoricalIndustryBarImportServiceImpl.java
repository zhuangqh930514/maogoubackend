package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiIndustryDailyBar;
import com.maogou.stock.mapper.research.AiIndustryDailyBarMapper;
import com.maogou.stock.service.research.AiHistoricalIndustryBarImportService;
import com.maogou.stock.service.research.AiIndustryDailyBarService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AiHistoricalIndustryBarImportServiceImpl implements AiHistoricalIndustryBarImportService {

    static final String HEADER = "industry_code,industry_name,classification_standard,trade_date,open,high,low,close,volume,amount,source_ref";
    private static final Pattern CODE = Pattern.compile("[A-Z0-9.]{3,32}");
    private static final Pattern STANDARD = Pattern.compile("[A-Z0-9_]{3,32}");
    private static final Pattern SOURCE = Pattern.compile("[A-Z0-9_]{3,32}");
    private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final int MAX_ROWS = 200_000;

    private final AppProperties properties;
    private final AiIndustryDailyBarMapper mapper;
    private final AiIndustryDailyBarService barService;
    private final ObjectMapper objectMapper;

    public AiHistoricalIndustryBarImportServiceImpl(
            AppProperties properties,
            AiIndustryDailyBarMapper mapper,
            AiIndustryDailyBarService barService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.mapper = mapper;
        this.barService = barService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ImportResult importCsv(ImportRequest request) {
        validateRequest(request);
        ParsedFile parsed = parse(request.file());
        int inserted = 0;
        int reused = 0;
        LocalDate earliest = null;
        LocalDate latest = null;
        Set<String> industries = new LinkedHashSet<>();
        for (Row row : parsed.rows()) {
            String fingerprint = sha256(String.join("|", "HISTORICAL_INDUSTRY_BAR/1.0.0",
                    parsed.checksum(), normalize(request.sourceName()), request.sourceRevision().trim(),
                    row.canonical()));
            AiIndustryDailyBar current = mapper.selectCurrent(
                    row.industryCode(), row.classificationStandard(), row.tradeDate());
            if (current != null && fingerprint.equals(current.sourceFingerprint)) {
                reused++;
            } else {
                barService.store(command(request, parsed.checksum(), row, fingerprint));
                inserted++;
            }
            industries.add(row.classificationStandard() + "|" + row.industryCode());
            earliest = earliest == null || row.tradeDate().isBefore(earliest) ? row.tradeDate() : earliest;
            latest = latest == null || row.tradeDate().isAfter(latest) ? row.tradeDate() : latest;
        }
        return new ImportResult(parsed.checksum(), parsed.rows().size(), industries.size(),
                inserted, reused, earliest, latest);
    }

    private AiIndustryDailyBarService.BarCommand command(
            ImportRequest request,
            String fileChecksum,
            Row row,
            String fingerprint
    ) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("format", "MAOGOU_HISTORICAL_INDUSTRY_BAR_CSV_V1");
        evidence.put("sourceName", normalize(request.sourceName()));
        evidence.put("sourceRevision", request.sourceRevision().trim());
        evidence.put("sourceObservedAt", request.sourceObservedAt().toString());
        evidence.put("sourceRef", row.sourceRef());
        evidence.put("fileChecksum", fileChecksum);
        evidence.put("lineNumber", row.lineNumber());
        evidence.put("rowFingerprint", sha256(row.canonical()));
        return new AiIndustryDailyBarService.BarCommand(
                row.industryCode(), row.industryName(), row.classificationStandard(), row.tradeDate(),
                row.openPrice(), row.highPrice(), row.lowPrice(), row.closePrice(), row.volume(), row.amount(),
                normalize(request.sourceName()), request.sourceRevision().trim(), "READY", row.sourceRef(),
                json(evidence), fingerprint, request.sourceObservedAt());
    }

    private ParsedFile parse(MultipartFile file) {
        List<Row> rows = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = file.getInputStream();
                 DigestInputStream input = new DigestInputStream(source, digest);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (header != null && header.startsWith("\uFEFF")) {
                    header = header.substring(1);
                }
                if (!HEADER.equals(header)) {
                    throw new IllegalArgumentException("历史行业日线 CSV 表头必须为：" + HEADER);
                }
                String line;
                int lineNumber = 1;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) {
                        continue;
                    }
                    if (rows.size() >= MAX_ROWS) {
                        throw new IllegalArgumentException("历史行业日线 CSV 行数超过限制");
                    }
                    Row row = row(line, lineNumber);
                    String key = row.classificationStandard() + "|" + row.industryCode() + "|" + row.tradeDate();
                    if (!keys.add(key)) {
                        throw new IllegalArgumentException("第 " + lineNumber + " 行与前文重复行业日期");
                    }
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("历史行业日线 CSV 没有数据行");
            }
            return new ParsedFile(HexFormat.of().formatHex(digest.digest()), List.copyOf(rows));
        } catch (IOException exception) {
            throw new IllegalStateException("读取历史行业日线 CSV 失败", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static Row row(String line, int lineNumber) {
        if (line.indexOf('"') >= 0) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行不支持引号或逗号字段");
        }
        String[] values = line.split(",", -1);
        if (values.length != 11) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行字段数错误");
        }
        String code = normalize(values[0]);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 industry_code 不合法");
        }
        String name = values[1].trim();
        if (name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 industry_name 缺失或过长");
        }
        String standard = normalize(values[2]);
        if (!STANDARD.matcher(standard).matches()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 classification_standard 不合法");
        }
        LocalDate tradeDate;
        try {
            tradeDate = LocalDate.parse(values[3].trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 trade_date 错误");
        }
        BigDecimal open = decimal(values[4], lineNumber, "open");
        BigDecimal high = decimal(values[5], lineNumber, "high");
        BigDecimal low = decimal(values[6], lineNumber, "low");
        BigDecimal close = decimal(values[7], lineNumber, "close");
        BigDecimal volume = decimal(values[8], lineNumber, "volume");
        BigDecimal amount = decimal(values[9], lineNumber, "amount");
        if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0
                || volume.signum() < 0 || amount.signum() < 0
                || high.compareTo(open) < 0 || high.compareTo(close) < 0 || high.compareTo(low) < 0
                || low.compareTo(open) > 0 || low.compareTo(close) > 0) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 OHLC、成交量或成交额不一致");
        }
        String sourceRef = values[10].trim();
        if (sourceRef.isBlank() || sourceRef.length() > 255) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 source_ref 缺失或过长");
        }
        return new Row(lineNumber, code, name, standard, tradeDate,
                open, high, low, close, volume, amount, sourceRef);
    }

    private void validateRequest(ImportRequest request) {
        if (request == null || request.operatorUserId() == null || request.operatorUserId() <= 0
                || request.file() == null || request.file().isEmpty()) {
            throw new IllegalArgumentException("历史行业日线导入缺少文件或运维操作者");
        }
        if (request.file().getSize() > properties.getScheduler().getHistoricalStateImportMaxBytes()) {
            throw new IllegalArgumentException("历史行业日线 CSV 超过大小限制");
        }
        String fileName = request.file().getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("历史行业日线导入仅支持 .csv 文件");
        }
        String sourceName = normalize(request.sourceName());
        if (!SOURCE.matcher(sourceName).matches() || sourceName.contains("MOCK")
                || sourceName.contains("FALLBACK") || sourceName.contains("UNAVAILABLE")) {
            throw new IllegalArgumentException("历史行业日线来源名称不合法");
        }
        if (request.sourceRevision() == null || !REVISION.matcher(request.sourceRevision().trim()).matches()
                || request.sourceObservedAt() == null) {
            throw new IllegalArgumentException("历史行业日线导入缺少合法来源版本或观测时间");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法写入历史行业日线证据", exception);
        }
    }

    private static BigDecimal decimal(String value, int lineNumber, String field) {
        try {
            return new BigDecimal(value.trim()).setScale(6, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 " + field + " 数值错误");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record Row(
            int lineNumber,
            String industryCode,
            String industryName,
            String classificationStandard,
            LocalDate tradeDate,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal volume,
            BigDecimal amount,
            String sourceRef
    ) {
        private String canonical() {
            return industryCode + "|" + industryName + "|" + classificationStandard + "|" + tradeDate
                    + "|" + openPrice.toPlainString() + "|" + highPrice.toPlainString()
                    + "|" + lowPrice.toPlainString() + "|" + closePrice.toPlainString()
                    + "|" + volume.toPlainString() + "|" + amount.toPlainString() + "|" + sourceRef;
        }
    }

    private record ParsedFile(String checksum, List<Row> rows) {
    }
}
