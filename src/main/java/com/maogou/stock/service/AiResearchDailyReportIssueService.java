package com.maogou.stock.service;

import com.maogou.stock.domain.entity.research.AiPipelineIssue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiResearchDailyReportIssueService {

    IssuePage page(Long reportId, int page, int pageSize);

    record IssueView(
            Long id,
            Long pipelineRunId,
            LocalDate tradeDate,
            String stepKey,
            String stepName,
            String stockCode,
            String stockName,
            String providerCode,
            String endpointType,
            String reasonCode,
            String reasonMessage,
            Integer retryCount,
            Integer maxRetries,
            LocalDateTime nextRetryAt,
            Boolean recoverable,
            LocalDateTime sourceAsOf,
            Integer attemptNo
    ) {
        public static IssueView from(AiPipelineIssue issue) {
            return new IssueView(issue.id, issue.pipelineRunId, issue.tradeDate, issue.stepKey,
                    issue.stepName, emptyToNull(issue.stockCode), emptyToNull(issue.stockName),
                    emptyToNull(issue.providerCode), emptyToNull(issue.endpointType), issue.reasonCode,
                    issue.reasonMessage, issue.retryCount, issue.maxRetries, issue.nextRetryAt,
                    issue.recoverable != null && issue.recoverable == 1, issue.sourceAsOf, issue.attemptNo);
        }

        private static String emptyToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    record IssuePage(
            List<IssueView> items,
            long total,
            int page,
            int pageSize,
            int totalPages
    ) {
        public static IssuePage empty(int page, int pageSize) {
            return new IssuePage(List.of(), 0, Math.max(1, page), Math.max(1, pageSize), 0);
        }
    }
}
