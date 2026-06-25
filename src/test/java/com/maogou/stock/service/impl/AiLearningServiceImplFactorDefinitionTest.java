package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiFactorDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AiLearningServiceImplFactorDefinitionTest {

    @Test
    void leavesExistingDefaultFactorUntouchedWhenSeedIsUnchanged() {
        LocalDateTime originalUpdatedAt = LocalDateTime.parse("2026-06-25T10:00:00");
        LocalDateTime now = LocalDateTime.parse("2026-06-25T16:00:00");
        AiFactorDefinition existing = definition("站上5日线", "TREND", "POSITIVE", "收盘价高于5日均线", "7.0000", 1);
        existing.updatedAt = originalUpdatedAt;

        boolean changed = AiLearningServiceImpl.applyDefaultFactorDefinition(
                existing,
                "站上5日线",
                "TREND",
                "POSITIVE",
                "收盘价高于5日均线",
                new BigDecimal("7"),
                now
        );

        assertThat(changed).isFalse();
        assertThat(existing.updatedAt).isEqualTo(originalUpdatedAt);
    }

    @Test
    void updatesExistingDefaultFactorOnlyWhenSeedDiffers() {
        LocalDateTime now = LocalDateTime.parse("2026-06-25T16:00:00");
        AiFactorDefinition existing = definition("旧名称", "TREND", "POSITIVE", "旧公式", "6", null);

        boolean changed = AiLearningServiceImpl.applyDefaultFactorDefinition(
                existing,
                "站上5日线",
                "TREND",
                "POSITIVE",
                "收盘价高于5日均线",
                new BigDecimal("7"),
                now
        );

        assertThat(changed).isTrue();
        assertThat(existing.factorName).isEqualTo("站上5日线");
        assertThat(existing.formulaDesc).isEqualTo("收盘价高于5日均线");
        assertThat(existing.defaultWeight).isEqualByComparingTo("7");
        assertThat(existing.enabled).isEqualTo(1);
        assertThat(existing.updatedAt).isEqualTo(now);
    }

    private static AiFactorDefinition definition(String name, String group, String direction, String formula, String weight, Integer enabled) {
        AiFactorDefinition definition = new AiFactorDefinition();
        definition.factorName = name;
        definition.factorGroup = group;
        definition.direction = direction;
        definition.formulaDesc = formula;
        definition.defaultWeight = new BigDecimal(weight);
        definition.enabled = enabled;
        return definition;
    }
}
