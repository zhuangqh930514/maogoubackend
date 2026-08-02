package com.maogou.stock.service.research;

import com.maogou.stock.dto.research.HistoricalFastStartPayloads;

import java.time.LocalDateTime;

public interface AiHistoricalFastStartService {

    HistoricalFastStartPayloads.PreviewResult preview(
            HistoricalFastStartPayloads.PreviewRequest request,
            Long operatorUserId
    );

    HistoricalFastStartPayloads.RunView create(
            HistoricalFastStartPayloads.CreateRequest request,
            String idempotencyHeader,
            Long operatorUserId
    );

    /**
     * Compatibility bridge for the existing research-lab action. It creates
     * the new run/shard record while preserving the already-created legacy
     * pipeline run ID returned to the old frontend.
     */
    HistoricalFastStartPayloads.RunView createLegacy(LegacyCreateCommand command);

    HistoricalFastStartPayloads.RunView getRun(Long runId, Long operatorUserId);

    HistoricalFastStartPayloads.PageResult<HistoricalFastStartPayloads.ShardView> listShards(
            Long runId,
            HistoricalFastStartPayloads.ShardQuery query,
            Long operatorUserId
    );

    HistoricalFastStartPayloads.PageResult<HistoricalFastStartPayloads.IssueView> listIssues(
            Long runId,
            HistoricalFastStartPayloads.IssueQuery query,
            Long operatorUserId
    );

    HistoricalFastStartPayloads.RunView pause(Long runId, Long operatorUserId);

    HistoricalFastStartPayloads.RunView resume(Long runId, Long operatorUserId);

    HistoricalFastStartPayloads.RunView retryFailed(Long runId, Long operatorUserId);

    HistoricalFastStartPayloads.RunView cancel(Long runId, Long operatorUserId);

    HistoricalFastStartPayloads.ReadinessView validate(Long runId, Long operatorUserId);

    HistoricalFastStartPayloads.ReadinessView latestReadiness(Long operatorUserId);

    record LegacyCreateCommand(
            AiHistoricalEvidenceImportService.ColdStartPlan plan,
            Long strategyReleaseId,
            Long modelVersionId,
            String idempotencyKey,
            Long pipelineRunId,
            Long operatorUserId,
            LocalDateTime requestedAt
    ) {
    }
}
