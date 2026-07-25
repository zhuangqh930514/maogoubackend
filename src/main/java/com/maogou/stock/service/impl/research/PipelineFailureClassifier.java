package com.maogou.stock.service.impl.research;

import com.maogou.stock.infrastructure.ai.AiModelRateLimitException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Keeps retry decisions deterministic and visible. A failed pipeline must either have a concrete
 * persisted retry time or be explicitly final; a bare FAILED state is not a scheduling contract.
 */
final class PipelineFailureClassifier {

    private PipelineFailureClassifier() {
    }

    static FailureDisposition classify(
            String stepKey,
            Throwable failure,
            int retryCount,
            int maximumRetryAttempts,
            Duration baseDelay,
            Duration maximumDelay,
            LocalDateTime now
    ) {
        String reason = rootMessage(failure);
        int safeMaximum = Math.max(0, maximumRetryAttempts);
        int safeRetryCount = Math.max(0, retryCount);
        if (!isRecoverable(failure, reason)) {
            return finalFailure(stepKey, reason, safeRetryCount, safeMaximum);
        }
        if (safeRetryCount >= safeMaximum) {
            return finalFailure(stepKey, reason, safeRetryCount, safeMaximum);
        }

        long delaySeconds = exponentialDelaySeconds(baseDelay, maximumDelay, safeRetryCount);
        LocalDateTime retryAt = now.plusSeconds(delaySeconds);
        String retryState = "可恢复故障；已重试=" + safeRetryCount + "/" + safeMaximum
                + "；下次自动重试=" + retryAt;
        String summary = "步骤=" + stepKey + "；" + retryState + "；原因=" + reason;
        String detail = failureDetail(stepKey, reason, retryState);
        return new FailureDisposition("FAILED_RECOVERABLE", retryAt, summary, detail);
    }

    private static FailureDisposition finalFailure(
            String stepKey,
            String reason,
            int retryCount,
            int maximumRetryAttempts
    ) {
        String retryState = retryCount >= maximumRetryAttempts
                ? "自动重试已达上限=" + retryCount + "/" + maximumRetryAttempts
                : "不可自动恢复";
        String summary = "步骤=" + stepKey + "；最终失败；" + retryState + "；原因=" + reason;
        return new FailureDisposition(
                "FAILED_FINAL", null, summary, failureDetail(stepKey, reason, retryState));
    }

    private static String failureDetail(String stepKey, String reason, String retryState) {
        // A global step may not concern one stock or external provider. State that explicitly rather
        // than inventing a stock code or presenting an internal failure as a market-data failure.
        return "步骤=" + stepKey
                + "；股票代码=不适用（全局任务）"
                + "；数据提供方=" + provider(reason)
                + "；重试状态=" + retryState
                + "；失败原因=" + reason;
    }

    private static String provider(String reason) {
        String normalized = reason == null ? "" : reason.toUpperCase(Locale.ROOT);
        if (normalized.contains("EASTMONEY")) {
            return "东方财富";
        }
        if (normalized.contains("AKSHARE")) {
            return "AkShare";
        }
        if (normalized.contains("SINA")) {
            return "新浪财经";
        }
        if (normalized.contains("MYSQL") || normalized.contains("JDBC") || normalized.contains("DATABASE")) {
            return "MySQL";
        }
        if (normalized.contains("MODEL") || normalized.contains("OLLAMA") || normalized.contains("VLLM")
                || normalized.contains("429")) {
            return "大模型服务";
        }
        return "内部研究服务";
    }

    private static boolean isRecoverable(Throwable failure, String reason) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AiModelRateLimitException
                    || current instanceof ResourceAccessException
                    || current instanceof HttpServerErrorException
                    || current instanceof TransientDataAccessException
                    || current instanceof SQLTransientException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof InterruptedIOException
                    || current instanceof EOFException
                    || current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof HttpClientErrorException clientError
                    && clientError.getStatusCode().value() == 429) {
                return true;
            }
            current = current.getCause();
        }
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        return normalized.contains("lock wait timeout")
                || normalized.contains("deadlock")
                || normalized.contains("read timed out")
                || normalized.contains("connection reset")
                || normalized.contains("connection refused")
                || normalized.contains("communications link failure")
                || normalized.contains("failed to obtain jdbc connection")
                || normalized.contains("temporarily unavailable")
                || normalized.contains("暂时不可用")
                || normalized.contains("网络不可达")
                || normalized.contains("超时")
                || normalized.contains("429");
    }

    private static long exponentialDelaySeconds(Duration baseDelay, Duration maximumDelay, int retryCount) {
        long base = Math.max(1L, baseDelay == null ? 300L : baseDelay.toSeconds());
        long maximum = Math.max(base, maximumDelay == null ? 1800L : maximumDelay.toSeconds());
        int exponent = Math.min(20, Math.max(0, retryCount));
        long multiplier = 1L << exponent;
        if (base > Long.MAX_VALUE / multiplier) {
            return maximum;
        }
        return Math.min(maximum, base * multiplier);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        if (current == null || current.getMessage() == null || current.getMessage().isBlank()) {
            return current == null ? "未知异常" : current.getClass().getSimpleName();
        }
        return current.getMessage().trim();
    }

    record FailureDisposition(
            String runStatus,
            LocalDateTime nextRetryAt,
            String summary,
            String detail
    ) {
        boolean recoverable() {
            return "FAILED_RECOVERABLE".equals(runStatus);
        }
    }
}
