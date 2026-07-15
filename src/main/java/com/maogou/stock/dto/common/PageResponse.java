package com.maogou.stock.dto.common;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        long total,
        int page,
        int pageSize,
        int totalPages
) {
    public static <T> PageResponse<T> of(List<T> items, long total, int page, int pageSize) {
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PageResponse<>(items == null ? List.of() : List.copyOf(items), total, page, pageSize, totalPages);
    }
}
