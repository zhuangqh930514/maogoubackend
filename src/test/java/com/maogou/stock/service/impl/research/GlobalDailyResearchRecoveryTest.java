package com.maogou.stock.service.impl.research;

import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalDailyResearchRecoveryTest {

    @Test
    void contextCarriesOnlyPersistedCheckpointsAcrossProcessRestart() {
        AiGlobalDailyResearchExecutor.PipelineContext context =
                new AiGlobalDailyResearchExecutor.PipelineContext(
                        10L,
                        LocalDate.of(2026, 7, 14),
                        1L,
                        null,
                        "GLOBAL_DAILY:2026-07-14",
                        "input-fingerprint",
                        LocalDateTime.of(2026, 7, 14, 16, 0),
                        0,
                        Map.of(
                                "SNAPSHOT_UNIVERSE", "{\"universeSnapshotId\":91}",
                                "FETCH_SOURCE_DATA", "{\"dataBatchId\":55}"
                        ),
                        () -> { }
                );

        assertThat(context.checkpoint("SNAPSHOT_UNIVERSE")).contains("\"universeSnapshotId\":91");
        assertThat(context.checkpoint("FETCH_SOURCE_DATA")).contains("\"dataBatchId\":55");
        assertThat(context).hasNoNullFieldsOrPropertiesExcept("modelVersionId");
    }
}
