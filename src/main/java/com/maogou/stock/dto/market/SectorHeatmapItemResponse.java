package com.maogou.stock.dto.market;

import java.math.BigDecimal;

public record SectorHeatmapItemResponse(
        String code,
        String name,
        BigDecimal value,
        BigDecimal percent,
        BigDecimal netInflow,
        String direction,
        int rank
) {
}
