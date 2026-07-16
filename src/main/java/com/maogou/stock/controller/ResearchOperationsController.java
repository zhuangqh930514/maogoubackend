package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.security.ResearchOperatorAuthorizer;
import com.maogou.stock.service.research.AiResearchOperationsService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/research-lab")
public class ResearchOperationsController {

    private final AiResearchOperationsService operationsService;
    private final ResearchOperatorAuthorizer authorizer;

    public ResearchOperationsController(
            AiResearchOperationsService operationsService,
            ResearchOperatorAuthorizer authorizer
    ) {
        this.operationsService = operationsService;
        this.authorizer = authorizer;
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

    @PostMapping("/actions/run-user-projection")
    public ApiResponse<ResearchLabPayloads.ActionAccepted> runUserProjection(
            @RequestBody(required = false) ResearchLabPayloads.ActionRequest request) {
        return ApiResponse.ok(operationsService.runUserProjection(currentUserId(), value(request)));
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
