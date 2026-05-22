package com.maogou.stock.dto.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record ModelConfigRequest(
        @NotBlank String apiBaseUrl,
        @NotBlank String modelName,
        String apiKey,
        @Min(1000) Integer timeout,
        @Min(0) @Max(1) BigDecimal temperature,
        @Min(256) Integer maxTokens,
        @Min(5) Integer intradayInterval,
        String closeTime,
        String analysisScope,
        String promptTemplate
) {
}
