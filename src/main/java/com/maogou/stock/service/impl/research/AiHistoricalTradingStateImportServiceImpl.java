package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiSecurityDailyState;
import com.maogou.stock.mapper.research.AiSecurityDailyStateMapper;
import com.maogou.stock.service.research.AiHistoricalTradingStateImportService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiResearchUniverseService;
import com.maogou.stock.service.research.AiSecurityDailyStateService;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Imports a deliberately narrow CSV contract from an auditable vendor archive.
 *
 * <p>The importer rejects incomplete rows rather than guessing ST status, suspension,
 * or price limits. If point-in-time K-line evidence is already available, it is preserved
 * in the new revision. A complete authoritative state archive may also establish the
 * first state revision directly, which is required when a privacy-minimized local snapshot
 * intentionally omits the multi-gigabyte raw-observation archive.</p>
 */
@Service
public class AiHistoricalTradingStateImportServiceImpl implements AiHistoricalTradingStateImportService {

    static final String HEADER = "stock_code,stock_name,market,trade_date,listed_on,delisted_on,listed_status,is_st,suspended,limit_ratio,is_limit_up,is_limit_down,buy_tradable,sell_tradable,industry_code,industry_name,industry_standard,industry_member_from,industry_member_to,industry_source_ref,source_ref";
    private static final Pattern STOCK_CODE = Pattern.compile("[0-9]{6}");
    private static final Pattern MARKET = Pattern.compile("SH|SZ|BJ");
    private static final Pattern SOURCE = Pattern.compile("[A-Z0-9_]{3,32}");
    private static final Pattern INDUSTRY_CODE = Pattern.compile("[A-Z0-9.]{3,32}");
    private static final Pattern INDUSTRY_STANDARD = Pattern.compile("[A-Z0-9_]{3,32}");
    private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final int MAX_ROWS = 1_000_000;
    private static final Set<BigDecimal> ALLOWED_LIMIT_RATIOS = Set.of(
            new BigDecimal("0.050000"), new BigDecimal("0.100000"),
            new BigDecimal("0.200000"), new BigDecimal("0.300000"));

    private final AppProperties properties;
    private final AiSecurityDailyStateMapper stateMapper;
    private final AiSecurityDailyStateService stateService;
    private final AiResearchUniverseService universeService;
    private final ObjectMapper objectMapper;

    public AiHistoricalTradingStateImportServiceImpl(
            AppProperties properties,
            AiSecurityDailyStateMapper stateMapper,
            AiSecurityDailyStateService stateService,
            AiResearchUniverseService universeService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.stateMapper = stateMapper;
        this.stateService = stateService;
        this.universeService = universeService;
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
        for (Row row : parsed.rows()) {
            AiSecurityDailyState existing = stateMapper.selectCurrent(row.stockCode(), row.tradeDate());
            String fingerprint = sha256(String.join("|", "HISTORICAL_TRADING_STATE/1.0.0",
                    parsed.checksum(), request.sourceName().trim().toUpperCase(Locale.ROOT),
                    request.sourceRevision().trim(), row.canonical()));
            if (existing != null && fingerprint.equals(existing.sourceFingerprint)) {
                reused++;
            } else {
                stateService.store(command(request, parsed.checksum(), row, existing, fingerprint));
                inserted++;
            }
            earliest = earliest == null || row.tradeDate().isBefore(earliest) ? row.tradeDate() : earliest;
            latest = latest == null || row.tradeDate().isAfter(latest) ? row.tradeDate() : latest;
        }
        int insertedSnapshots = 0;
        int reusedSnapshots = 0;
        Map<LocalDate, List<Row>> rowsByDate = new LinkedHashMap<>();
        parsed.rows().forEach(row -> rowsByDate.computeIfAbsent(row.tradeDate(), ignored -> new ArrayList<>()).add(row));
        for (Map.Entry<LocalDate, List<Row>> entry : rowsByDate.entrySet()) {
            AiResearchUniverseService.SnapshotResult result = universeService.createSystemCoreSnapshot(
                    universeRequest(request, parsed.checksum(), entry.getKey(), entry.getValue()));
            if (result.snapshot() == null || result.snapshot().id == null
                    || !"FINALIZED".equals(result.snapshot().status)
                    || !"READY".equals(result.snapshot().pointInTimeStatus)) {
                throw new IllegalStateException("历史股票池快照没有形成可审计的 READY 结果：" + entry.getKey());
            }
            if (result.reused()) {
                reusedSnapshots++;
            } else {
                insertedSnapshots++;
            }
        }
        return new ImportResult(parsed.checksum(), parsed.rows().size(), inserted, reused,
                insertedSnapshots, reusedSnapshots, earliest, latest);
    }

