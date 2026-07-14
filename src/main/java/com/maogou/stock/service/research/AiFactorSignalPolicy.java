package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiFactorValue;

import java.math.BigDecimal;

public final class AiFactorSignalPolicy {

    private static final BigDecimal INVALID_VALUATION_SIGNAL = new BigDecimal("-3");

    private AiFactorSignalPolicy() {
    }

    public static BigDecimal orient(AiFactorValue factor, BigDecimal normalizedValue) {
        if (factor == null || normalizedValue == null) {
            return null;
        }
        String code = factor.factorCode == null ? "" : factor.factorCode.toUpperCase();
        if ((code.contains("FUNDAMENTAL_PE") || code.contains("FUNDAMENTAL_PB"))
                && factor.rawValue != null && factor.rawValue.signum() <= 0) {
            return INVALID_VALUATION_SIGNAL;
        }
        if (code.contains("VOLATILITY") || code.contains("DEBT_RATIO")
                || code.contains("FUNDAMENTAL_PE") || code.contains("FUNDAMENTAL_PB")) {
            return normalizedValue.negate();
        }
        return normalizedValue;
    }
}
