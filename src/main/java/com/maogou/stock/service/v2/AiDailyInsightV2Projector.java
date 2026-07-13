package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.AiDailyInsightItem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiDailyInsightV2Projector {

    List<AiDailyInsightItem> project(Long userId, LocalDate tradeDate, Long snapshotId, LocalDateTime now);
}
