package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiModelConfig;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiModelConfigMapper extends BaseMapper<AiModelConfig> {

    @Select("""
            SELECT *
            FROM ai_model_config
            WHERE auto_close_pipeline_enabled = 1
              AND deleted = 0
              AND user_id IS NOT NULL
              AND user_id > #{afterUserId}
            ORDER BY user_id
            LIMIT #{limit}
            """)
    List<AiModelConfig> selectEnabledAutomationConfigsAfter(
            @Param("afterUserId") long afterUserId,
            @Param("limit") int limit
    );
}
