package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
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
}
