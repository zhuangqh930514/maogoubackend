package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiSample;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiResearchLabQueryServiceImplTest {

    @Test
    void globalPipelineEvidenceDoesNotExposeOwnersLeasesOrAuditActorIds() {
        AiPipelineRun run = new AiPipelineRun();
        run.id = 91L;
        run.scopeType = "GLOBAL";
        run.ownerUserId = 5L;
        run.executionOwner = "internal-lease-owner";
        run.pipelineType = "GLOBAL_WEEKLY_RESEARCH";

        AiPipelineStep audit = new AiPipelineStep();
        audit.id = 92L;
        audit.pipelineRunId = 91L;
        audit.stepKey = "REQUEST_ACCEPTED";
        audit.checkpointJson = "{\"actorUserId\":5,\"reason\":\"manual\"}";
        audit.leaseUntil = java.time.LocalDateTime.now().plusMinutes(1);

        assertThat(AiResearchLabQueryServiceImpl.evidenceFields(run))
                .doesNotContainKeys("ownerUserId", "executionOwner", "leaseUntil");
        assertThat(AiResearchLabQueryServiceImpl.evidenceFields(audit))
                .doesNotContainKeys("leaseUntil", "checkpointJson");
    }

    @Test
    void pipelineDetailAllowsGlobalRunsOrTheAuthenticatedUsersOwnProjectionRun() {
        QueryWrapper<AiPipelineRun> query = AiResearchLabQueryServiceImpl.pipelineRunScope(91L, 5L);

        assertThat(query.getCustomSqlSegment()).contains("id", "scope_type", "owner_user_id");
        assertThat(query.getParamNameValuePairs().values())
                .contains(91L, "GLOBAL", "USER", 5L);
    }

    @Test
    void sampleNameFallsBackToTheImmutableUniverseSnapshot() {
        AiSample sample = new AiSample();
        sample.stockCode = "688525";
        sample.stockName = "688525";
        AiResearchUniverseItem universeItem = new AiResearchUniverseItem();
        universeItem.stockCode = "688525";
        universeItem.stockName = "佰维存储";

        assertThat(AiResearchLabQueryServiceImpl.sampleDisplayName(sample, universeItem))
                .isEqualTo("佰维存储");
    }
}
