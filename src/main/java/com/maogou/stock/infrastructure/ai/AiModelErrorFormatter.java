package com.maogou.stock.infrastructure.ai;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Converts provider failures into short, actionable messages safe for the browser.
 * The original exception is still logged by the caller for server-side diagnosis.
 */
public final class AiModelErrorFormatter {

    private AiModelErrorFormatter() {
    }

    public static String userMessage(Throwable error, String modelName) {
        AiModelRateLimitException rateLimit = find(error, AiModelRateLimitException.class);
        if (rateLimit != null) {
            return rateLimit.getMessage();
        }
        String model = modelName == null || modelName.isBlank() ? "未配置模型" : modelName.trim();
        ResourceAccessException network = find(error, ResourceAccessException.class);
        if (network != null) {
            return "模型服务连接超时或网络不可达，请检查 API Base URL 和服务器出网。模型=" + model;
        }
        RestClientResponseException response = find(error, RestClientResponseException.class);
        if (response != null) {
            int status = response.getStatusCode().value();
            if (status == 401 || status == 403) {
                return "模型鉴权失败，请检查 API Key 和模型权限。HTTP " + status + "，模型=" + model;
            }
            if (status == 429) {
                return "模型接口触发限流，请稍后重试。模型=" + model;
            }
            if (status >= 500) {
                return "模型服务暂时不可用（HTTP " + status + "），系统会稍后重试。模型=" + model;
            }
            return "模型接口请求失败（HTTP " + status + "），请检查接入地址和请求配置。模型=" + model;
        }
        return "模型调用失败，请稍后重试。模型=" + model;
    }

    public static Long retryAfterSeconds(Throwable error) {
        AiModelRateLimitException rateLimit = find(error, AiModelRateLimitException.class);
        if (rateLimit == null || rateLimit.retryAfterMillis() <= 0) {
            return null;
        }
        return Math.max(1L, (rateLimit.retryAfterMillis() + 999L) / 1000L);
    }

    public static String storedFailureMessage(String content, String errorMessage, String modelName) {
        String source = ((content == null ? "" : content) + " "
                + (errorMessage == null ? "" : errorMessage)).toLowerCase();
        String model = modelName == null || modelName.isBlank() ? "未配置模型" : modelName.trim();
        if (source.contains("429") || source.contains("daily_limit") || source.contains("rate limit")
                || source.contains("限流")) {
            return "模型接口触发限流，请稍后重试。模型=" + model;
        }
        if (source.contains("read timed out") || source.contains("timeout")
                || source.contains("connection refused") || source.contains("unknownhost")) {
            return "模型服务连接超时或网络不可达，请检查 API Base URL 和服务器出网。模型=" + model;
        }
        String visible = content == null ? "" : content.trim();
        if (visible.startsWith("模型服务连续失败") || visible.startsWith("模型鉴权失败")
                || visible.startsWith("模型接口请求失败") || visible.startsWith("模型服务暂时不可用")
                || visible.startsWith("模型接口触发限流") || visible.startsWith("模型服务连接超时")
                || visible.startsWith("模型调用失败，请稍后重试")) {
            return visible;
        }
        return "模型调用失败，请稍后重试。模型=" + model;
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth++ < 12) {
            if (type.isInstance(cursor)) {
                return type.cast(cursor);
            }
            cursor = cursor.getCause();
        }
        return null;
    }
}
