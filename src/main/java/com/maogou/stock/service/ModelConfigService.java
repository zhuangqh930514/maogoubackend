package com.maogou.stock.service;

import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.dto.settings.ConnectionTestResponse;
import com.maogou.stock.dto.settings.ModelConfigRequest;
import com.maogou.stock.dto.settings.ModelConfigResponse;

public interface ModelConfigService {
    ModelConfigResponse current();

    AiModelConfig currentEntity();

    ModelConfigResponse save(ModelConfigRequest request);

    ConnectionTestResponse testConnection(ModelConfigRequest request);

    AiModelConfig setAutoClosePipelineEnabled(boolean enabled);
}
