package com.maogou.stock.service.impl.research;

import com.maogou.stock.service.research.AiResearchCycleResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiResearchOperationsCycleResultTest {

    @Test
    void insufficientTrainingDataRemainsAVisibleTerminalState() {
        var result = AiResearchOperationsServiceImpl.managedCycleResult(
                new AiResearchCycleResult(
                        "INSUFFICIENT_DATA", 406, 0, 0,
                        "训练数据尚未就绪：tradabilityCoverage=0.0"));

        assertThat(result.runStatus()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.stepStatus()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.processed()).isEqualTo(406);
        assertThat(result.success()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.message()).contains("尚未就绪");
        assertThat(result.problem()).isTrue();
    }

    @Test
    void skippedAndPartialResultsCannotBeReportedAsSuccess() {
        var skipped = AiResearchOperationsServiceImpl.managedCycleResult(
                new AiResearchCycleResult("SKIPPED", 0, 0, 0, "样本不足"));
        var partial = AiResearchOperationsServiceImpl.managedCycleResult(
                new AiResearchCycleResult("PARTIAL_SUCCESS", 10, 9, 1, "一个因子失败"));

        assertThat(skipped.runStatus()).isEqualTo("SKIPPED");
        assertThat(partial.runStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(partial.stepStatus()).isEqualTo("SUCCESS_WITH_WARNINGS");
    }

    @Test
    void unknownCycleStatusFailsClosed() {
        assertThatThrownBy(() -> AiResearchOperationsServiceImpl.managedCycleResult(
                new AiResearchCycleResult("MAYBE", 0, 0, 0, "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知状态");
    }
}
