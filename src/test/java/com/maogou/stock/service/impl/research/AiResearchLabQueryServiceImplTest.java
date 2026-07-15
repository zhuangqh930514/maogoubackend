package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiSample;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.mapper.research.AiSampleMapper;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    void sampleCountRunsBeforeTheSummaryColumnProjection() {
        AtomicBoolean countObserved = new AtomicBoolean();
        AtomicBoolean listObserved = new AtomicBoolean();
        AiSampleMapper sampleMapper = (AiSampleMapper) Proxy.newProxyInstance(
                AiSampleMapper.class.getClassLoader(),
                new Class<?>[]{AiSampleMapper.class},
                (proxy, method, arguments) -> {
                    if ("selectCount".equals(method.getName())) {
                        QueryWrapper<AiSample> query = (QueryWrapper<AiSample>) arguments[0];
                        assertThat(query.getSqlSelect()).isNull();
                        countObserved.set(true);
                        return 1L;
                    }
                    if ("selectList".equals(method.getName())) {
                        QueryWrapper<AiSample> query = (QueryWrapper<AiSample>) arguments[0];
                        assertThat(query.getSqlSelect())
                                .contains("id", "stock_code", "stock_name", "data_quality_score")
                                .doesNotContain("feature_snapshot");
                        listObserved.set(true);
                        AiSample sample = new AiSample();
                        sample.id = 1L;
                        sample.stockCode = "688525";
                        sample.stockName = "佰维存储";
                        return java.util.List.of(sample);
                    }
                    return null;
                });
        SqlSession sqlSession = (SqlSession) Proxy.newProxyInstance(
                SqlSession.class.getClassLoader(),
                new Class<?>[]{SqlSession.class},
                (proxy, method, arguments) -> "getMapper".equals(method.getName()) ? sampleMapper : null);

        AiResearchLabQueryServiceImpl service = new AiResearchLabQueryServiceImpl(sqlSession);
        ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> result = service.samples(
                new ResearchLabPayloads.QueryFilter(1, 20, null, null, null,
                        null, null, null, null));

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        assertThat(countObserved).isTrue();
        assertThat(listObserved).isTrue();
    }
}
