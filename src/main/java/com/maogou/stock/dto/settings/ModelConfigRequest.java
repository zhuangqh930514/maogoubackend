package com.maogou.stock.dto.settings;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record ModelConfigRequest(
        @NotBlank String apiBaseUrl,
        @NotBlank String modelName,
        String apiKey,
        @Min(1000) Integer timeout,
        @DecimalMin("0.0") @DecimalMax("2.0") BigDecimal temperature,
        @Min(256) Integer maxTokens,
        @Min(5) Integer intradayInterval,
        String closeTime,
        String analysisScope,
        String promptTemplate
) {
}
