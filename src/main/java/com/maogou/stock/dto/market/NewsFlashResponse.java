package com.maogou.stock.dto.market;

import java.time.LocalDateTime;

public record NewsFlashResponse(
        String time,
        String title,
        String source,
        String url,
        LocalDateTime publishedAt
) {
}
