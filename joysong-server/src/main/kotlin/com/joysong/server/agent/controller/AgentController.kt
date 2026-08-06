package com.joysong.server.agent.controller

import com.joysong.server.agent.dto.AgentAssessmentResponse
import com.joysong.server.agent.dto.AgentPlanResponse
import com.joysong.server.agent.dto.AgentProfileRequest
import com.joysong.server.agent.dto.AgentProfileResponse
import com.joysong.server.agent.dto.CreateAssessmentRequest
import com.joysong.server.agent.dto.AgentCatalogReportRequest
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.service.AgentCatalogService
import com.joysong.server.agent.service.AgentAssessmentService
import com.joysong.server.agent.service.AgentPlanService
import com.joysong.server.agent.service.AgentProfileService
import com.joysong.server.agent.service.AgentTraceService
import com.joysong.server.common.BaseResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agent")
class AgentController(
    private val profileService: AgentProfileService,
    private val assessmentService: AgentAssessmentService,
    private val planService: AgentPlanService,
    private val catalogService: AgentCatalogService,
    private val traceService: AgentTraceService
) {
    @GetMapping("/profile")
    fun getProfile(authentication: Authentication): BaseResponse<AgentProfileResponse> =
        BaseResponse.success(profileService.get(authentication.principal as String))

    @PutMapping("/profile")
    fun updateProfile(
        authentication: Authentication,
        @RequestBody request: AgentProfileRequest
    ): BaseResponse<AgentProfileResponse> = BaseResponse.success(
        profileService.upsert(authentication.principal as String, request)
    )

    @PostMapping("/profile/confirm")
    fun confirmProfile(authentication: Authentication): BaseResponse<AgentProfileResponse> =
        BaseResponse.success(profileService.confirm(authentication.principal as String))

    @PostMapping("/assessments")
    fun createAssessment(
        authentication: Authentication,
        @RequestBody request: CreateAssessmentRequest
    ): BaseResponse<AgentAssessmentResponse> = BaseResponse.success(
        assessmentService.create(authentication.principal as String, request)
    )

    @PostMapping("/assessments/{assessmentId}/plans")
    fun createPlan(
        authentication: Authentication,
        @PathVariable assessmentId: String
    ): BaseResponse<AgentPlanResponse> = BaseResponse.success(
        planService.create(authentication.principal as String, assessmentId)
    )

    @GetMapping("/plans")
    fun listPlans(authentication: Authentication): BaseResponse<List<AgentPlanResponse>> =
        BaseResponse.success(planService.list(authentication.principal as String))

    @GetMapping("/plans/{planId}")
    fun getPlan(
        authentication: Authentication,
        @PathVariable planId: String
    ): BaseResponse<AgentPlanResponse> = BaseResponse.success(
        planService.get(authentication.principal as String, planId)
    )

    @DeleteMapping("/plans/{planId}")
    fun deletePlan(authentication: Authentication, @PathVariable planId: String): BaseResponse<String> {
        planService.delete(authentication.principal as String, planId)
        return BaseResponse.success("ok")
    }

    @DeleteMapping("/plans")
    fun clearPlans(authentication: Authentication): BaseResponse<String> {
        planService.clear(authentication.principal as String)
        return BaseResponse.success("ok")
    }

    @GetMapping("/traces")
    fun listTraces(
        authentication: Authentication,
        @RequestParam(defaultValue = "50") limit: Int
    ): BaseResponse<*> = BaseResponse.success(
        traceService.listForUser(authentication.principal as String, limit)
    )

    @PostMapping("/catalog/report")
    fun catalogReport(@RequestBody request: AgentCatalogReportRequest): BaseResponse<AgentCatalogReportResponse> =
        BaseResponse.success(catalogService.report(request))
}
