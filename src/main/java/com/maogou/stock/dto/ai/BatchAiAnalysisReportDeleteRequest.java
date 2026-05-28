package com.maogou.stock.dto.ai;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchAiAnalysisReportDeleteRequest(
        @NotEmpty List<Long> ids
) {
}
