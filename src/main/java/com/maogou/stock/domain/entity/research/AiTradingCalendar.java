package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@TableName("ai_trading_calendar")
public class AiTradingCalendar {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String marketCode;
    public LocalDate tradeDate;
    public String calendarVersion;
    public Integer isTradeDay;
    public LocalTime sessionOpenTime;
    public LocalTime sessionCloseTime;
    public LocalDate previousTradeDate;
    public LocalDate nextTradeDate;
    public String sourceName;
    public LocalDateTime sourceAsOf;
    public String sourceFingerprint;
    public LocalDateTime createdAt;
}
