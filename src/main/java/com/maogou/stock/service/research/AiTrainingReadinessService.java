package com.maogou.stock.service.research;

import com.maogou.stock.service.impl.research.AiTrainingReadinessGate;

import java.time.LocalDateTime;

public interface AiTrainingReadinessService {

    AiTrainingReadinessGate.Readiness assess(LocalDateTime asOfTime);
}
