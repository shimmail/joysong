package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.order.service.SplitConfigProposalRequest
import com.joysong.server.order.service.SplitConfigProposalService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/doctor-institution-project-config-proposals")
class SplitConfigProposalController(
    private val service: SplitConfigProposalService,
    private val managementAccessService: ManagementAccessService
) {
    @GetMapping
    fun list(authentication: Authentication): BaseResponse<*> =
        BaseResponse.success(service.list(managementAccessService.actor(authentication)))

    @PostMapping
    fun submit(authentication: Authentication, @RequestBody request: SplitConfigProposalRequest): BaseResponse<*> =
        BaseResponse.success(service.submit(managementAccessService.actor(authentication), request))

    @PostMapping("/{id}/confirm")
    fun confirm(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody(required = false) request: SplitProposalActionRequest?
    ): BaseResponse<*> = BaseResponse.success(
        service.confirm(managementAccessService.actor(authentication), id, request?.side)
    )

    @PostMapping("/{id}/reject")
    fun reject(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody request: SplitProposalActionRequest
    ): BaseResponse<*> = BaseResponse.success(
        service.reject(managementAccessService.actor(authentication), id, request.note)
    )

    @PostMapping("/{id}/withdraw")
    fun withdraw(authentication: Authentication, @PathVariable id: String): BaseResponse<*> =
        BaseResponse.success(service.withdraw(managementAccessService.actor(authentication), id))
}

data class SplitProposalActionRequest(
    val side: String? = null,
    val note: String = ""
)
