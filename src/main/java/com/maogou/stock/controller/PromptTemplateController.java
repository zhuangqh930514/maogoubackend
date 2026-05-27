package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.settings.PromptTemplateRequest;
import com.maogou.stock.dto.settings.PromptTemplateResponse;
import com.maogou.stock.service.PromptTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/prompt-templates")
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    public PromptTemplateController(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    @GetMapping
    public ApiResponse<List<PromptTemplateResponse>> list() {
        return ApiResponse.ok(promptTemplateService.list());
    }

    @PostMapping
    public ApiResponse<PromptTemplateResponse> create(@RequestBody @Valid PromptTemplateRequest request) {
        return ApiResponse.ok(promptTemplateService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PromptTemplateResponse> update(@PathVariable Long id, @RequestBody @Valid PromptTemplateRequest request) {
        return ApiResponse.ok(promptTemplateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        promptTemplateService.remove(id);
        return ApiResponse.ok(null);
    }
}
