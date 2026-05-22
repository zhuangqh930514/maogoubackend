package com.maogou.stock.dto.watchlist;

import jakarta.validation.constraints.NotBlank;

public record AddWatchStockRequest(
        @NotBlank String code,
        String groupName
) {
}
