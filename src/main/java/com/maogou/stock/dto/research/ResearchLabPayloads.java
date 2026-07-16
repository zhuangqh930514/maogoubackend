package com.maogou.stock.dto.research;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ResearchLabPayloads {

    private ResearchLabPayloads() {
    }

    public record QueryFilter(
            Integer page,
            Integer pageSize,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            String stockCode,
            String status,
            Long strategyReleaseId,
            Long modelVersionId,
            String qualityStatus
    ) {
        public QueryFilter {
            page = Math.max(1, page == null ? 1 : page);
            pageSize = Math.max(1, Math.min(pageSize == null ? 20 : pageSize, 100));
            stockCode = trim(stockCode);
            status = upper(status);
            qualityStatus = upper(qualityStatus);
            if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
                throw new IllegalArgumentException("dateFrom 不能晚于 dateTo");
            }
            if (strategyReleaseId != null && strategyReleaseId <= 0) {
                throw new IllegalArgumentException("strategyReleaseId 必须为正数");
            }
            if (modelVersionId != null && modelVersionId <= 0) {
                throw new IllegalArgumentException("modelVersionId 必须为正数");
            }
        }

        public long offset() {
            return (long) (page - 1) * pageSize;
        }
    }

    public record EvidenceItem(String type, Map<String, Object> fields) {
        public EvidenceItem {
            type = trim(type);
            fields = immutableMap(fields);
        }
    }

    public record PageResult<T>(List<T> items, long total, int page, int pageSize) {
        public PageResult {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static <T> PageResult<T> empty(int page, int pageSize) {
            return new PageResult<>(List.of(), 0, page, pageSize);
        }
    }

    public record Detail(EvidenceItem record, Map<String, List<EvidenceItem>> related) {
        public Detail {
            related = immutableMap(related);
        }
    }

    public record Overview(
            Map<String, Long> counts,
            Map<String, Object> activeStrategy,
            Map<String, Object> latestPipeline,
            Map<String, Object> trainingReadiness
    ) {
        public Overview {
            counts = counts == null ? Map.of() : Map.copyOf(counts);
            activeStrategy = immutableMap(activeStrategy);
            latestPipeline = immutableMap(latestPipeline);
            trainingReadiness = immutableMap(trainingReadiness);
        }
    }

    public record ActionRequest(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Long strategyReleaseId,
            Long modelVersionId,
            Long parentPipelineRunId,
            Long userId,
            String idempotencyKey,
            Integer historyTradingDays,
            Integer historyStockCount
    ) {
        public ActionRequest {
            idempotencyKey = trim(idempotencyKey);
        }

        public ActionRequest(
                LocalDate tradeDate,
                LocalDate startDate,
                LocalDate endDate,
                Long strategyReleaseId,
                Long modelVersionId,
                Long parentPipelineRunId,
                Long userId,
                String idempotencyKey
        ) {
            this(tradeDate, startDate, endDate, strategyReleaseId, modelVersionId,
                    parentPipelineRunId, userId, idempotencyKey, null, null);
        }
    }

    public record GovernanceRequest(
            String assessmentEventKey,
            Long previousChampionReleaseId,
            Long shadowEvaluationId,
            Integer criticalDriftCount,
            String degradationFingerprint,
            String policyVersion,
            String reason,
            String idempotencyKey
    ) {
        public GovernanceRequest {
            assessmentEventKey = trim(assessmentEventKey);
            degradationFingerprint = trim(degradationFingerprint);
            policyVersion = trim(policyVersion);
            reason = trim(reason);
            idempotencyKey = trim(idempotencyKey);
        }
    }

    public record ActionAccepted(Long pipelineRunId, String status) {
        public ActionAccepted {
            if (pipelineRunId == null || pipelineRunId <= 0) {
                throw new IllegalArgumentException("异步操作缺少 pipelineRunId");
            }
            status = upper(status);
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String upper(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isBlank() ? trimmed : trimmed.toUpperCase(java.util.Locale.ROOT);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
