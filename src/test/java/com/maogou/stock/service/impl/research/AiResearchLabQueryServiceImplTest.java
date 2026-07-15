package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
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
}
