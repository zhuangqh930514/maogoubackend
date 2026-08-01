package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.MarketSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface MarketSnapshotMapper extends BaseMapper<MarketSnapshot> {

    @Insert("""
            INSERT INTO market_snapshot (
                symbol, name, market, latest_price, change_amount, change_percent, volume_ratio,
                amount, quote_time, trade_date, source_provider, source_status, data_mode, source_fingerprint
            ) VALUES (
                #{item.symbol}, #{item.name}, #{item.market}, #{item.latestPrice}, #{item.changeAmount},
                #{item.changePercent}, #{item.volumeRatio}, #{item.amount}, #{item.quoteTime}, #{item.tradeDate},
                #{item.sourceProvider}, #{item.sourceStatus}, #{item.dataMode}, #{item.sourceFingerprint}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") MarketSnapshot item);
}
