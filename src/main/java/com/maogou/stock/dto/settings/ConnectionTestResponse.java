package com.maogou.stock.dto.settings;

public record ConnectionTestResponse(
        boolean success,
        String message,
        long latencyMs
) {
}
