package com.maogou.stock.infrastructure.ai;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.AiModelConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class AiModelRequestLimiterTest {

    @Test
    void sharesTheConcurrencyLimitForDifferentUsersOfTheSameEndpoint() {
        AppProperties properties = properties(1, 5, 100, 1_000);
        AiModelRequestLimiter limiter = new AiModelRequestLimiter(properties);
        AiModelConfig firstUser = config("https://api.example.com/v1", "deepseek-chat", "shared-key");
        AiModelConfig secondUser = config("https://api.example.com/v1", "deepseek-chat", "shared-key");
        firstUser.userId = 5L;
        secondUser.userId = 6L;

        try (AiModelRequestLimiter.Permit ignored = limiter.acquire(firstUser)) {
            assertThatThrownBy(() -> limiter.acquire(secondUser))
                    .isInstanceOf(AiModelRateLimitException.class)
                    .hasMessageContaining("请求队列繁忙")
                    .extracting(exception -> ((AiModelRateLimitException) exception).retryAfterMillis())
                    .isEqualTo(100L);
        }

        try (AiModelRequestLimiter.Permit ignored = limiter.acquire(secondUser)) {
            assertThat(ignored).isNotNull();
        }
    }

    @Test
    void keepsTheSharedEndpointInCooldownAfterAProviderRateLimit() {
        AppProperties properties = properties(1, 5, 100, 1_000);
        AiModelRequestLimiter limiter = new AiModelRequestLimiter(properties);
        AiModelConfig config = config("https://api.example.com/v1", "qwen3.6", "key");

        limiter.recordRateLimit(config, Duration.ofMillis(350), null);

        Throwable thrown = catchThrowable(() -> limiter.acquire(config));
        assertThat(thrown)
                .isInstanceOf(AiModelRateLimitException.class)
                .hasMessageContaining("触发限流");
        assertThat(((AiModelRateLimitException) thrown).retryAfterMillis())
                .isGreaterThanOrEqualTo(200L)
                .isLessThanOrEqualTo(350L);
    }

    private static AppProperties properties(int concurrency, long queueWaitMs, long retryBaseMs, long retryMaxMs) {
        AppProperties properties = new AppProperties();
        properties.getAi().setMaxConcurrentRequests(concurrency);
        properties.getAi().setQueueWaitMs(queueWaitMs);
        properties.getAi().setRetryBaseDelayMs(retryBaseMs);
        properties.getAi().setRetryMaxDelayMs(retryMaxMs);
        return properties;
    }

    private static AiModelConfig config(String baseUrl, String model, String apiKey) {
        AiModelConfig config = new AiModelConfig();
        config.apiBaseUrl = baseUrl;
        config.modelName = model;
        config.apiKey = apiKey;
        return config;
    }
}
