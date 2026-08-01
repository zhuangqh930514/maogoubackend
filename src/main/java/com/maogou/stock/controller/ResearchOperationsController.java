package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.security.ResearchOperatorAuthorizer;
import com.maogou.stock.service.research.AiModelPackageImportService;
import com.maogou.stock.service.research.AiChallengerReleaseService;
import com.maogou.stock.service.research.AiImportedModelQualificationService;
import com.maogou.stock.service.research.AiTrainingDatasetPackageImportService;
import com.maogou.stock.service.research.AiHistoricalIndustryBarImportService;
import com.maogou.stock.service.research.AiHistoricalTradingStateImportService;
import com.maogou.stock.service.research.AiResearchOperationsService;
import com.maogou.stock.service.research.AiConditionalRuleGovernanceService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ai/research-lab")
public class ResearchOperationsController {

    private final AiResearchOperationsService operationsService;
    private final AiModelPackageImportService modelPackageImportService;
    private final AiTrainingDatasetPackageImportService trainingDatasetPackageImportService;
    private final AiHistoricalTradingStateImportService historicalTradingStateImportService;
    private final AiHistoricalIndustryBarImportService historicalIndustryBarImportService;
    private final AiConditionalRuleGovernanceService conditionalRuleGovernanceService;
    private final ResearchOperatorAuthorizer authorizer;
    private final AiChallengerReleaseService challengerReleaseService;
    private final AiImportedModelQualificationService importedModelQualificationService;

    public ResearchOperationsController(
            AiResearchOperationsService operationsService,
            AiModelPackageImportService modelPackageImportService,
            AiTrainingDatasetPackageImportService trainingDatasetPackageImportService,
            AiHistoricalTradingStateImportService historicalTradingStateImportService,
            AiHistoricalIndustryBarImportService historicalIndustryBarImportService,
            AiConditionalRuleGovernanceService conditionalRuleGovernanceService,
            ResearchOperatorAuthorizer authorizer
    ) {
        this(operationsService, modelPackageImportService, trainingDatasetPackageImportService,
                historicalTradingStateImportService, historicalIndustryBarImportService,
                conditionalRuleGovernanceService, authorizer, null, null);
    }

