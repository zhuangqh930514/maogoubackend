package com.maogou.stock.infrastructure.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelStoredFailureFormatterTest {

    @Test
    void sanitizesLegacyRawRateLimitPayloadsWhenChatHistoryIsRead() {
        String message = AiModelErrorFormatter.storedFailureMessage(
                "模型调用失败：429 {\"code\":\"DAILY_LIMIT_EXCEEDED\"}",
                "429 provider payload",
                "deepseek-chat");

        assertThat(message)
                .isEqualTo("模型接口触发限流，请稍后重试。模型=deepseek-chat")
                .doesNotContain("DAILY_LIMIT_EXCEEDED");
    }
}
