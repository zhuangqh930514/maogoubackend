package com.maogou.stock.dto.portfolio;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchPortfolioPositionRequest(
        @NotEmpty List<String> codes
) {
}
