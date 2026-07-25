package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiUserNotification;
import com.maogou.stock.dto.ai.AiUserNotificationPayload;
import com.maogou.stock.mapper.AiUserNotificationMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.AiUserNotificationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AiUserNotificationServiceImpl implements AiUserNotificationService {

    private final AiUserNotificationMapper notificationMapper;

    public AiUserNotificationServiceImpl(AiUserNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @Override
    public void publishDailyReport(Long userId, AiResearchDailyReportService.ReportView report) {
        if (userId == null || userId <= 0 || report == null || report.id() == null || report.tradeDate() == null) {
            return;
        }
        String day = report.tradeDate().toString();
        publish(userId, report, "DAILY_REPORT_READY", "INFO", "投研日报已更新",
                day + " 投研日报已归档，可查看今日结论与条件计划。");
        if (value(report.holdingRiskCount()) > 0) {
            publish(userId, report, "HOLDING_RISK", "DANGER", "持仓需要优先处理",
                    day + " 有 " + report.holdingRiskCount() + " 只持仓触发风险或保护条件，请先查看投研日报。");
        }
        if (value(report.recommendationCount()) > 0) {
            publish(userId, report, "RECOMMENDATION", "WARNING", "发现条件型关注机会",
                    day + " 有 " + report.recommendationCount() + " 只股票满足关注条件；请按日报中的“如果 A，则执行 B”计划处理。");
        }
        if ("PARTIAL_READY".equals(report.reportStatus()) || "DATA_UNAVAILABLE".equals(report.reportStatus())
                || "FAILED_PIPELINE".equals(report.reportStatus())) {
            publish(userId, report, "PIPELINE_ATTENTION", "WARNING", "投研日报存在数据或任务限制",
                    day + " 日报已保留可追溯结果，但部分数据不可用或流水线未完整完成；不可将其视为完整收盘结论。");
        }
    }

    @Override
    public List<AiUserNotificationPayload> recent(int limit) {
        int resolvedLimit = limit <= 0 ? 20 : Math.min(limit, 50);
        return notificationMapper.selectRecent(AuthContext.currentUserIdOrDefault(), resolvedLimit).stream()
                .map(AiUserNotificationPayload::from)
                .toList();
    }

    @Override
    public long unreadCount() {
        return notificationMapper.countUnread(AuthContext.currentUserIdOrDefault());
    }

    @Override
    public void markRead(Long notificationId) {
        if (notificationId == null || notificationId <= 0) {
            throw new IllegalArgumentException("通知 ID 无效");
        }
        notificationMapper.markRead(AuthContext.currentUserIdOrDefault(), notificationId, LocalDateTime.now());
    }

    private void publish(
            Long userId,
            AiResearchDailyReportService.ReportView report,
            String type,
            String level,
            String title,
        String content
    ) {
        String key = "DAILY_REPORT:" + report.tradeDate() + ":" + type;
        AiUserNotification notification = notificationMapper.selectByDedupeKey(userId, key);
        LocalDateTime now = LocalDateTime.now();
        if (notification == null) {
            notification = new AiUserNotification();
            notification.userId = userId;
            notification.notificationType = type;
            notification.dedupeKey = key;
            notification.isRead = 0;
            notification.createdAt = now;
        }
        notification.level = level;
        notification.title = title;
        notification.content = content;
        notification.reportId = report.id();
        notification.tradeDate = report.tradeDate();
        notification.updatedAt = now;
        if (notification.id == null) {
            notificationMapper.insert(notification);
        } else {
            notificationMapper.updateById(notification);
        }
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
