package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.WatchStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WatchStockMapper extends BaseMapper<WatchStock> {
    @Select("""
            SELECT id, user_id, stock_code, stock_name, market, group_name, priority, deleted, created_at, updated_at
            FROM watch_stock
            WHERE user_id = #{userId} AND stock_code = #{code}
            LIMIT 1
            """)
    WatchStock selectAnyByUserIdAndCode(@Param("userId") Long userId, @Param("code") String code);

    @Update("""
            UPDATE watch_stock
            SET stock_name = #{stockName},
                market = #{market},
                group_name = #{groupName},
                priority = #{priority},
                deleted = 0,
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int restore(WatchStock entity);

    @Update("""
            UPDATE watch_stock
            SET priority = #{priority}, updated_at = #{updatedAt}
            WHERE user_id = #{userId} AND stock_code = #{stockCode} AND deleted = 0
            """)
    int updatePriority(WatchStock entity);
}
