package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.settings.ConnectionTestResponse;
import com.maogou.stock.dto.settings.ModelConfigRequest;
import com.maogou.stock.dto.settings.ModelConfigResponse;
import com.maogou.stock.service.ModelConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ModelConfigService modelConfigService;

    public SettingsController(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @GetMapping("/model")
    public ApiResponse<ModelConfigResponse> model() {
        return ApiResponse.ok(modelConfigService.current());
    }

    @PutMapping("/model")
    public ApiResponse<ModelConfigResponse> saveModel(@RequestBody @Valid ModelConfigRequest request) {
        return ApiResponse.ok(modelConfigService.save(request));
    }

    @PostMapping("/model/test")
    public ApiResponse<ConnectionTestResponse> test(@RequestBody @Valid ModelConfigRequest request) {
        return ApiResponse.ok(modelConfigService.testConnection(request));
    }
}
