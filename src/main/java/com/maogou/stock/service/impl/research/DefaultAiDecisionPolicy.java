package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.service.research.AiDecisionPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DefaultAiDecisionPolicy implements AiDecisionPolicy {

    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("60");
    private static final BigDecimal RECOMMEND_SCORE = new BigDecimal("65");
    private static final BigDecimal MAX_RECOMMEND_RISK = new BigDecimal("70");
    private static final BigDecimal AVOID_SCORE = new BigDecimal("35");
    private static final BigDecimal AVOID_RISK = new BigDecimal("75");

    @Override
    public Decision decide(AiSample sample, Signal signal, Integer rankNo, int topK) {
        if (sample == null
                || !"READY".equals(sample.qualityStatus) && !"PARTIAL".equals(sample.qualityStatus)
                || !"TRADABLE".equals(sample.tradableStatus)
                || sample.dataQualityScore == null
                || sample.dataQualityScore.compareTo(new BigDecimal("60")) < 0) {
            return new Decision("UNAVAILABLE", "UNAVAILABLE", "SIDEWAYS", "DATA_UNAVAILABLE_OR_UNTRADABLE");
        }
        if (signal.confidence().compareTo(MIN_CONFIDENCE) < 0) {
            return new Decision("WATCH", "ABSTAIN", "SIDEWAYS", "LOW_CONFIDENCE");
        }
        if (signal.riskScore().compareTo(AVOID_RISK) >= 0 || signal.score().compareTo(AVOID_SCORE) <= 0) {
            return new Decision("REDUCE", "AVOID", direction(signal.expectedReturn()), null);
        }
        if (rankNo != null
                && rankNo <= Math.max(1, topK)
                && signal.score().compareTo(RECOMMEND_SCORE) >= 0
                && signal.riskScore().compareTo(MAX_RECOMMEND_RISK) <= 0) {
            return new Decision("BUY", "RECOMMEND", "UP", null);
        }
        String reason = rankNo == null ? "NOT_RANKABLE" : "NOT_TOP_K_OR_SIGNAL_WEAK";
        return new Decision("WATCH", "ABSTAIN", "SIDEWAYS", reason);
    }

    private static String direction(BigDecimal expectedReturn) {
        if (expectedReturn == null) {
            return "SIDEWAYS";
        }
        if (expectedReturn.compareTo(new BigDecimal("0.005")) >= 0) {
            return "UP";
        }
        if (expectedReturn.compareTo(new BigDecimal("-0.005")) <= 0) {
            return "DOWN";
        }
        return "SIDEWAYS";
    }
}
