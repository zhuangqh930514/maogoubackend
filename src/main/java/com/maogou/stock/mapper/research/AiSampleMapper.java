package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiSample;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface AiSampleMapper extends BaseMapper<AiSample> {

    @Select("""
            <script>
            SELECT s.*
            FROM ai_sample s
            INNER JOIN (
                SELECT stock_code, MAX(as_of_time) AS max_as_of_time
                FROM ai_sample
                WHERE data_batch_id = #{dataBatchId}
                  AND trade_date = #{tradeDate}
                  AND stock_code IN
                  <foreach collection="stockCodes" item="stockCode" open="(" separator="," close=")">
                    #{stockCode}
                  </foreach>
                GROUP BY stock_code
            ) latest ON latest.stock_code = s.stock_code AND latest.max_as_of_time = s.as_of_time
            WHERE s.data_batch_id = #{dataBatchId} AND s.trade_date = #{tradeDate}
            ORDER BY s.stock_code
            </script>
            """)
    List<AiSample> selectLatestForDecision(
            @Param("dataBatchId") Long dataBatchId,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("stockCodes") List<String> stockCodes
    );
}
