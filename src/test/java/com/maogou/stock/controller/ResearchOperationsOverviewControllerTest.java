package com.maogou.stock.controller;

import com.maogou.stock.common.GlobalExceptionHandler;
import com.maogou.stock.domain.entity.UserAccount;
import com.maogou.stock.dto.research.ResearchOperationsOverviewPayloads;
import com.maogou.stock.mapper.UserAccountMapper;
import com.maogou.stock.security.AuthPrincipal;
import com.maogou.stock.security.ResearchOperatorAuthorizer;
import com.maogou.stock.service.research.AiResearchOperationsOverviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResearchOperationsOverviewControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void operatorCanReadOverviewAndWindowIsForwarded() throws Exception {
        Fixture fixture = fixture("OPERATOR");
        authenticate(5L, "OPERATOR");
        when(fixture.service.overview(30)).thenReturn(overview());

        fixture.mvc.perform(get("/api/ai/research-lab/operations-overview").param("windowDays", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.windowDays").value(14));
        verify(fixture.service).overview(30);
    }

    @Test
    void databaseRoleDowngradeBlocksGlobalOperationalEvidence() throws Exception {
        Fixture fixture = fixture("USER");
        authenticate(5L, "OPERATOR");

        fixture.mvc.perform(get("/api/ai/research-lab/operations-overview"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("需要研究运维权限"));
    }

    private static Fixture fixture(String databaseRole) {
        UserAccountMapper users = mock(UserAccountMapper.class);
        UserAccount account = new UserAccount();
        account.id = 5L;
        account.status = "ACTIVE";
        account.systemRole = databaseRole;
        account.deleted = 0;
        when(users.selectById(5L)).thenReturn(account);
        AiResearchOperationsOverviewService service = mock(AiResearchOperationsOverviewService.class);
        ResearchOperationsOverviewController controller = new ResearchOperationsOverviewController(
                service, new ResearchOperatorAuthorizer(users));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        return new Fixture(mvc, service);
    }

    private static void authenticate(Long userId, String role) {
        AuthPrincipal principal = new AuthPrincipal(userId, "operator", role);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static ResearchOperationsOverviewPayloads.Overview overview() {
        return new ResearchOperationsOverviewPayloads.Overview(
                LocalDateTime.now(), null, 14,
                new ResearchOperationsOverviewPayloads.TaskSummary(0, java.util.Map.of(), null, null, 0, List.of()),
                new ResearchOperationsOverviewPayloads.SourceSummary(List.of(), List.of()),
                new ResearchOperationsOverviewPayloads.ModelFailureSummary(0, java.util.Map.of(), List.of()),
                new ResearchOperationsOverviewPayloads.DailyReportCoverage(0, 0, 0, List.of()),
                new ResearchOperationsOverviewPayloads.HoldingCoverage(0, 0, List.of()),
                new ResearchOperationsOverviewPayloads.DecisionConflictSummary(0, List.of()),
                new ResearchOperationsOverviewPayloads.UniversePollutionSummary(0, List.of()),
                new ResearchOperationsOverviewPayloads.UniverseLineageSummary(0, 0, List.of()),
                List.of());
    }

    private record Fixture(MockMvc mvc, AiResearchOperationsOverviewService service) {
    }
}
