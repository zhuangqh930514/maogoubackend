package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.v2.AiSampleV2;

import java.math.BigDecimal;

public interface AiDecisionPolicy {

    Decision decide(AiSampleV2 sample, Signal signal, Integer rankNo, int topK);

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
