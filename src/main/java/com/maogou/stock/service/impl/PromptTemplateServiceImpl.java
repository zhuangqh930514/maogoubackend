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
    private static final String DEFAULT_CONTENT = "你是一名A股投研助手。请基于以下行情、K线、财务和持仓数据，输出 JSON 结构：technicalAnalysis、riskWarning、buySellPoints、score。";

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
