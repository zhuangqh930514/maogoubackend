package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiWatchlistAnalysisJob;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.ai.WatchlistAnalysisJobResponse;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.mapper.AiWatchlistAnalysisJobMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.WatchlistService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistAnalysisJobServiceImplTest {

    @Test
    void returnsTheExistingActiveJobWithoutSubmittingDuplicateWork() {
        AiWatchlistAnalysisJobMapper mapper = mock(AiWatchlistAnalysisJobMapper.class);
        WatchlistService watchlistService = mock(WatchlistService.class);
        AiAnalysisService analysisService = mock(AiAnalysisService.class);
        AtomicBoolean submitted = new AtomicBoolean();
        TaskExecutor executor = task -> submitted.set(true);
        AiWatchlistAnalysisJob existing = job(81L, 5L, "RUNNING");
        existing.totalCount = 8;
        existing.completedCount = 3;
        when(mapper.selectActive(5L)).thenReturn(existing);
        WatchlistAnalysisJobServiceImpl service = new WatchlistAnalysisJobServiceImpl(
                mapper, watchlistService, analysisService, executor);

        WatchlistAnalysisJobResponse response = AuthContext.callAs(5L, () -> service.submit(null));

        assertThat(response.id()).isEqualTo(81L);
        assertThat(response.progressPercent()).isEqualTo(37);
        verify(mapper, never()).insert(any(AiWatchlistAnalysisJob.class));
        assertThat(submitted).isFalse();
    }

    @Test
    void runsInTheSubmittingUsersContextAndContinuesAfterAStockFailure() {
        AiWatchlistAnalysisJobMapper mapper = mock(AiWatchlistAnalysisJobMapper.class);
        WatchlistService watchlistService = mock(WatchlistService.class);
        AiAnalysisService analysisService = mock(AiAnalysisService.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        TaskExecutor executor = submitted::set;
        when(mapper.selectActive(5L)).thenReturn(null);
        when(mapper.insert(any(AiWatchlistAnalysisJob.class))).thenAnswer(invocation -> {
            AiWatchlistAnalysisJob value = invocation.getArgument(0);
            value.id = 91L;
            return 1;
        });
        when(watchlistService.list("全部")).thenAnswer(invocation -> {
            assertThat(AuthContext.currentUserIdOrDefault()).isEqualTo(5L);
            return List.of(
                    stock("600519", "贵州茅台"),
                    stock("300058", "蓝色光标")
            );
        });
        when(analysisService.analyzeStock("600519", false, null, null))
                .thenReturn(report("600519", "SUCCESS", null));
        when(analysisService.analyzeStock("300058", false, null, null))
                .thenReturn(report("300058", "FAILED", "大模型返回内容无法解析"));
        WatchlistAnalysisJobServiceImpl service = new WatchlistAnalysisJobServiceImpl(
                mapper, watchlistService, analysisService, executor);

        WatchlistAnalysisJobResponse response = AuthContext.callAs(5L, () -> service.submit(null));
        submitted.get().run();

        assertThat(response.id()).isEqualTo(91L);
        assertThat(response.status()).isEqualTo("PENDING");
        verify(mapper).recordProgress(
                eq(91L), eq(5L), eq(1), eq(0), eq(0),
                eq("贵州茅台 600519 分析完成（1/2）"), isNull(), any(LocalDateTime.class));
        verify(mapper).recordProgress(
                eq(91L), eq(5L), eq(0), eq(0), eq(1),
                eq("蓝色光标 300058 分析失败，继续处理下一只（2/2）"),
                eq("股票：300058 蓝色光标｜步骤：生成 AI 分析报告｜数据提供方：用户配置的大模型｜失败原因：大模型返回内容无法解析"),
                any(LocalDateTime.class));
        verify(mapper).markFinished(
                eq(91L), eq(5L), eq("PARTIAL"), eq("自选股分析完成：成功 1 只，跳过 0 只，失败 1 只"),
                any(LocalDateTime.class));
    }

    @Test
    void exposesRealProgressAndMarksTerminalJobsAtOneHundredPercent() {
        AiWatchlistAnalysisJob running = job(101L, 5L, "RUNNING");
        running.totalCount = 9;
        running.completedCount = 4;
        AiWatchlistAnalysisJob finished = job(102L, 5L, "PARTIAL");
        finished.totalCount = 9;
        finished.completedCount = 8;

        assertThat(WatchlistAnalysisJobResponse.from(running).progressPercent()).isEqualTo(44);
        assertThat(WatchlistAnalysisJobResponse.from(running).terminal()).isFalse();
        assertThat(WatchlistAnalysisJobResponse.from(finished).progressPercent()).isEqualTo(100);
        assertThat(WatchlistAnalysisJobResponse.from(finished).terminal()).isTrue();
    }

    private static AiWatchlistAnalysisJob job(Long id, Long userId, String status) {
        AiWatchlistAnalysisJob value = new AiWatchlistAnalysisJob();
        value.id = id;
        value.userId = userId;
        value.status = status;
        value.activeKey = status.equals("RUNNING") || status.equals("PENDING") ? "USER:" + userId : null;
        value.totalCount = 0;
        value.completedCount = 0;
        value.analyzedCount = 0;
        value.skippedCount = 0;
        value.failedCount = 0;
        value.createdAt = LocalDateTime.of(2026, 7, 24, 10, 0);
        value.updatedAt = value.createdAt;
        return value;
    }

    private static WatchStockResponse stock(String code, String name) {
        return new WatchStockResponse(
                1L, code, name, null, null, null, null, null,
                null, null, null, null, "全部");
    }

    private static AiAnalysisReportResponse report(String code, String status, String errorMessage) {
        return new AiAnalysisReportResponse(
                1L,
                code,
                code,
                70,
                "观察",
                LocalDateTime.of(2026, 7, 24, 10, 0),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                "test-model",
                status,
                errorMessage,
                11L,
                21L,
                1,
                null,
                null,
                null
        );
    }
}