    public ResearchOperationsController(
            AiResearchOperationsService operationsService,
            AiModelPackageImportService modelPackageImportService,
            AiTrainingDatasetPackageImportService trainingDatasetPackageImportService,
            AiHistoricalTradingStateImportService historicalTradingStateImportService,
            AiHistoricalIndustryBarImportService historicalIndustryBarImportService,
            AiConditionalRuleGovernanceService conditionalRuleGovernanceService,
            ResearchOperatorAuthorizer authorizer,
            AiChallengerReleaseService challengerReleaseService
    ) {
        this(operationsService, modelPackageImportService, trainingDatasetPackageImportService,
                historicalTradingStateImportService, historicalIndustryBarImportService,
                conditionalRuleGovernanceService, authorizer, challengerReleaseService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ResearchOperationsController(
            AiResearchOperationsService operationsService,
            AiModelPackageImportService modelPackageImportService,
            AiTrainingDatasetPackageImportService trainingDatasetPackageImportService,
            AiHistoricalTradingStateImportService historicalTradingStateImportService,
            AiHistoricalIndustryBarImportService historicalIndustryBarImportService,
            AiConditionalRuleGovernanceService conditionalRuleGovernanceService,
            ResearchOperatorAuthorizer authorizer,
            AiChallengerReleaseService challengerReleaseService,
            AiImportedModelQualificationService importedModelQualificationService
    ) {
        this.operationsService = operationsService;
        this.modelPackageImportService = modelPackageImportService;
        this.trainingDatasetPackageImportService = trainingDatasetPackageImportService;
        this.historicalTradingStateImportService = historicalTradingStateImportService;
        this.historicalIndustryBarImportService = historicalIndustryBarImportService;
        this.conditionalRuleGovernanceService = conditionalRuleGovernanceService;
        this.authorizer = authorizer;
        this.challengerReleaseService = challengerReleaseService;
        this.importedModelQualificationService = importedModelQualificationService;
    }

    @PostMapping("/actions/run-daily")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> runDaily(
            @RequestBody(required = false) ResearchLabPayloads.ActionRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(operationsService.runDaily(currentUserId(), value(request)));
    }

    @PostMapping("/actions/run-historical-bootstrap")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> runHistoricalBootstrap(
            @RequestBody(required = false) ResearchLabPayloads.ActionRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(operationsService.runHistoricalBootstrap(currentUserId(), value(request)));
    }

    @PostMapping("/actions/verify-labels")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> verifyLabels(
            @RequestBody(required = false) ResearchLabPayloads.ActionRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(operationsService.verifyLabels(currentUserId(), value(request)));
    }

    @PostMapping("/actions/run-weekly")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> runWeekly(
            @RequestBody(required = false) ResearchLabPayloads.ActionRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(operationsService.runWeekly(currentUserId(), value(request)));
    }

    @PostMapping("/actions/run-training")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> runTraining(
            @RequestBody(required = false) ResearchLabPayloads.ActionRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(operationsService.runTraining(currentUserId(), value(request)));
    }

    @PostMapping(value = "/actions/import-model-package", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AiModelPackageImportService.ImportResult> importModelPackage(
            @RequestParam("package") MultipartFile packageFile) {
        authorizer.requireOperator();
        return ApiResponse.ok(modelPackageImportService.importCandidate(packageFile, currentUserId()));
    }

    @PostMapping("/models/{modelId}/qualify-and-shadow")
    public ApiResponse<?> qualifyAndShadow(@PathVariable Long modelId) {
        authorizer.requireOperator();
        if (importedModelQualificationService != null) {
            return ApiResponse.ok(importedModelQualificationService.qualifyAndCreateShadow(
                    modelId, java.time.LocalDateTime.now()));
        }
        if (challengerReleaseService == null) {
            throw new IllegalStateException("Challenger 服务未启用");
        }
        var release = challengerReleaseService.createFromValidatedModel(modelId, java.time.LocalDateTime.now());
        return ApiResponse.ok(new ResearchLabPayloads.ActionAccepted(release.id, release.status));
    }

    @PostMapping(value = "/actions/preview-training-dataset-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AiTrainingDatasetPackageImportService.PreviewResult> previewTrainingDatasetImport(
            @RequestParam("package") MultipartFile packageFile) {
        authorizer.requireOperator();
        return ApiResponse.ok(trainingDatasetPackageImportService.preview(packageFile, currentUserId()));
    }

    @PostMapping(value = "/actions/import-training-dataset", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AiTrainingDatasetPackageImportService.ImportResult> importTrainingDataset(
            @RequestParam("package") MultipartFile packageFile) {
        authorizer.requireOperator();
        return ApiResponse.ok(trainingDatasetPackageImportService.importPackage(packageFile, currentUserId()));
    }

    @PostMapping(value = "/actions/import-historical-trading-state", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AiHistoricalTradingStateImportService.ImportResult> importHistoricalTradingState(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sourceName") String sourceName,
            @RequestParam("sourceRevision") String sourceRevision,
            @RequestParam("sourceObservedAt") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime sourceObservedAt) {
        authorizer.requireOperator();
        return ApiResponse.ok(historicalTradingStateImportService.importCsv(
                new AiHistoricalTradingStateImportService.ImportRequest(
                        file, sourceName, sourceRevision, sourceObservedAt, currentUserId())));
    }

    @PostMapping(value = "/actions/import-historical-industry-bars", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AiHistoricalIndustryBarImportService.ImportResult> importHistoricalIndustryBars(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sourceName") String sourceName,
            @RequestParam("sourceRevision") String sourceRevision,
            @RequestParam("sourceObservedAt") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime sourceObservedAt) {
        authorizer.requireOperator();
        return ApiResponse.ok(historicalIndustryBarImportService.importCsv(
                new AiHistoricalIndustryBarImportService.ImportRequest(
                        file, sourceName, sourceRevision, sourceObservedAt, currentUserId())));
    }

    @PostMapping("/actions/run-user-projection")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> runUserProjection(
            @RequestBody(required = false) ResearchLabPayloads.ActionRequest request) {
        ResearchLabPayloads.ActionRequest action = value(request);
        Long currentUserId = currentUserId();
        Long targetUserId = action.userId() == null ? currentUserId : action.userId();
        if (!targetUserId.equals(currentUserId)) {
            authorizer.requireOperator();
        }
        return ApiResponse.ok(operationsService.runUserProjection(targetUserId, action));
    }

    @PostMapping("/strategies/{id}/promote")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> promote(
            @PathVariable Long id,
            @RequestBody ResearchLabPayloads.GovernanceRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(operationsService.promote(currentUserId(), id, request));
    }

    @PostMapping("/strategies/{id}/reject")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> reject(
            @PathVariable Long id,
            @RequestBody ResearchLabPayloads.GovernanceRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(operationsService.reject(currentUserId(), id, request));
    }

    @PostMapping("/strategies/{id}/rollback")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> rollback(
            @PathVariable Long id,
            @RequestBody ResearchLabPayloads.GovernanceRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(operationsService.rollback(currentUserId(), id, request));
    }

    @PostMapping("/conditional-rules/candidates")
    public ApiResponse<AiConditionalRuleGovernanceService.CandidateResult> createConditionalRuleCandidate(
            @RequestBody ResearchLabPayloads.ConditionalRuleCandidateRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(conditionalRuleGovernanceService.createCandidate(currentUserId(),
                new AiConditionalRuleGovernanceService.CandidateRequest(
                        request.sourceTradeRuleConfigId(), request.versionNo(), request.name(), request.overrideJson(),
                        java.time.LocalDateTime.now())));
    }

    @PostMapping("/actions/run-conditional-rule-walk-forward")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> runConditionalRuleWalkForward(
            @RequestBody ResearchLabPayloads.ConditionalRuleExperimentRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(operationsService.runConditionalRuleWalkForward(currentUserId(), request));
    }

    @PostMapping("/actions/run-conditional-rule-shadow")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> runConditionalRuleShadow(
            @RequestBody ResearchLabPayloads.ConditionalRuleShadowRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(operationsService.runConditionalRuleShadow(currentUserId(), request));
    }

    @PostMapping("/conditional-rules/shadow/{id}/approve")
    public ApiResponse<AiConditionalRuleGovernanceService.ApprovalResult> approveConditionalRule(
            @PathVariable Long id,
            @RequestBody ResearchLabPayloads.ConditionalRuleDecisionRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(conditionalRuleGovernanceService.approve(currentUserId(),
                new AiConditionalRuleGovernanceService.ApprovalRequest(
                        id, request.reason(), request.policyVersion(), java.time.LocalDateTime.now())));
    }

    @PostMapping("/conditional-rules/shadow/{id}/reject")
    public ApiResponse<AiConditionalRuleGovernanceService.ApprovalResult> rejectConditionalRule(
            @PathVariable Long id,
            @RequestBody ResearchLabPayloads.ConditionalRuleDecisionRequest request) {
        authorizer.requireOperator();
        return ApiResponse.ok(conditionalRuleGovernanceService.reject(currentUserId(),
                new AiConditionalRuleGovernanceService.RejectionRequest(
                        id, request.reason(), request.policyVersion(), java.time.LocalDateTime.now())));
    }

    private static Long currentUserId() {
        return AuthContext.currentUserId().orElseThrow(() ->
                new org.springframework.security.access.AccessDeniedException("请先登录"));
    }

    private static ResearchLabPayloads.ActionRequest value(ResearchLabPayloads.ActionRequest request) {
        return request == null
                ? new ResearchLabPayloads.ActionRequest(
                        null, null, null, null, null, null, null, null, null, null)
                : request;
    }
}