    private AiResearchUniverseService.SnapshotRequest universeRequest(
            ImportRequest request,
            String fileChecksum,
            LocalDate tradeDate,
            List<Row> rows
    ) {
        String sourceName = request.sourceName().trim().toUpperCase(Locale.ROOT);
        String sourceRevision = request.sourceRevision().trim();
        List<AiResearchUniverseService.UniverseCandidate> candidates = rows.stream()
                .map(row -> new AiResearchUniverseService.UniverseCandidate(
                        row.stockCode(), row.stockName(), row.market(),
                        sourceName + "_HISTORICAL_MEMBERSHIP", true, null,
                        row.listedOn(), row.delistedOn(), row.listedStatus(), row.sourceRef(),
                        sha256(fileChecksum + "|" + row.canonical()),
                        row.industryCode(), row.industryName(), row.industryStandard(),
                        sha256(row.industryCanonical())))
                .toList();
        return new AiResearchUniverseService.SnapshotRequest(
                tradeDate, tradeDate.atTime(16, 0), AiResearchContract.CALENDAR_VERSION,
                candidates, false, sourceName, sourceRevision, request.sourceObservedAt(),
                "READY", "供应商历史证券主数据按目标交易日重建");
    }

    private AiSecurityDailyStateService.StateCommand command(
            ImportRequest request,
            String fileChecksum,
            Row row,
            AiSecurityDailyState previous,
            String fingerprint
    ) {
        LocalDate listedOn = row.listedOn() == null
                ? previous == null ? null : previous.listedOn
                : row.listedOn();
        Integer listedDays = listedOn == null || listedOn.isAfter(row.tradeDate()) ? null
                : Math.toIntExact(ChronoUnit.DAYS.between(listedOn, row.tradeDate()) + 1);
        BigDecimal previousClose = previous == null ? null : decimal(previous.evidenceJson, "previousClose");
        BigDecimal limitUp = limitPrice(previousClose, row.limitRatio(), true);
        BigDecimal limitDown = limitPrice(previousClose, row.limitRatio(), false);
        validateTradingConsistency(row);

        Map<String, Object> evidence = previous == null ? new LinkedHashMap<>() : previousEvidence(previous.evidenceJson);
        Map<String, Object> importedEvidence = new LinkedHashMap<>();
        importedEvidence.put("format", "MAOGOU_HISTORICAL_TRADING_STATE_CSV_V3");
        importedEvidence.put("sourceName", request.sourceName().trim().toUpperCase(Locale.ROOT));
        importedEvidence.put("sourceRevision", request.sourceRevision().trim());
        importedEvidence.put("sourceObservedAt", request.sourceObservedAt().toString());
        importedEvidence.put("sourceRef", row.sourceRef());
        importedEvidence.put("stockName", row.stockName());
        importedEvidence.put("market", row.market());
        importedEvidence.put("listedStatus", row.listedStatus());
        importedEvidence.put("delistedOn", row.delistedOn() == null ? "" : row.delistedOn().toString());
        importedEvidence.put("industryCode", row.industryCode());
        importedEvidence.put("industryName", row.industryName());
        importedEvidence.put("industryStandard", row.industryStandard());
        importedEvidence.put("industryMemberFrom", row.industryMemberFrom().toString());
        importedEvidence.put("industryMemberTo", row.industryMemberTo() == null
                ? "" : row.industryMemberTo().toString());
        importedEvidence.put("industrySourceRef", row.industrySourceRef());
        importedEvidence.put("fileChecksum", fileChecksum);
        importedEvidence.put("lineNumber", row.lineNumber());
        importedEvidence.put("rowFingerprint", sha256(row.canonical()));
        evidence.put("historicalTradingStateImport", importedEvidence);
        return new AiSecurityDailyStateService.StateCommand(
                row.stockCode(), row.tradeDate(), previous == null ? null : previous.sourceBatchId,
                request.sourceName().trim().toUpperCase(Locale.ROOT) + "/" + request.sourceRevision().trim(),
                listedOn, listedDays, row.suspended() == 1 ? "SUSPENDED" : row.listedStatus(),
                row.isSt() == 1 ? "VERIFIED_ST" : "VERIFIED_NON_ST", row.isSt(), row.suspended(),
                row.limitRatio(), limitUp, limitDown, row.isLimitUp(), row.isLimitDown(),
                row.buyTradable(), row.sellTradable(), "READY", null, json(evidence), fingerprint,
                request.sourceObservedAt());
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
                    throw new IllegalArgumentException("历史交易状态 CSV 表头必须为：" + HEADER);
                }
                String line;
                int lineNumber = 1;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) {
                        continue;
                    }
                    if (rows.size() >= MAX_ROWS) {
                        throw new IllegalArgumentException("历史交易状态 CSV 行数超过限制");
                    }
                    Row row = row(line, lineNumber);
                    if (!keys.add(row.stockCode() + "|" + row.tradeDate())) {
                        throw new IllegalArgumentException("第 " + lineNumber + " 行与前文重复证券日期");
                    }
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("历史交易状态 CSV 没有数据行");
            }
            return new ParsedFile(HexFormat.of().formatHex(digest.digest()), List.copyOf(rows));
        } catch (IOException exception) {
            throw new IllegalStateException("读取历史交易状态 CSV 失败", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static Row row(String line, int lineNumber) {
        if (line.indexOf('"') >= 0) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行不支持引号或逗号字段");
        }
        String[] values = line.split(",", -1);
        if (values.length != 21) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行字段数错误");
        }
        String stockCode = values[0].trim();
        if (!STOCK_CODE.matcher(stockCode).matches()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行股票代码错误");
        }
        String stockName = values[1].trim();
        if (stockName.isBlank() || stockName.length() > 64) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 stock_name 缺失或过长");
        }
        String market = values[2].trim().toUpperCase(Locale.ROOT);
        if (!MARKET.matcher(market).matches()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 market 必须为 SH、SZ 或 BJ");
        }
        LocalDate tradeDate = date(values[3], lineNumber, "trade_date");
        LocalDate listedOn = values[4].isBlank() ? null : date(values[4], lineNumber, "listed_on");
        LocalDate delistedOn = values[5].isBlank() ? null : date(values[5], lineNumber, "delisted_on");
        String listedStatus = values[6].trim().toUpperCase(Locale.ROOT);
        if (!"LISTED".equals(listedStatus)) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行只允许导入目标日仍上市的证券");
        }
        if (listedOn == null || listedOn.isAfter(tradeDate)
                || (delistedOn != null && delistedOn.isBefore(tradeDate))) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行上市/退市有效期与交易日不一致");
        }
        int isSt = flag(values[7], lineNumber, "is_st");
        int suspended = flag(values[8], lineNumber, "suspended");
        BigDecimal limitRatio;
        try {
            limitRatio = new BigDecimal(values[9].trim()).setScale(6, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 limit_ratio 错误");
        }
        if (!ALLOWED_LIMIT_RATIOS.contains(limitRatio) || (isSt == 1 && limitRatio.compareTo(new BigDecimal("0.050000")) != 0)) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行涨跌停比例与 ST 状态不一致或不受支持");
        }
        int isLimitUp = flag(values[10], lineNumber, "is_limit_up");
        int isLimitDown = flag(values[11], lineNumber, "is_limit_down");
        int buyTradable = flag(values[12], lineNumber, "buy_tradable");
        int sellTradable = flag(values[13], lineNumber, "sell_tradable");
        if (suspended == 1 && (buyTradable != 0 || sellTradable != 0)) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行停牌证券不能标记为可交易");
        }
        String industryCode = values[14].trim().toUpperCase(Locale.ROOT);
        if (!INDUSTRY_CODE.matcher(industryCode).matches()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 industry_code 不合法");
        }
        String industryName = values[15].trim();
        if (industryName.isBlank() || industryName.length() > 64) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 industry_name 缺失或过长");
        }
        String industryStandard = values[16].trim().toUpperCase(Locale.ROOT);
        if (!INDUSTRY_STANDARD.matcher(industryStandard).matches()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 industry_standard 不合法");
        }
        LocalDate industryMemberFrom = date(values[17], lineNumber, "industry_member_from");
        LocalDate industryMemberTo = values[18].isBlank()
                ? null : date(values[18], lineNumber, "industry_member_to");
        if (industryMemberFrom.isAfter(tradeDate)
                || (industryMemberTo != null && industryMemberTo.isBefore(tradeDate))) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行行业归属有效期不包含交易日");
        }
        String industrySourceRef = values[19].trim();
        if (industrySourceRef.isBlank() || industrySourceRef.length() > 255) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 industry_source_ref 缺失或过长");
        }
        String sourceRef = values[20].trim();
        if (sourceRef.isBlank() || sourceRef.length() > 255) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 source_ref 缺失或过长");
        }
        return new Row(lineNumber, stockCode, stockName, market, tradeDate, listedOn, delistedOn,
                listedStatus, isSt, suspended, limitRatio, isLimitUp, isLimitDown,
                buyTradable, sellTradable, industryCode, industryName, industryStandard,
                industryMemberFrom, industryMemberTo, industrySourceRef, sourceRef);
    }

    private void validateRequest(ImportRequest request) {
        if (request == null || request.operatorUserId() == null || request.operatorUserId() <= 0
                || request.file() == null || request.file().isEmpty()) {
            throw new IllegalArgumentException("历史交易状态导入缺少文件或运维操作者");
        }
        if (request.file().getSize() > properties.getScheduler().getHistoricalStateImportMaxBytes()) {
            throw new IllegalArgumentException("历史交易状态 CSV 超过大小限制");
        }
        String fileName = request.file().getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("历史交易状态导入仅支持 .csv 文件");
        }
        String sourceName = request.sourceName() == null ? "" : request.sourceName().trim().toUpperCase(Locale.ROOT);
        if (!SOURCE.matcher(sourceName).matches()) {
            throw new IllegalArgumentException("历史交易状态来源名称不合法");
        }
        if (request.sourceRevision() == null || !REVISION.matcher(request.sourceRevision().trim()).matches()
                || request.sourceObservedAt() == null) {
            throw new IllegalArgumentException("历史交易状态导入缺少合法来源版本或观测时间");
        }
    }

    private static void validateTradingConsistency(Row row) {
        if (row.suspended() == 1 && (row.buyTradable() != 0 || row.sellTradable() != 0)) {
            throw new IllegalArgumentException("停牌证券不能标记为可交易");
        }
        if (row.isLimitUp() == 1 && row.buyTradable() != 0) {
            throw new IllegalArgumentException(row.stockCode() + " " + row.tradeDate() + " 一字涨停不能买入");
        }
        if (row.isLimitDown() == 1 && row.sellTradable() != 0) {
            throw new IllegalArgumentException(row.stockCode() + " " + row.tradeDate() + " 一字跌停不能卖出");
        }
    }

    private Map<String, Object> previousEvidence(String value) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(entry -> evidence.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class)));
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("已有日度状态证据不是合法 JSON", exception);
        }
        return evidence;
    }

    private BigDecimal decimal(String evidence, String field) {
        try {
            JsonNode node = objectMapper.readTree(evidence).path(field);
            return node.isNumber() || node.isTextual() ? node.decimalValue() : null;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("已有日度状态缺少可解析价格证据", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法写入历史交易状态证据", exception);
        }
    }

    private static BigDecimal limitPrice(BigDecimal previousClose, BigDecimal ratio, boolean upper) {
        if (previousClose == null || previousClose.signum() <= 0) {
            return null;
        }
        return previousClose.multiply(upper ? BigDecimal.ONE.add(ratio) : BigDecimal.ONE.subtract(ratio))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate date(String value, int lineNumber, String field) {
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 " + field + " 日期错误");
        }
    }

    private static int flag(String value, int lineNumber, String field) {
        String normalized = value.trim();
        if (!"0".equals(normalized) && !"1".equals(normalized)) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 " + field + " 必须为 0 或 1");
        }
        return Integer.parseInt(normalized);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record Row(int lineNumber, String stockCode, String stockName, String market,
                       LocalDate tradeDate, LocalDate listedOn, LocalDate delistedOn,
                       String listedStatus, int isSt, int suspended, BigDecimal limitRatio,
                       int isLimitUp, int isLimitDown, int buyTradable, int sellTradable,
                       String industryCode, String industryName, String industryStandard,
                       LocalDate industryMemberFrom, LocalDate industryMemberTo,
                       String industrySourceRef, String sourceRef) {
        private String canonical() {
            return stockCode + "|" + stockName + "|" + market + "|" + tradeDate
                    + "|" + (listedOn == null ? "" : listedOn)
                    + "|" + (delistedOn == null ? "" : delistedOn) + "|" + listedStatus
                    + "|" + isSt + "|" + suspended + "|" + limitRatio.toPlainString()
                    + "|" + isLimitUp + "|" + isLimitDown + "|" + buyTradable
                    + "|" + sellTradable + "|" + industryCanonical() + "|" + sourceRef;
        }

        private String industryCanonical() {
            return industryCode + "|" + industryName + "|" + industryStandard
                    + "|" + industryMemberFrom + "|"
                    + (industryMemberTo == null ? "" : industryMemberTo)
                    + "|" + industrySourceRef;
        }
    }

    private record ParsedFile(String checksum, List<Row> rows) {
    }
}
