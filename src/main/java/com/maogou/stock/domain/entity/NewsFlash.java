package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("news_flash")
public class NewsFlash {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String title;
    public String source;
    public String url;
    public LocalDateTime publishedAt;
    public LocalDateTime createdAt;
}
