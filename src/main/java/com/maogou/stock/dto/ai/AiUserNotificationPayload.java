package com.maogou.stock.dto.ai;

import com.maogou.stock.domain.entity.AiUserNotification;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AiUserNotificationPayload(
        Long id,
        String type,
        String level,
        String title,
        String content,
        Long reportId,
        LocalDate tradeDate,
        boolean read,
        LocalDateTime createdAt
) {
    public static AiUserNotificationPayload from(AiUserNotification item) {
        return new AiUserNotificationPayload(
                item.id, item.notificationType, item.level, item.title, item.content,
                item.reportId, item.tradeDate, item.isRead != null && item.isRead == 1, item.createdAt);
    }
}
