package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiSample;

import java.math.BigDecimal;

public interface AiDecisionPolicy {

    Decision decide(AiSample sample, Signal signal, Integer rankNo, int topK);

    record Signal(
            BigDecimal score,
            BigDecimal riskScore,
            BigDecimal confidence,
            BigDecimal expectedReturn
    ) {
    }

    record Decision(String action, String actionBucket, String targetDirection, String abstainReason) {
    }
}
