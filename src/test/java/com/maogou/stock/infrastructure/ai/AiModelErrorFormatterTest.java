package com.maogou.stock.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelErrorFormatterTest {

    @Test
    void hidesRawNetworkExceptionFromTheBrowser() {
        String message = AiModelErrorFormatter.userMessage(
                new ResourceAccessException("Read timed out"), "deepseek-chat");

        assertThat(message)
                .contains("连接超时或网络不可达")
                .contains("deepseek-chat")
                .doesNotContain("Read timed out");
    }

    @Test
    void translatesAuthenticationAndRateLimitResponses() {
        HttpClientErrorException unauthorized = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                "raw provider payload".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(AiModelErrorFormatter.userMessage(unauthorized, "qwen3.6"))
                .contains("鉴权失败").contains("HTTP 401");

        AiModelRateLimitException rateLimit = new AiModelRateLimitException("请稍后重试", 2_500, null);
        assertThat(AiModelErrorFormatter.userMessage(rateLimit, "qwen3.6"))
                .isEqualTo("请稍后重试");
        assertThat(AiModelErrorFormatter.retryAfterSeconds(rateLimit)).isEqualTo(3L);
    }
}
