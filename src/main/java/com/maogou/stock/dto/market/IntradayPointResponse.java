package com.maogou.stock.dto.market;

import java.math.BigDecimal;

public record IntradayPointResponse(
        String time,
        BigDecimal value,
        BigDecimal volume
) {
}
