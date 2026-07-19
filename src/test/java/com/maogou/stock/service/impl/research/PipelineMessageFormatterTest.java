package com.maogou.stock.service.impl.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineMessageFormatterTest {

    @Test
    void keepsBoundedSummaryAndPreservesFullDetailForErrorStorms() {
        List<String> errors = IntStream.range(0, 2_000)
                .mapToObj(index -> "600%03d: %s".formatted(index, "source unavailable ".repeat(20)))
                .toList();

        String summary = PipelineMessageFormatter.summary(errors);
        String detail = PipelineMessageFormatter.detail(errors);

        assertThat(summary.length()).isLessThanOrEqualTo(PipelineMessageFormatter.MAX_SUMMARY_LENGTH);
        assertThat(summary).contains("其余 1988 条省略");
        assertThat(detail).contains(errors.get(0).trim(), errors.get(errors.size() - 1).trim());
        assertThat(detail.length()).isLessThanOrEqualTo(PipelineMessageFormatter.MAX_DETAIL_LENGTH);
    }
}
