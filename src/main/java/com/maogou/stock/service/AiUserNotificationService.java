package com.maogou.stock.service;

import com.maogou.stock.dto.ai.AiUserNotificationPayload;

import java.util.List;

public interface AiUserNotificationService {

    void publishDailyReport(Long userId, AiResearchDailyReportService.ReportView report);

    List<AiUserNotificationPayload> recent(int limit);

    long unreadCount();

    void markRead(Long notificationId);
}
