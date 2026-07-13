package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiDailyInsightItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiDailyInsightOrderingTest {

    @Test
    void recommendationComesBeforeWatchAndAvoidWhileScoresDescendInsideBucket() {
        List<AiDailyInsightItem> items = new ArrayList<>(List.of(
                item("AVOID", "99"),
                item("RECOMMEND", "70"),
                item("WATCH", "90"),
                item("RECOMMEND", "80")));

        items.sort(AiDailyInsightOrdering.comparator());

        assertThat(items).extracting(value -> value.actionBucket + ":" + value.compositeScore)
                .containsExactly("RECOMMEND:80", "RECOMMEND:70", "WATCH:90", "AVOID:99");
    }

    private static AiDailyInsightItem item(String bucket, String score) {
        AiDailyInsightItem value = new AiDailyInsightItem();
        value.actionBucket = bucket;
        value.compositeScore = new BigDecimal(score);
        value.riskScore = BigDecimal.ZERO;
        return value;
    }
}
