package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiSampleLabelMapper extends BaseMapper<AiSampleLabel> {

    @Select("""
            SELECT * FROM ai_sample_label
            WHERE sample_id = #{sampleId} AND horizon_trading_days = #{horizonDays}
              AND label_status = 'MATURED'
            ORDER BY created_at DESC LIMIT 1
            """)
    AiSampleLabel selectForReview(
            @Param("sampleId") Long sampleId,
            @Param("horizonDays") Integer horizonDays
    );
}
