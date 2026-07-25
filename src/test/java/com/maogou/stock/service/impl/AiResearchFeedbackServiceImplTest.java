package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiResearchUserFeedback;
import com.maogou.stock.domain.entity.research.AiResearchDailyReport;
import com.maogou.stock.dto.ai.AiResearchFeedbackPayload;
import com.maogou.stock.mapper.AiResearchUserFeedbackMapper;
import com.maogou.stock.mapper.research.AiResearchDailyReportMapper;
import com.maogou.stock.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiResearchFeedbackServiceImplTest {

    @Test
    void savesFeedbackOnlyForTheCurrentUsersReport() {
        AiResearchDailyReportMapper reportMapper = mock(AiResearchDailyReportMapper.class);
        AiResearchUserFeedbackMapper feedbackMapper = mock(AiResearchUserFeedbackMapper.class);
        AiResearchDailyReport report = report(71L, 5L);
        AiResearchUserFeedback stored = feedback(71L, 5L, "600519", "HELPFUL");
        stored.comment = "结论清晰";
        when(reportMapper.selectById(71L)).thenReturn(report);
        when(feedbackMapper.selectByReportAndStock(5L, 71L, "600519"))
                .thenReturn(stored);
        AiResearchFeedbackServiceImpl service = new AiResearchFeedbackServiceImpl(reportMapper, feedbackMapper);

        AiResearchFeedbackPayload.Item saved = AuthContext.callAs(5L, () -> service.submit(71L,
                new AiResearchFeedbackPayload.SubmitRequest("600519", "helpful", "  结论清晰  ")));

        assertThat(saved.feedbackType()).isEqualTo("HELPFUL");
        assertThat(saved.comment()).isEqualTo("结论清晰");
        verify(feedbackMapper).upsert(any(AiResearchUserFeedback.class));
        verify(feedbackMapper).selectByReportAndStock(5L, 71L, "600519");
    }

    @Test
    void rejectsFeedbackForAnotherUsersReportBeforeWriting() {
        AiResearchDailyReportMapper reportMapper = mock(AiResearchDailyReportMapper.class);
        AiResearchUserFeedbackMapper feedbackMapper = mock(AiResearchUserFeedbackMapper.class);
        when(reportMapper.selectById(71L)).thenReturn(report(71L, 6L));
        AiResearchFeedbackServiceImpl service = new AiResearchFeedbackServiceImpl(reportMapper, feedbackMapper);

        assertThatThrownBy(() -> AuthContext.callAs(5L, () -> service.submit(71L,
                new AiResearchFeedbackPayload.SubmitRequest("600519", "HELPFUL", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("日报不存在");
        verify(feedbackMapper, never()).upsert(any());
    }

    @Test
    void rejectsUnknownFeedbackTypesAndDoesNotPersistThem() {
        AiResearchDailyReportMapper reportMapper = mock(AiResearchDailyReportMapper.class);
        AiResearchUserFeedbackMapper feedbackMapper = mock(AiResearchUserFeedbackMapper.class);
        when(reportMapper.selectById(71L)).thenReturn(report(71L, 5L));
        AiResearchFeedbackServiceImpl service = new AiResearchFeedbackServiceImpl(reportMapper, feedbackMapper);

        assertThatThrownBy(() -> AuthContext.callAs(5L, () -> service.submit(71L,
                new AiResearchFeedbackPayload.SubmitRequest("600519", "BUY", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("反馈类型无效");
        verify(feedbackMapper, never()).upsert(any());
    }

    @Test
    void listsOnlyRowsOwnedByTheAuthenticatedUser() {
        AiResearchDailyReportMapper reportMapper = mock(AiResearchDailyReportMapper.class);
        AiResearchUserFeedbackMapper feedbackMapper = mock(AiResearchUserFeedbackMapper.class);
        when(reportMapper.selectById(71L)).thenReturn(report(71L, 5L));
        when(feedbackMapper.selectByReport(5L, 71L)).thenReturn(List.of(
                feedback(71L, 5L, "600519", "UNCLEAR")));
        AiResearchFeedbackServiceImpl service = new AiResearchFeedbackServiceImpl(reportMapper, feedbackMapper);

        List<AiResearchFeedbackPayload.Item> rows = AuthContext.callAs(5L, () -> service.list(71L));

        assertThat(rows).singleElement().satisfies(item -> {
            assertThat(item.stockCode()).isEqualTo("600519");
            assertThat(item.feedbackType()).isEqualTo("UNCLEAR");
        });
        verify(feedbackMapper).selectByReport(eq(5L), eq(71L));
    }

    private static AiResearchDailyReport report(Long id, Long userId) {
        AiResearchDailyReport entity = new AiResearchDailyReport();
        entity.id = id;
        entity.userId = userId;
        return entity;
    }

    private static AiResearchUserFeedback feedback(Long reportId, Long userId, String stockCode, String type) {
        AiResearchUserFeedback entity = new AiResearchUserFeedback();
        entity.reportId = reportId;
        entity.userId = userId;
        entity.stockCode = stockCode;
        entity.feedbackType = type;
        entity.updatedAt = LocalDateTime.of(2026, 7, 25, 13, 30);
        return entity;
    }
}
