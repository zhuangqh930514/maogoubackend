package com.maogou.stock.infrastructure.market;

import com.maogou.stock.config.AppProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Provider retry and cooldown policy. It deliberately knows nothing about
 * stock business validation; callers validate the returned payload before
 * persisting formal evidence.
 */
@Component
public class HistoricalProviderRetryExecutorImpl implements HistoricalProviderRetryExecutor {

    private static final long[] DEFAULT_BACKOFF_SECONDS = {2, 5, 15, 30, 60};

    private final MarketSourceHealthRegistry healthRegistry;
    private final Clock clock;
    private final Sleeper sleeper;
    private final UnaryOperator<Duration> jitter;
    private final int maxAttempts;
    private final long maxDelayMs;

    public HistoricalProviderRetryExecutorImpl(
            MarketSourceHealthRegistry healthRegistry,
            AppProperties properties
    ) {
        this(healthRegistry, Clock.systemDefaultZone(),
                duration -> Thread.sleep(duration.toMillis()),
                HistoricalProviderRetryExecutorImpl::withJitter,
                properties == null ? 5 : properties.getMarket().getHistoricalProviderMaxAttempts(),
                properties == null ? 60_000L : properties.getMarket().getHistoricalRetryMaxDelayMs());
    }

    HistoricalProviderRetryExecutorImpl(
            MarketSourceHealthRegistry healthRegistry,
            Clock clock,
            Sleeper sleeper,
            UnaryOperator<Duration> jitter,
            int maxAttempts,
            long maxDelayMs
    ) {
        this.healthRegistry = healthRegistry;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.sleeper = sleeper == null ? duration -> { } : sleeper;
        this.jitter = jitter == null ? UnaryOperator.identity() : jitter;
        this.maxAttempts = Math.max(1, Math.min(5, maxAttempts));
        this.maxDelayMs = Math.max(0, maxDelayMs);
    }

    @Override
    public <T> T execute(String providerCode, String endpointType, Supplier<T> operation) {
        if (operation == null) {
            throw new IllegalArgumentException("历史 provider 操作不能为空");
        }
        String provider = normalize(providerCode);
        String endpoint = normalize(endpointType);
        LocalDateTime now = LocalDateTime.now(clock);
        if (healthRegistry != null && healthRegistry.isCoolingDown(provider, endpoint, now)) {
            throw failure(provider, endpoint, 0, "数据源处于熔断冷却期", true, now, null);
        }

        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            LocalDateTime attemptedAt = LocalDateTime.now(clock);
            try {
                T value = operation.get();
                if (value == null) {
                    throw new IllegalStateException("provider 返回空数据");
                }
                if (healthRegistry != null) {
                    healthRegistry.recordSuccess(provider, endpoint, fingerprint(value), attemptedAt);
                }
                return value;
            } catch (RuntimeException exception) {
                last = exception;
                boolean retryable = retryable(exception);
                if (healthRegistry != null) {
                    healthRegistry.recordFailure(provider, endpoint, rootMessage(exception), attemptedAt);
                }
                if (!retryable || attempt >= maxAttempts) {
                    throw failure(provider, endpoint, attempt, rootMessage(exception), retryable,
                            attemptedAt, exception);
                }
                Duration delay = retryAfter(exception).orElse(null);
                if (delay == null) {
                    delay = jitter.apply(baseDelay(attempt));
                }
                delay = clamp(delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure(provider, endpoint, attempt, "重试等待被中断", true,
                            attemptedAt, interrupted);
                }
            }
        }
        throw failure(provider, endpoint, maxAttempts, rootMessage(last), true,
                LocalDateTime.now(clock), last);
    }

    private HistoricalProviderRetryExecutor.ProviderRetryException failure(
            String provider,
            String endpoint,
            int attempt,
            String reason,
            boolean retryable,
            LocalDateTime at,
            Throwable cause
    ) {
        LocalDateTime next = retryable ? at.plusSeconds(Math.max(1, baseDelaySeconds(attempt))) : null;
        String message = "历史 provider 失败：provider=" + provider + "；endpoint=" + endpoint
                + "；attempt=" + attempt + "/" + maxAttempts + "；retryable=" + retryable
                + "；nextRetryAt=" + (next == null ? "none" : next) + "；原因=" + reason;
        return new HistoricalProviderRetryExecutor.ProviderRetryException(
                provider, endpoint, attempt, maxAttempts, retryable, next, message, cause);
    }

    private Duration baseDelay(int attempt) {
        return Duration.ofSeconds(baseDelaySeconds(attempt));
    }

    private long baseDelaySeconds(int attempt) {
        return DEFAULT_BACKOFF_SECONDS[Math.min(Math.max(attempt - 1, 0), DEFAULT_BACKOFF_SECONDS.length - 1)];
    }

    private Duration clamp(Duration delay) {
        if (delay == null || delay.isNegative()) {
            return Duration.ZERO;
        }
        return delay.compareTo(Duration.ofMillis(maxDelayMs)) > 0
                ? Duration.ofMillis(maxDelayMs) : delay;
    }

    private static Duration withJitter(Duration base) {
        if (base == null || base.isZero()) {
            return Duration.ZERO;
        }
        double factor = 0.8d + ThreadLocalRandom.current().nextDouble(0.4d);
        return Duration.ofMillis(Math.max(1L, Math.round(base.toMillis() * factor)));
    }

    private static java.util.Optional<Duration> retryAfter(Throwable throwable) {
        String message = rootMessage(throwable).toLowerCase(Locale.ROOT);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "retry-after\\s*[:=]\\s*(\\d+)").matcher(message);
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(matcher.group(1))));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static boolean retryable(Throwable throwable) {
        String message = rootMessage(throwable).toLowerCase(Locale.ROOT);
        if (message.contains("401") || message.contains("403") || message.contains("forbidden")
                || message.contains("permission") || message.contains("权限")
                || message.contains("schema") || message.contains("字段")
                || message.contains("invalid token") || message.contains("unauthorized")) {
            return false;
        }
        return message.contains("429") || message.contains("502") || message.contains("503")
                || message.contains("504") || message.contains("timeout")
                || message.contains("timed out") || message.contains("unexpected end of file")
                || message.contains("eof") || message.contains("connection reset")
                || message.contains("connection refused") || message.contains("remote host")
                || message.contains("temporarily") || message.contains("lock wait");
    }

    private static String fingerprint(Object value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
