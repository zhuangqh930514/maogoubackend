package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.dto.portfolio.TradePositionAggregate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TradeRecordMapper extends BaseMapper<TradeRecord> {
    @Select("""
            SELECT stock_code,
                   MAX(stock_name) AS stock_name,
                   SUM(CASE WHEN side = 'SELL' THEN -price * quantity ELSE price * quantity END) AS total_cost,
                   SUM(CASE WHEN side = 'SELL' THEN -quantity ELSE quantity END) AS quantity,
                   MAX(traded_at) AS last_traded_at
            FROM trade_record
            WHERE user_id = #{userId} AND deleted = 0
            GROUP BY stock_code
            HAVING SUM(CASE WHEN side = 'SELL' THEN -quantity ELSE quantity END) > 0
            ORDER BY last_traded_at DESC, stock_code ASC
            """)
    List<TradePositionAggregate> selectActivePositions(@Param("userId") Long userId);
}
