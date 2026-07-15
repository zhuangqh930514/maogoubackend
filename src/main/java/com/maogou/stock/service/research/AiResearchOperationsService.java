package com.maogou.stock.service.research;

import com.maogou.stock.dto.research.ResearchLabPayloads;

public interface AiResearchOperationsService {

    ResearchLabPayloads.ActionAccepted runDaily(Long actorUserId, ResearchLabPayloads.ActionRequest request);

    ResearchLabPayloads.ActionAccepted runHistoricalBootstrap(Long actorUserId, ResearchLabPayloads.ActionRequest request);

    ResearchLabPayloads.ActionAccepted verifyLabels(Long actorUserId, ResearchLabPayloads.ActionRequest request);

    ResearchLabPayloads.ActionAccepted runWeekly(Long actorUserId, ResearchLabPayloads.ActionRequest request);

    ResearchLabPayloads.ActionAccepted runTraining(Long actorUserId, ResearchLabPayloads.ActionRequest request);

    ResearchLabPayloads.ActionAccepted runUserProjection(Long authenticatedUserId, ResearchLabPayloads.ActionRequest request);

    ResearchLabPayloads.ActionAccepted promote(Long actorUserId, Long strategyId, ResearchLabPayloads.GovernanceRequest request);

    ResearchLabPayloads.ActionAccepted reject(Long actorUserId, Long strategyId, ResearchLabPayloads.GovernanceRequest request);

    ResearchLabPayloads.ActionAccepted rollback(Long actorUserId, Long strategyId, ResearchLabPayloads.GovernanceRequest request);
}
