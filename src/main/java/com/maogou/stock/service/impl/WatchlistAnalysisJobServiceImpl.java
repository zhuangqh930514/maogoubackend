package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiWatchlistAnalysisJob;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.ai.WatchlistAnalysisJobResponse;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.exception.FormalResearchSampleUnavailableException;
import com.maogou.stock.mapper.AiWatchlistAnalysisJobMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.WatchlistAnalysisJobService;
import com.maogou.stock.service.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class WatchlistAnalysisJobServiceImpl implements WatchlistAnalysisJobService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistAnalysisJobServiceImpl.class);
    private static final String PENDING = "PENDING";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCESS = "SUCCESS";
    private static final String PARTIAL = "PARTIAL";
    private static final String FAILED = "FAILED";
    private static final int MESSAGE_LIMIT = 500;
    private static final int ISSUE_LIMIT = 2_000;

    private final AiWatchlistAnalysisJobMapper jobMapper;
    private final WatchlistService watchlistService;
    private final AiAnalysisService aiAnalysisService;
    private final TaskExecutor taskExecutor;

    public WatchlistAnalysisJobServiceImpl(
            AiWatchlistAnalysisJobMapper jobMapper,
            WatchlistService watchlistService,
            AiAnalysisService aiAnalysisService,
            @Qualifier("watchlistAnalysisTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.jobMapper = jobMapper;
        this.watchlistService = watchlistService;
        this.aiAnalysisService = aiAnalysisService;
        this.taskExecutor = taskExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedJobs() {
        String reason = "服务重启导致后台分析中断，未完成的股票不会计为成功，请重新点击“立即分析自选股”";
        try {
            int recovered = jobMapper.recoverInterrupted(reason, LocalDateTime.now());
            if (recovered > 0) {
                log.warn("recovered interrupted watchlist analysis jobs count={}", recovered);
            }
        } catch (RuntimeException exception) {
            log.error("failed to recover interrupted watchlist analysis jobs: {}", exception.getMessage(), exception);
        }
    }

    @Override
    public WatchlistAnalysisJobResponse submit(Long promptTemplateId) {
        Long userId = AuthContext.currentUserIdOrDefault();
        AiWatchlistAnalysisJob active = jobMapper.selectActive(userId);
        if (active != null) {
            return WatchlistAnalysisJobResponse.from(active);
        }

        LocalDateTime now = LocalDateTime.now();
        AiWatchlistAnalysisJob job = new AiWatchlistAnalysisJob();
        job.userId = userId;
        job.promptTemplateId = normalizePromptTemplateId(promptTemplateId);
        job.status = PENDING;
        job.activeKey = "USER:" + userId;
        job.totalCount = 0;
        job.completedCount = 0;
        job.analyzedCount = 0;
        job.skippedCount = 0;
        job.failedCount = 0;
        job.message = "任务已提交，正在等待后台执行";
        job.createdAt = now;
        job.updatedAt = now;
        try {
            jobMapper.insert(job);
        } catch (DuplicateKeyException duplicate) {
            AiWatchlistAnalysisJob concurrent = jobMapper.selectActive(userId);
            if (concurrent != null) {
                return WatchlistAnalysisJobResponse.from(concurrent);
            }
            throw new IllegalStateException("自选股分析任务状态刚刚发生变化，请重新点击一次");
        }

        try {
            taskExecutor.execute(() -> AuthContext.runAs(userId,
                    () -> executeJob(job.id, userId, job.promptTemplateId)));
        } catch (RuntimeException exception) {
            String detail = failureDetail(null, "任务调度", "后台任务执行器", exception.getMessage());
            jobMapper.markFailed(job.id, userId, "后台任务队列已满，本次任务未启动", detail, LocalDateTime.now());
            throw new IllegalStateException("后台分析任务繁忙，本次任务未启动，请稍后重试");
        }
        return WatchlistAnalysisJobResponse.from(job);
    }

    @Override
    public WatchlistAnalysisJobResponse current() {
        Long userId = AuthContext.currentUserIdOrDefault();
        return WatchlistAnalysisJobResponse.from(jobMapper.selectActive(userId));
    }

    @Override
    public WatchlistAnalysisJobResponse detail(Long jobId) {
        if (jobId == null || jobId <= 0) {
            throw new IllegalArgumentException("任务 ID 不合法");
        }
        Long userId = AuthContext.currentUserIdOrDefault();
        AiWatchlistAnalysisJob job = jobMapper.selectOwned(jobId, userId);
        if (job == null) {
            throw new IllegalArgumentException("分析任务不存在或不属于当前用户");
        }
        return WatchlistAnalysisJobResponse.from(job);
    }

    private void executeJob(Long jobId, Long userId, Long promptTemplateId) {
        LocalDateTime startedAt = LocalDateTime.now();
        jobMapper.markRunning(jobId, userId, 0, "正在加载当前用户的自选股", startedAt);
        try {
            List<WatchStockResponse> watchlist = watchlistService.list("全部");
            int total = watchlist.size();
            jobMapper.markRunning(jobId, userId, total,
                    total == 0 ? "当前没有自选股，任务将直接结束" : "已加载 " + total + " 只自选股，准备逐只分析",
                    LocalDateTime.now());
            if (total == 0) {
                jobMapper.markFinished(jobId, userId, SUCCESS, "当前没有需要分析的自选股", LocalDateTime.now());
                return;
            }

            int analyzed = 0;
            int skipped = 0;
            int failed = 0;
            for (int index = 0; index < total; index++) {
                WatchStockResponse stock = watchlist.get(index);
                int position = index + 1;
                String stockLabel = stockLabel(stock);
                jobMapper.updateCurrentStock(
                        jobId,
                        userId,
                        stock.code(),
                        stock.name(),
                        "正在分析 " + stockLabel + "（" + position + "/" + total + "）",
                        LocalDateTime.now()
                );
                try {
                    AiAnalysisReportResponse report = aiAnalysisService.analyzeStock(
                            stock.code(), false, promptTemplateId, null);
                    if (report != null && "SUCCESS".equalsIgnoreCase(report.status())) {
                        analyzed++;
                        record(jobId, userId, 1, 0, 0,
                                stockLabel + " 分析完成（" + position + "/" + total + "）", null);
                    } else {
                        failed++;
                        String reason = report == null ? "分析服务未返回报告" : report.errorMessage();
                        String issue = failureDetail(stock, "生成 AI 分析报告", inferProvider(reason), reason);
                        record(jobId, userId, 0, 0, 1,
                                stockLabel + " 分析失败，继续处理下一只（" + position + "/" + total + "）", issue);
                    }
                } catch (FormalResearchSampleUnavailableException exception) {
                    skipped++;
                    String issue = failureDetail(
                            stock,
                            "读取正式收盘研究样本",
                            "正式研究样本库",
                            exception.getMessage() + "；重试状态：等待收盘流水线补齐后再提交"
                    );
                    record(jobId, userId, 0, 1, 0,
                            stockLabel + " 暂无正式样本，已跳过（" + position + "/" + total + "）", issue);
                } catch (RuntimeException exception) {
                    failed++;
                    String reason = rootMessage(exception);
                    String issue = failureDetail(stock, "生成 AI 分析报告", inferProvider(reason),
                            reason + "；重试状态：本批次不阻塞其他股票，可稍后重新提交");
                    log.error("watchlist analysis stock failed jobId={} userId={} code={} message={}",
                            jobId, userId, stock.code(), reason, exception);
                    record(jobId, userId, 0, 0, 1,
                            stockLabel + " 分析失败，继续处理下一只（" + position + "/" + total + "）", issue);
                }
            }

            String terminalStatus = failed == 0 && skipped == 0
                    ? SUCCESS
                    : analyzed == 0 ? FAILED : PARTIAL;
            String terminalMessage = "自选股分析完成：成功 " + analyzed
                    + " 只，跳过 " + skipped + " 只，失败 " + failed + " 只";
            jobMapper.markFinished(jobId, userId, terminalStatus, terminalMessage, LocalDateTime.now());
        } catch (RuntimeException exception) {
            String reason = rootMessage(exception);
            String detail = failureDetail(null, "加载自选股或执行批量任务", inferProvider(reason),
                    reason + "；重试状态：任务已停止，可重新提交");
            log.error("watchlist analysis job failed jobId={} userId={} message={}",
                    jobId, userId, reason, exception);
            jobMapper.markFailed(jobId, userId, "自选股分析任务异常终止：" + shortText(reason, 300),
                    detail, LocalDateTime.now());
        }
    }

    private void record(
            Long jobId,
            Long userId,
            int analyzedIncrement,
            int skippedIncrement,
            int failedIncrement,
            String message,
            String issueDetail
    ) {
        jobMapper.recordProgress(
                jobId,
                userId,
                analyzedIncrement,
                skippedIncrement,
                failedIncrement,
                shortText(message, MESSAGE_LIMIT),
                shortText(issueDetail, ISSUE_LIMIT),
                LocalDateTime.now()
        );
    }

    private static Long normalizePromptTemplateId(Long promptTemplateId) {
        return promptTemplateId == null || promptTemplateId <= 0 ? null : promptTemplateId;
    }

    private static String stockLabel(WatchStockResponse stock) {
        if (stock == null) {
            return "未知股票";
        }
        String name = stock.name() == null || stock.name().isBlank() ? stock.code() : stock.name();
        return name + " " + stock.code();
    }

    private static String failureDetail(
            WatchStockResponse stock,
            String step,
            String provider,
            String reason
    ) {
        String code = stock == null ? "-" : stock.code();
        String name = stock == null || stock.name() == null || stock.name().isBlank() ? "-" : stock.name();
        return shortText(
                "股票：" + code + " " + name
                        + "｜步骤：" + safeText(step)
                        + "｜数据提供方：" + safeText(provider)
                        + "｜失败原因：" + safeText(reason),
                ISSUE_LIMIT
        );
    }

    private static String inferProvider(String reason) {
        String normalized = safeText(reason).toLowerCase(Locale.ROOT);
        if (normalized.contains("东方财富") || normalized.contains("eastmoney")) {
            return "东方财富";
        }
        if (normalized.contains("新浪") || normalized.contains("sina")) {
            return "新浪财经";
        }
        if (normalized.contains("akshare")) {
            return "AkShare";
        }
        if (normalized.contains("ollama") || normalized.contains("vllm")
                || normalized.contains("model") || normalized.contains("大模型")
                || normalized.contains("openai")) {
            return "用户配置的大模型";
        }
        if (normalized.contains("数据库") || normalized.contains("jdbc")
                || normalized.contains("mysql") || normalized.contains("sql")) {
            return "猫狗智投数据库";
        }
        return "行情、研究样本或大模型服务";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "未提供具体原因" : value.trim();
    }

    private static String shortText(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit - 3)) + "...";
    }
}
