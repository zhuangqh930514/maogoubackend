package com.maogou.stock.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.AiModelConfig;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LocalAiClient {

    private final RestTemplateBuilder restTemplateBuilder;
    private final AppProperties properties;

    public LocalAiClient(RestTemplateBuilder restTemplateBuilder, AppProperties properties) {
        this.restTemplateBuilder = restTemplateBuilder;
        this.properties = properties;
    }

    public String chat(String prompt, AiModelConfig config) {
        AppProperties.Ai defaults = properties.getAi();
        String baseUrl = firstNonBlank(config == null ? null : config.apiBaseUrl, defaults.getApiBaseUrl());
        String modelName = firstNonBlank(config == null ? null : config.modelName, defaults.getModelName());
        String apiKey = firstNonBlank(config == null ? null : config.apiKey, defaults.getApiKey());
        Integer timeoutMs = config == null || config.timeoutMs == null
                ? defaults.getTimeoutMs()
                : config.timeoutMs;
        BigDecimal temperature = config == null || config.temperature == null
                ? BigDecimal.valueOf(defaults.getTemperature())
                : config.temperature;
        Integer maxTokens = config == null || config.maxTokens == null
                ? defaults.getMaxTokens()
                : config.maxTokens;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是一名谨慎的 A 股投研助手，只输出结构化、可复核的分析。"),
                Map.of("role", "user", "content", prompt)
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, normalizeAuthorization(apiKey));
        }

        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .build();
        JsonNode response = restTemplate.postForObject(
                chatCompletionsUrl(baseUrl),
                new HttpEntity<>(body, headers),
                JsonNode.class
        );
        JsonNode content = response == null ? null : response.at("/choices/0/message/content");
        if (content == null || content.isMissingNode()) {
            JsonNode text = response == null ? null : response.at("/choices/0/text");
            return text == null || text.isMissingNode() ? "" : text.asText();
        }
        return content.asText();
    }

    public boolean test(AiModelConfig config) {
        String result = chat("请只回复 pong。", config);
        return result != null && !result.isBlank();
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String chatCompletionsUrl(String baseUrl) {
        String normalized = stripTrailingSlash(baseUrl);
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized + "/chat/completions";
    }

    private static String normalizeAuthorization(String apiKey) {
        String trimmed = apiKey.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed : "Bearer " + trimmed;
    }
}
