package com.maogou.stock.dto.portfolio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TradeRecordCreateRequest(
        @NotBlank String code,
        @NotNull @Positive BigDecimal buyPrice,
        @NotNull @Positive Integer quantity,
        @NotNull LocalDateTime buyTime
) {
}
