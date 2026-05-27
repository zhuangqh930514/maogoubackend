package com.maogou.stock.service;

import com.maogou.stock.dto.settings.PromptTemplateRequest;
import com.maogou.stock.dto.settings.PromptTemplateResponse;

import java.util.List;

public interface PromptTemplateService {
    List<PromptTemplateResponse> list();

    PromptTemplateResponse create(PromptTemplateRequest request);

    PromptTemplateResponse update(Long id, PromptTemplateRequest request);

    void remove(Long id);
}
