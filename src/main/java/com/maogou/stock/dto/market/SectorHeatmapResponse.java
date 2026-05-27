package com.maogou.stock.dto.market;

import java.time.LocalDateTime;
import java.util.List;

public record SectorHeatmapResponse(
        List<SectorHeatmapItemResponse> items,
        LocalDateTime updatedAt
) {
}
