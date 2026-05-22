package com.maogou.stock.dto.portfolio;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TradeRecordResponse(
        Long id,
        String code,
        String name,
        BigDecimal buyPrice,
        Integer quantity,
        LocalDateTime buyTime
) {
}
