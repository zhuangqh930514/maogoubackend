package com.maogou.stock.infrastructure.ai;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.AiModelConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates all in-process requests sent to the same configured model endpoint.
 *
 * A user config is private, but several users may point at the same provider/key. The limiter
 * therefore keys on the effective endpoint configuration rather than the user id so a batch job
 * cannot overwhelm a shared third-party model quota.
 */
@Component
public class AiModelRequestLimiter {

    private final AppProperties properties;
    private final ConcurrentHashMap<EndpointKey, EndpointState> endpointStates = new ConcurrentHashMap<>();

    public AiModelRequestLimiter(AppProperties properties) {
        this.properties = properties;
    }

    public Permit acquire(AiModelConfig config) {
        EndpointKey key = EndpointKey.from(config, properties.getAi());
        EndpointState state = endpointStates.computeIfAbsent(key,
                ignored -> new EndpointState(properties.getAi().getMaxConcurrentRequests()));
        long now = System.currentTimeMillis();
        long blockedUntil = state.blockedUntilMillis.get();
        if (blockedUntil > now) {
            throw state.providerUnavailable.get()
                    ? providerCooldownException(key, blockedUntil - now, null)
                    : cooldownException(key, blockedUntil - now, null);
        }
        try {
            if (!state.permits.tryAcquire(properties.getAi().getQueueWaitMs(), TimeUnit.MILLISECONDS)) {
                throw new AiModelRateLimitException(
                        "模型请求队列繁忙，系统将在稍后自动重试。模型=" + key.modelName,
                        properties.getAi().getRetryBaseDelayMs(), null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiModelRateLimitException("模型请求等待被中断，系统将在稍后自动重试。模型=" + key.modelName,
                    properties.getAi().getRetryBaseDelayMs(), exception);
        }
        blockedUntil = state.blockedUntilMillis.get();
        if (blockedUntil > System.currentTimeMillis()) {
            state.permits.release();
            throw state.providerUnavailable.get()
                    ? providerCooldownException(key, blockedUntil - System.currentTimeMillis(), null)
                    : cooldownException(key, blockedUntil - System.currentTimeMillis(), null);
        }
        return new Permit(state.permits);
    }

    public void recordSuccess(AiModelConfig config) {
        EndpointKey key = EndpointKey.from(config, properties.getAi());
        EndpointState state = endpointStates.computeIfAbsent(key,
                ignored -> new EndpointState(properties.getAi().getMaxConcurrentRequests()));
        state.consecutiveTransientFailures.set(0);
        state.providerUnavailable.set(false);
        state.blockedUntilMillis.set(0L);
    }

    public void recordRateLimit(AiModelConfig config, Duration retryAfter, Throwable cause) {
        EndpointKey key = EndpointKey.from(config, properties.getAi());
        EndpointState state = endpointStates.computeIfAbsent(key,
                ignored -> new EndpointState(properties.getAi().getMaxConcurrentRequests()));
        long requestedDelay = retryAfter == null ? 0L : retryAfter.toMillis();
        long delay = Math.max(properties.getAi().getRetryBaseDelayMs(), requestedDelay);
        delay = Math.min(delay, properties.getAi().getRetryMaxDelayMs());
        long until = System.currentTimeMillis() + delay;
        state.providerUnavailable.set(false);
        state.consecutiveTransientFailures.set(0);
        state.blockedUntilMillis.accumulateAndGet(until, Math::max);
    }

    /**
     * Stop a failing endpoint from being hammered by scheduled report jobs. The first
     * transient failure is returned to the caller for normal retry handling; once the
     * threshold is reached, subsequent callers fail fast during the short circuit window.
     */
    public AiModelRateLimitException recordTransientFailure(AiModelConfig config, Throwable cause) {
        EndpointKey key = EndpointKey.from(config, properties.getAi());
        EndpointState state = endpointStates.computeIfAbsent(key,
                ignored -> new EndpointState(properties.getAi().getMaxConcurrentRequests()));
        int failures = state.consecutiveTransientFailures.incrementAndGet();
        int threshold = Math.max(1, properties.getAi().getTransientFailureThreshold());
        if (failures < threshold) {
            return null;
        }
        long base = Math.max(1L, properties.getAi().getProviderCooldownBaseMs());
        long maximum = Math.max(base, properties.getAi().getProviderCooldownMaxMs());
        int exponent = Math.min(10, Math.max(0, failures - threshold));
        long delay = Math.min(maximum, base * (1L << exponent));
        state.providerUnavailable.set(true);
        state.blockedUntilMillis.accumulateAndGet(System.currentTimeMillis() + delay, Math::max);
        return providerCooldownException(key, delay, cause);
    }

    public AiModelRateLimitException cooldownException(AiModelConfig config, Duration retryAfter, Throwable cause) {
        EndpointKey key = EndpointKey.from(config, properties.getAi());
        long requestedDelay = retryAfter == null ? 0L : retryAfter.toMillis();
        long delay = Math.max(properties.getAi().getRetryBaseDelayMs(), requestedDelay);
        return cooldownException(key, Math.min(delay, properties.getAi().getRetryMaxDelayMs()), cause);
    }

    private static AiModelRateLimitException cooldownException(EndpointKey key, long delayMillis, Throwable cause) {
        long seconds = Math.max(1L, (delayMillis + 999L) / 1000L);
        return new AiModelRateLimitException(
                "模型接口触发限流，系统将在约 " + seconds + " 秒后自动重试。模型=" + key.modelName,
                delayMillis, cause);
    }

    private static AiModelRateLimitException providerCooldownException(
            EndpointKey key, long delayMillis, Throwable cause) {
        long seconds = Math.max(1L, (delayMillis + 999L) / 1000L);
        return new AiModelRateLimitException(
                "模型服务连续失败，已暂时熔断，约 " + seconds + " 秒后自动重试。模型=" + key.modelName,
                delayMillis, cause);
    }

    record EndpointKey(String baseUrl, String modelName, String apiKey) {
        static EndpointKey from(AiModelConfig config, AppProperties.Ai defaults) {
            return new EndpointKey(
                    firstNonBlank(config == null ? null : config.apiBaseUrl, defaults.getApiBaseUrl()),
                    firstNonBlank(config == null ? null : config.modelName, defaults.getModelName()),
                    firstNonBlank(config == null ? null : config.apiKey, defaults.getApiKey()));
        }

        private static String firstNonBlank(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private static final class EndpointState {
        private final Semaphore permits;
        private final AtomicLong blockedUntilMillis = new AtomicLong();
        private final AtomicInteger consecutiveTransientFailures = new AtomicInteger();
        private final AtomicBoolean providerUnavailable = new AtomicBoolean();

        private EndpointState(int maxConcurrentRequests) {
            this.permits = new Semaphore(Math.max(1, maxConcurrentRequests), true);
        }
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore permits;
        private boolean released;

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                permits.release();
            }
        }
    }
}
