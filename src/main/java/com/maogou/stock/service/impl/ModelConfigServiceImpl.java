package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.dto.settings.ConnectionTestResponse;
import com.maogou.stock.dto.settings.ModelConfigRequest;
import com.maogou.stock.dto.settings.ModelConfigResponse;
import com.maogou.stock.infrastructure.ai.LocalAiClient;
import com.maogou.stock.mapper.AiModelConfigMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.ModelConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class ModelConfigServiceImpl implements ModelConfigService {

    private static final String DEFAULT_PROMPT_TEMPLATE = "你是一名偏投顾可执行风格的A股投研助手。请基于给定行情、K线与财务数据输出严格 JSON，不要输出 markdown、不要输出代码块、不要补充任何解释文字。JSON 顶层必须包含 technicalAnalysis、riskWarning、buySellPoints、promptSummary、score 五个字段。technicalAnalysis 需给出趋势判断、均线关系、K线形态、量能表现、支撑压力；riskWarning 必须结合当前个股写出具体风险、风险触发条件、后续观察点，禁止空话；buySellPoints 必须给出当前动作建议、观察买点、减仓或卖出触发条件、止损位或失效条件、仓位建议；promptSummary 必须覆盖实时价、涨跌幅、量比、PE、PB、营收同比、净利同比、近阶段K线特征、量能概览，内容供客户直接阅读；score 输出 0-100 整数。";

    private final AiModelConfigMapper configMapper;
    private final AppProperties properties;
    private final LocalAiClient localAiClient;

    public ModelConfigServiceImpl(AiModelConfigMapper configMapper, AppProperties properties, LocalAiClient localAiClient) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.localAiClient = localAiClient;
    }

    @Override
    public ModelConfigResponse current() {
        return toResponse(currentEntity());
    }

    @Override
    public AiModelConfig currentEntity() {
        AiModelConfig existing = configMapper.selectOne(new QueryWrapper<AiModelConfig>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .last("limit 1"));
        return existing == null ? defaultEntity() : existing;
    }

    @Override
    @Transactional
    public ModelConfigResponse save(ModelConfigRequest request) {
        AiModelConfig entity = currentEntity();
        boolean insert = entity.id == null;
        entity.userId = AuthContext.currentUserIdOrDefault();
        entity.apiBaseUrl = normalizeBaseUrl(request.apiBaseUrl());
        entity.modelName = request.modelName().trim();
        entity.apiKey = resolveApiKey(request.apiKey(), insert ? null : entity.apiKey);
        entity.timeoutMs = request.timeout() == null ? properties.getAi().getTimeoutMs() : request.timeout();
        entity.temperature = request.temperature() == null ? BigDecimal.valueOf(properties.getAi().getTemperature()) : request.temperature();
        entity.maxTokens = request.maxTokens() == null ? properties.getAi().getMaxTokens() : request.maxTokens();
        entity.intradayIntervalMinutes = request.intradayInterval() == null ? 30 : request.intradayInterval();
        entity.closeAnalysisTime = request.closeTime() == null || request.closeTime().isBlank() ? "15:30" : request.closeTime();
        entity.analysisScope = request.analysisScope() == null || request.analysisScope().isBlank() ? "全部自选股" : request.analysisScope();
        entity.promptTemplate = resolvePromptTemplate(request.promptTemplate(), entity.promptTemplate);
        if (entity.autoClosePipelineEnabled == null) {
            entity.autoClosePipelineEnabled = 0;
        }
        if (entity.autoClosePipelineLastStatus == null || entity.autoClosePipelineLastStatus.isBlank()) {
            entity.autoClosePipelineLastStatus = "IDLE";
        }
        entity.deleted = 0;
        entity.updatedAt = LocalDateTime.now();
        if (insert) {
            entity.createdAt = entity.updatedAt;
            configMapper.insert(entity);
        } else {
            configMapper.updateById(entity);
        }
        return toResponse(entity);
    }

    @Override
    public ConnectionTestResponse testConnection(ModelConfigRequest request) {
        AiModelConfig existing = currentEntity();
        AiModelConfig config = fromRequest(request, existing);
        long start = System.currentTimeMillis();
        try {
            boolean success = localAiClient.test(config);
            return new ConnectionTestResponse(success, success ? "连接测试成功" : "模型返回为空", System.currentTimeMillis() - start);
        } catch (Exception ex) {
            return new ConnectionTestResponse(false, ex.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    @Transactional
    public AiModelConfig setAutoClosePipelineEnabled(boolean enabled) {
        AiModelConfig entity = currentEntity();
        boolean insert = entity.id == null;
        entity.userId = AuthContext.currentUserIdOrDefault();
        entity.autoClosePipelineEnabled = enabled ? 1 : 0;
        entity.autoClosePipelineLastStatus = enabled ? "IDLE" : "DISABLED";
        entity.autoClosePipelineLastMessage = enabled
                ? "已开启每日 16:00 自动收盘学习流水线"
                : "已关闭每日自动收盘学习流水线";
        entity.deleted = 0;
        entity.updatedAt = LocalDateTime.now();
        if (insert) {
            entity.createdAt = entity.updatedAt;
            configMapper.insert(entity);
        } else {
            configMapper.updateById(entity);
        }
        return entity;
    }

    private AiModelConfig defaultEntity() {
        AppProperties.Ai ai = properties.getAi();
        AiModelConfig config = new AiModelConfig();
        config.userId = AuthContext.currentUserIdOrDefault();
        config.apiBaseUrl = ai.getApiBaseUrl();
        config.modelName = ai.getModelName();
        config.apiKey = ai.getApiKey();
        config.timeoutMs = ai.getTimeoutMs();
        config.temperature = BigDecimal.valueOf(ai.getTemperature());
        config.maxTokens = ai.getMaxTokens();
        config.intradayIntervalMinutes = 30;
        config.closeAnalysisTime = "15:30";
        config.analysisScope = "全部自选股";
        config.promptTemplate = DEFAULT_PROMPT_TEMPLATE;
        config.autoClosePipelineEnabled = 0;
        config.autoClosePipelineLastStatus = "IDLE";
        config.deleted = 0;
        return config;
    }

    private AiModelConfig fromRequest(ModelConfigRequest request, AiModelConfig existing) {
        AiModelConfig config = new AiModelConfig();
        config.apiBaseUrl = normalizeBaseUrl(request.apiBaseUrl());
        config.modelName = request.modelName().trim();
        config.apiKey = resolveApiKey(request.apiKey(), existing == null ? null : existing.apiKey);
        config.timeoutMs = request.timeout() == null ? properties.getAi().getTimeoutMs() : request.timeout();
        config.temperature = request.temperature() == null ? BigDecimal.valueOf(properties.getAi().getTemperature()) : request.temperature();
        config.maxTokens = request.maxTokens() == null ? properties.getAi().getMaxTokens() : request.maxTokens();
        config.promptTemplate = resolvePromptTemplate(request.promptTemplate(), existing == null ? null : existing.promptTemplate);
        return config;
    }

    private ModelConfigResponse toResponse(AiModelConfig entity) {
        return new ModelConfigResponse(
                entity.apiBaseUrl,
                entity.modelName,
                mask(entity.apiKey),
                entity.timeoutMs,
                entity.temperature,
                entity.maxTokens,
                entity.intradayIntervalMinutes,
                entity.closeAnalysisTime,
                entity.analysisScope,
                entity.promptTemplate
        );
    }

    private static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "******";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String resolveApiKey(String requested, String existing) {
        if (requested == null || requested.isBlank() || requested.contains("****")) {
            return existing;
        }
        return requested.trim();
    }

    private static String resolvePromptTemplate(String requested, String existing) {
        if (requested == null || requested.isBlank()) {
            return existing == null || existing.isBlank() ? DEFAULT_PROMPT_TEMPLATE : existing;
        }
        return requested;
    }
}
