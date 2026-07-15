package com.maogou.stock.controller;

import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.service.research.AiResearchLabQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchLabControllerTest {

    @Test
    void exposesTheCompleteResearchLabReadContract() {
        Set<String> expected = Set.of(
                "/overview", "/universe", "/data-batches", "/source-health",
                "/samples", "/samples/{id}", "/predictions", "/labels",
                "/prediction-evaluations", "/factors", "/factor-performance",
                "/datasets", "/datasets/{id}", "/models", "/models/{id}",
                "/walk-forward", "/walk-forward/{id}", "/backtests", "/backtests/{id}",
                "/strategies", "/strategies/{id}", "/shadow-evaluations",
                "/governance-events", "/pipeline-runs", "/pipeline-runs/{id}");
        Set<String> actual = new LinkedHashSet<>();
        for (Method method : ResearchLabController.class.getDeclaredMethods()) {
            GetMapping mapping = method.getAnnotation(GetMapping.class);
            if (mapping != null) {
                actual.addAll(Arrays.asList(mapping.value()));
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void clampsPaginationAndPreservesEverySupportedFilter() {
        AiResearchLabQueryService service = mock(AiResearchLabQueryService.class);
        when(service.samples(any())).thenReturn(ResearchLabPayloads.PageResult.empty(1, 100));
        ResearchLabController controller = new ResearchLabController(service);
        ResearchLabPayloads.QueryFilter filter = new ResearchLabPayloads.QueryFilter(
                0, 500, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14),
                " 600519 ", " READY ", 71L, 81L, " HIGH ");

        controller.samples(filter);

        ArgumentCaptor<ResearchLabPayloads.QueryFilter> captor =
                ArgumentCaptor.forClass(ResearchLabPayloads.QueryFilter.class);
        verify(service).samples(captor.capture());
        assertThat(captor.getValue().page()).isEqualTo(1);
        assertThat(captor.getValue().pageSize()).isEqualTo(100);
        assertThat(captor.getValue().stockCode()).isEqualTo("600519");
        assertThat(captor.getValue().status()).isEqualTo("READY");
        assertThat(captor.getValue().strategyReleaseId()).isEqualTo(71L);
        assertThat(captor.getValue().modelVersionId()).isEqualTo(81L);
        assertThat(captor.getValue().qualityStatus()).isEqualTo("HIGH");
        assertThat(captor.getValue().dateFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(captor.getValue().dateTo()).isEqualTo(LocalDate.of(2026, 7, 14));
    }

    @Test
    void returnsOnlyQueryServiceResultsWithoutRunningResearchAlgorithms() {
        AiResearchLabQueryService service = mock(AiResearchLabQueryService.class);
        ResearchLabPayloads.Detail detail = new ResearchLabPayloads.Detail(
                new ResearchLabPayloads.EvidenceItem("sample", java.util.Map.of("id", 9L)),
                java.util.Map.of("labels", List.of()));
        when(service.sample(9L)).thenReturn(detail);
        ResearchLabController controller = new ResearchLabController(service);

        ResearchLabPayloads.Detail response = controller.sample(9L).data();

        assertThat(response.record().fields()).containsEntry("id", 9L);
        verify(service).sample(9L);
    }
}
