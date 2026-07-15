package com.maogou.stock.service.research;

import com.maogou.stock.dto.research.ResearchLabPayloads;

public interface AiResearchLabQueryService {

    ResearchLabPayloads.Overview overview();

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> universe(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> dataBatches(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> sourceHealth(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> samples(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.Detail sample(Long id);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> predictions(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> labels(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> predictionEvaluations(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> factors(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> factorPerformance(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> datasets(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.Detail dataset(Long id);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> models(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.Detail model(Long id);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> walkForward(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.Detail walkForward(Long id);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> backtests(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.Detail backtest(Long id);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> strategies(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.Detail strategy(Long id);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> shadowEvaluations(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> governanceEvents(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> pipelineRuns(ResearchLabPayloads.QueryFilter filter);

    ResearchLabPayloads.Detail pipelineRun(Long id);
}
