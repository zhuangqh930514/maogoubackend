package com.maogou.stock.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.common.GlobalExceptionHandler;
import com.maogou.stock.domain.entity.UserAccount;
import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.mapper.UserAccountMapper;
import com.maogou.stock.security.AuthPrincipal;
import com.maogou.stock.security.ResearchOperatorAuthorizer;
import com.maogou.stock.service.research.AiHistoricalIndustryBarImportService;
import com.maogou.stock.service.research.AiResearchOperationsService;
import com.maogou.stock.service.research.AiModelPackageImportService;
import com.maogou.stock.service.research.AiHistoricalTradingStateImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResearchOperationsAuthorizationTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userCannotRunGlobalResearchAndReceivesHttp403() throws Exception {
        Fixture fixture = fixture("USER");
        authenticate(5L, "USER");

        fixture.mvc.perform(post("/api/ai/research-lab/actions/run-weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("需要研究运维权限"));
        verify(fixture.operations, never()).runWeekly(anyLong(), any());
    }

    @Test
    void databaseRoleDowngradeInvalidatesAnOldOperatorToken() {
        Fixture fixture = fixture("USER");
        authenticate(5L, "OPERATOR");

        assertThatThrownBy(fixture.authorizer::requireOperator)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("需要研究运维权限");
    }

    @Test
    void operatorCreatesAnAsynchronousRunAndReceivesItsPipelineId() throws Exception {
        Fixture fixture = fixture("OPERATOR");
        authenticate(5L, "OPERATOR");
        when(fixture.operations.runWeekly(anyLong(), any())).thenReturn(
                new ResearchLabPayloads.ActionAccepted(901L, "PENDING"));

        fixture.mvc.perform(post("/api/ai/research-lab/actions/run-weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"weekly-20260714\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pipelineRunId").value(901))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        verify(fixture.operations).runWeekly(anyLong(), any());
    }

    @Test
    void userProjectionIgnoresClientUserIdAndUsesAuthenticatedUser() throws Exception {
        Fixture fixture = fixture("USER");
        authenticate(5L, "USER");
        when(fixture.operations.runUserProjection(anyLong(), any())).thenReturn(
                new ResearchLabPayloads.ActionAccepted(902L, "PENDING"));

        fixture.mvc.perform(post("/api/ai/research-lab/actions/run-user-projection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ResearchLabPayloads.ActionRequest(
                                LocalDate.of(2026, 7, 14), null, null, null, null,
                                801L, 999L, "projection-5-20260714"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pipelineRunId").value(902));
        ArgumentCaptor<Long> userCaptor = ArgumentCaptor.forClass(Long.class);
        verify(fixture.operations).runUserProjection(userCaptor.capture(), any());
        org.assertj.core.api.Assertions.assertThat(userCaptor.getValue()).isEqualTo(5L);
    }

    @Test
    void operatorCanImportModelPackageOnlyAsCandidate() throws Exception {
        Fixture fixture = fixture("OPERATOR");
        authenticate(5L, "OPERATOR");
        when(fixture.modelPackageImporter.importCandidate(any(), anyLong())).thenReturn(
                new AiModelPackageImportService.ImportResult(
                        55L, 44L, "A_SHARE_MULTI_HORIZON", "MAOGOU_RANKER",
                        "20260719003353", "CANDIDATE", "a".repeat(64)));

        fixture.mvc.perform(multipart("/api/ai/research-lab/actions/import-model-package")
                        .file(new MockMultipartFile("package", "candidate.tar.gz", "application/gzip", new byte[]{1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE"));
        verify(fixture.modelPackageImporter).importCandidate(any(), anyLong());
    }

    private static Fixture fixture(String databaseRole) {
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        UserAccount user = new UserAccount();
        user.id = 5L;
        user.status = "ACTIVE";
        user.systemRole = databaseRole;
        user.deleted = 0;
        when(mapper.selectById(5L)).thenReturn(user);
        ResearchOperatorAuthorizer authorizer = new ResearchOperatorAuthorizer(mapper);
        AiResearchOperationsService operations = mock(AiResearchOperationsService.class);
        AiModelPackageImportService modelPackageImporter = mock(AiModelPackageImportService.class);
        AiHistoricalTradingStateImportService historicalStateImporter = mock(AiHistoricalTradingStateImportService.class);
        AiHistoricalIndustryBarImportService historicalIndustryBarImporter =
                mock(AiHistoricalIndustryBarImportService.class);
        ResearchOperationsController controller = new ResearchOperationsController(
                operations, modelPackageImporter, historicalStateImporter, historicalIndustryBarImporter, authorizer);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        return new Fixture(mapper, operations, modelPackageImporter, historicalStateImporter,
                historicalIndustryBarImporter, authorizer, mvc);
    }

    private static void authenticate(Long userId, String role) {
        AuthPrincipal principal = new AuthPrincipal(userId, "tester", role);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private record Fixture(
            UserAccountMapper mapper,
            AiResearchOperationsService operations,
            AiModelPackageImportService modelPackageImporter,
            AiHistoricalTradingStateImportService historicalStateImporter,
            AiHistoricalIndustryBarImportService historicalIndustryBarImporter,
            ResearchOperatorAuthorizer authorizer,
            MockMvc mvc
    ) {
    }
}
