package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiSourceObservation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

public interface AiSourceObservationMapper extends BaseMapper<AiSourceObservation> {

    @Select("""
            SELECT * FROM ai_source_observation
            WHERE stock_code = #{stockCode}
              AND source_type = 'INDUSTRY_MEMBERSHIP'
              AND quality_status = 'READY'
              AND event_time <= #{asOfTime}
              AND as_of_time <= #{asOfTime}
            ORDER BY event_time DESC, id DESC
            LIMIT 1
            """)
    AiSourceObservation selectIndustryMembershipAt(
            @Param("stockCode") String stockCode,
            @Param("asOfTime") LocalDateTime asOfTime
    );
}
