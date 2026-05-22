package com.maogou.stock.dto.settings;

import java.math.BigDecimal;

public record ModelConfigResponse(
        String apiBaseUrl,
        String modelName,
        String apiKeyMasked,
        Integer timeout,
        BigDecimal temperature,
        Integer maxTokens,
        Integer intradayInterval,
        String closeTime,
        String analysisScope,
        String promptTemplate
) {
}
