package com.joysong.server.doctor.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.doctor.service.DoctorProfileService
import com.joysong.server.doctor.service.DoctorProfileUpdateCommand
import com.joysong.server.doctor.service.DoctorProfileView
import com.joysong.server.identity.service.ManagementAccessService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/management/doctor-profile")
class DoctorProfileController(
    private val managementAccessService: ManagementAccessService,
    private val doctorProfileService: DoctorProfileService
) {
    @GetMapping
    fun get(authentication: Authentication): BaseResponse<DoctorProfileView> {
        val actor = managementAccessService.actor(authentication)
        return BaseResponse.success(doctorProfileService.get(actor))
    }

    @PutMapping
    fun update(
        authentication: Authentication,
        @RequestBody request: DoctorProfileUpdateRequest
    ): BaseResponse<DoctorProfileView> {
        val actor = managementAccessService.actor(authentication)
        return BaseResponse.success(doctorProfileService.update(actor, request.toCommand()))
    }
}

data class DoctorProfileUpdateRequest(
    val name: String,
    val title: String,
    val bio: String,
    val avatar: String,
    val contactPhone: String,
    val specialties: String,
    val credentials: String,
    val credentialImages: String,
    val certificationTags: String
) {
    fun toCommand(): DoctorProfileUpdateCommand {
        require(name.isNotBlank()) { "name 不能为空" }
        return DoctorProfileUpdateCommand(
            name = name,
            title = title,
            bio = bio,
            avatar = avatar,
            contactPhone = contactPhone,
            specialties = specialties,
            credentials = credentials,
            credentialImages = credentialImages,
            certificationTags = certificationTags
        )
    }
}
