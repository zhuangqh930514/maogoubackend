package com.maogou.stock.dto.watchlist;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchWatchStockRequest(
        @NotEmpty List<String> codes
) {
}
