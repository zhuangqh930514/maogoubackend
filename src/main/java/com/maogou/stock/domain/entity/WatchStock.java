package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("watch_stock")
public class WatchStock {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String stockCode;
    public String stockName;
    public String market;
    public String groupName;
    public Integer priority;
    @TableLogic
    public Integer deleted;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
