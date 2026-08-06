package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.AdminIdentityService
import com.joysong.server.identity.service.PrivateIdentityFileService
import org.springframework.core.io.FileSystemResource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/identity")
class AdminIdentityController(
    private val adminIdentityService: AdminIdentityService,
    private val privateIdentityFileService: PrivateIdentityFileService
) {
    @GetMapping("/applications")
    fun listApplications(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) roleCode: String?,
        @RequestParam(required = false) keyword: String?
    ): BaseResponse<*> = BaseResponse.success(adminIdentityService.listApplications(status, roleCode, keyword))

    @PutMapping("/applications/{id}/review")
    fun reviewApplication(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody request: IdentityReviewRequest
    ): BaseResponse<*> {
        adminIdentityService.reviewApplication(id, authentication.adminId(), request.decision, request.reviewNote)
        return BaseResponse.success(null)
    }

    @GetMapping("/files/{fileId}")
    fun previewSubmittedFile(@PathVariable fileId: String): ResponseEntity<FileSystemResource> {
        val file = privateIdentityFileService.loadSubmittedFileForAdmin(fileId)
        val resource = FileSystemResource(file.path)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType))
            .contentLength(resource.contentLength())
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                .filename(file.originalName, Charsets.UTF_8)
                .build()
                .toString())
            .body(resource)
    }

    @GetMapping("/roles")
    fun listRoles(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) roleCode: String?,
        @RequestParam(required = false) keyword: String?
    ): BaseResponse<*> = BaseResponse.success(adminIdentityService.listRoles(status, roleCode, keyword))

    @PutMapping("/roles/{userId}/{roleCode}/revoke")
    fun revokeRole(
        authentication: Authentication,
        @PathVariable userId: String,
        @PathVariable roleCode: String,
        @RequestBody request: RevokeIdentityRequest
    ): BaseResponse<*> {
        adminIdentityService.revokeRole(userId, roleCode, authentication.adminId(), request.reason)
        return BaseResponse.success(null)
    }

    @GetMapping("/memberships")
    fun listMemberships(
        @RequestParam(required = false) institutionId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) memberRole: String?,
        @RequestParam(required = false) keyword: String?
    ): BaseResponse<*> = BaseResponse.success(
        adminIdentityService.listMemberships(institutionId, status, memberRole, keyword)
    )

    @PostMapping("/memberships")
    fun createMembership(@RequestBody request: CreateMembershipRequest): BaseResponse<*> = BaseResponse.success(
        mapOf("id" to adminIdentityService.createMembership(request.userId, request.institutionId, request.memberRole))
    )

    @PutMapping("/memberships/{id}/approve")
    fun approveMembership(authentication: Authentication, @PathVariable id: String): BaseResponse<*> {
        adminIdentityService.approveMembership(id, authentication.adminId())
        return BaseResponse.success(null)
    }

    @PutMapping("/memberships/{id}/revoke")
    fun revokeMembership(@PathVariable id: String): BaseResponse<*> {
        adminIdentityService.revokeMembership(id)
        return BaseResponse.success(null)
    }

    @GetMapping("/doctor-practices")
    fun listDoctorPractices(
        @RequestParam(required = false) institutionId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) keyword: String?
    ): BaseResponse<*> = BaseResponse.success(
        adminIdentityService.listDoctorPractices(institutionId, status, keyword)
    )

    @PutMapping("/doctor-practices/{id}/approve")
    fun approveDoctorPractice(authentication: Authentication, @PathVariable id: String): BaseResponse<*> {
        adminIdentityService.approveDoctorPractice(id, authentication.adminId())
        return BaseResponse.success(null)
    }

    @PutMapping("/doctor-practices/{id}/revoke")
    fun revokeDoctorPractice(@PathVariable id: String): BaseResponse<*> {
        adminIdentityService.revokeDoctorPractice(id)
        return BaseResponse.success(null)
    }

    private fun Authentication.adminId(): String = principal as? String
        ?: throw IllegalArgumentException("无法识别当前管理员")
}

data class IdentityReviewRequest(val decision: String, val reviewNote: String = "")
data class RevokeIdentityRequest(val reason: String)
data class CreateMembershipRequest(val userId: String, val institutionId: String, val memberRole: String)
