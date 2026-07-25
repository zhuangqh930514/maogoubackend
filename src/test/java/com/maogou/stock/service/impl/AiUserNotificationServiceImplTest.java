package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiUserNotification;
import com.maogou.stock.mapper.AiUserNotificationMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.AiUserNotificationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiUserNotificationServiceImplTest {

    @Test
    void persistsActionableAndDeDuplicatedDailyReportNotifications() {
        AiUserNotificationMapper mapper = mock(AiUserNotificationMapper.class);
        AiUserNotificationService service = new AiUserNotificationServiceImpl(mapper);
        when(mapper.selectByDedupeKey(anyLong(), any())).thenReturn(null);

        service.publishDailyReport(5L, report("PARTIAL_READY", 1, 2));

        var captor = org.mockito.ArgumentCaptor.forClass(AiUserNotification.class);
        verify(mapper, times(4)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(item -> {
            assertThat(item.userId).isEqualTo(5L);
            assertThat(item.dedupeKey).startsWith("DAILY_REPORT:2026-07-25:");
            assertThat(item.reportId).isEqualTo(88L);
        });
        assertThat(captor.getAllValues()).extracting(item -> item.notificationType)
                .containsExactlyInAnyOrder("DAILY_REPORT_READY", "HOLDING_RISK", "RECOMMENDATION", "PIPELINE_ATTENTION");
    }

    @Test
    void returnsOnlyAuthenticatedUsersNotificationsAndMarksTheOwnedItemRead() {
        AiUserNotificationMapper mapper = mock(AiUserNotificationMapper.class);
        AiUserNotificationService service = new AiUserNotificationServiceImpl(mapper);
        AiUserNotification item = new AiUserNotification();
        item.id = 11L;
        item.userId = 5L;
        item.notificationType = "DAILY_REPORT_READY";
        item.title = "投研日报已更新";
        item.content = "日报已归档";
        item.isRead = 0;
        item.createdAt = LocalDateTime.of(2026, 7, 25, 16, 10);
        when(mapper.selectRecent(5L, 20)).thenReturn(List.of(item));

        List<?> notifications = AuthContext.callAs(5L, () -> service.recent(20));
        AuthContext.runAs(5L, () -> service.markRead(11L));

        assertThat(notifications).hasSize(1);
        verify(mapper).markRead(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq(11L), any());
    }

    private static AiResearchDailyReportService.ReportView report(
            String status,
            int recommendationCount,
            int holdingRiskCount
    ) {
        return new AiResearchDailyReportService.ReportView(
                88L, 41L, LocalDate.of(2026, 7, 25), 1, 71L, 1L, null,
                null, true, status, "投研日报", "已归档", "BALANCED",
                recommendationCount, 20, 0, holdingRiskCount, "CURRENT_CLOSE",
                new BigDecimal("95"), null, null, LocalDateTime.of(2026, 7, 25, 16, 10));
    }
}
