package com.maogou.stock.dto.market;

import java.time.LocalDateTime;
import java.util.List;

public record SectorHeatmapResponse(
        List<SectorHeatmapItemResponse> items,
        LocalDateTime updatedAt,
        String sourceStatus,
        String source,
        LocalDateTime sourceUpdatedAt,
        LocalDateTime servedAt,
        String message
) {
    public SectorHeatmapResponse(List<SectorHeatmapItemResponse> items, LocalDateTime updatedAt) {
        this(items, updatedAt, "REALTIME", "EASTMONEY", updatedAt, LocalDateTime.now(), "");
    }

    public static SectorHeatmapResponse realtime(List<SectorHeatmapItemResponse> items, LocalDateTime sourceUpdatedAt) {
        return new SectorHeatmapResponse(items, sourceUpdatedAt, "REALTIME", "EASTMONEY", sourceUpdatedAt, LocalDateTime.now(), "东方财富实时板块数据");
    }

    public static SectorHeatmapResponse stale(List<SectorHeatmapItemResponse> items, LocalDateTime sourceUpdatedAt, String message) {
        return new SectorHeatmapResponse(items, sourceUpdatedAt, "STALE", "EASTMONEY", sourceUpdatedAt, LocalDateTime.now(), message);
    }

    public static SectorHeatmapResponse unavailable(String message) {
        LocalDateTime now = LocalDateTime.now();
        return new SectorHeatmapResponse(List.of(), now, "UNAVAILABLE", "EASTMONEY", null, now, message);
    }
}
