package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.entity.AiPromptTemplate;
import com.maogou.stock.dto.settings.PromptTemplateRequest;
import com.maogou.stock.dto.settings.PromptTemplateResponse;
import com.maogou.stock.mapper.AiModelConfigMapper;
import com.maogou.stock.mapper.AiPromptTemplateMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.PromptTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PromptTemplateServiceImpl implements PromptTemplateService {

    private static final String DEFAULT_TITLE = "默认 A 股投研分析";
    private static final String DEFAULT_CONTENT = "你是一名偏投顾可执行风格的A股投研助手。请基于给定行情、K线与财务数据输出严格 JSON，不要输出 markdown、不要输出代码块、不要补充任何解释文字。JSON 顶层必须包含 technicalAnalysis、riskWarning、buySellPoints、promptSummary、score 五个字段。technicalAnalysis 需给出趋势判断、均线关系、K线形态、量能表现、支撑压力；riskWarning 必须结合当前个股写出具体风险、风险触发条件、后续观察点，禁止空话；buySellPoints 必须给出当前动作建议、观察买点、减仓或卖出触发条件、止损位或失效条件、仓位建议；promptSummary 必须覆盖实时价、涨跌幅、量比、PE、PB、营收同比、净利同比、近阶段K线特征、量能概览，内容供客户直接阅读；score 输出 0-100 整数。";

    private final AiPromptTemplateMapper promptTemplateMapper;
    private final AiModelConfigMapper modelConfigMapper;

    public PromptTemplateServiceImpl(AiPromptTemplateMapper promptTemplateMapper, AiModelConfigMapper modelConfigMapper) {
        this.promptTemplateMapper = promptTemplateMapper;
        this.modelConfigMapper = modelConfigMapper;
    }

    @Override
    @Transactional
    public List<PromptTemplateResponse> list() {
        ensureDefaultTemplate();
        return promptTemplateMapper.selectList(baseQuery().orderByDesc("updated_at")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public PromptTemplateResponse create(PromptTemplateRequest request) {
        AiPromptTemplate entity = new AiPromptTemplate();
        entity.userId = AuthContext.currentUserIdOrDefault();
        entity.title = request.title().trim();
        entity.content = request.content().trim();
        entity.deleted = 0;
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = entity.createdAt;
        promptTemplateMapper.insert(entity);
        syncCurrentPrompt(entity.content);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public PromptTemplateResponse update(Long id, PromptTemplateRequest request) {
        AiPromptTemplate entity = promptTemplateMapper.selectOne(baseQuery().eq("id", id).last("limit 1"));
        if (entity == null) {
            throw new IllegalArgumentException("提示词不存在");
        }
        entity.title = request.title().trim();
        entity.content = request.content().trim();
        entity.updatedAt = LocalDateTime.now();
        promptTemplateMapper.updateById(entity);
        syncCurrentPrompt(entity.content);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public void remove(Long id) {
        promptTemplateMapper.update(null, new UpdateWrapper<AiPromptTemplate>()
                .eq("id", id)
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .set("deleted", 1)
                .set("updated_at", LocalDateTime.now()));
    }

    @Override
    public String resolveContent(Long id, String fallback) {
        if (id == null || id <= 0) {
            return fallback;
        }
        AiPromptTemplate entity = promptTemplateMapper.selectOne(baseQuery().eq("id", id).last("limit 1"));
        if (entity == null) {
            throw new IllegalArgumentException("提示词不存在");
        }
        if (entity.content == null || entity.content.isBlank()) {
            return fallback;
        }
        return entity.content.trim();
    }

    private void ensureDefaultTemplate() {
        Long count = promptTemplateMapper.selectCount(baseQuery());
        if (count != null && count > 0) {
            return;
        }
        String currentPrompt = currentModelPrompt();
        create(new PromptTemplateRequest(DEFAULT_TITLE, currentPrompt));
    }

    private QueryWrapper<AiPromptTemplate> baseQuery() {
        return new QueryWrapper<AiPromptTemplate>()
                .eq("user_id", AuthContext.currentUserIdOrDefault());
    }

    private PromptTemplateResponse toResponse(AiPromptTemplate entity) {
        return new PromptTemplateResponse(entity.id, entity.title, entity.content, entity.updatedAt);
    }

    private String currentModelPrompt() {
        AiModelConfig config = modelConfigMapper.selectOne(new QueryWrapper<AiModelConfig>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .last("limit 1"));
        if (config == null || config.promptTemplate == null || config.promptTemplate.isBlank()) {
            return DEFAULT_CONTENT;
        }
        return config.promptTemplate;
    }

    private void syncCurrentPrompt(String content) {
        modelConfigMapper.update(null, new UpdateWrapper<AiModelConfig>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .set("prompt_template", content)
                .set("updated_at", LocalDateTime.now()));
    }
}
