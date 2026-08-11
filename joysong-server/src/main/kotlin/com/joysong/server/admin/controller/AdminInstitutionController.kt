package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.catalog.service.CatalogIntegrityService
import com.joysong.server.doctor.service.DoctorService
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.service.InstitutionService
import com.joysong.server.identity.service.ManagementAccessService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@RestController
@RequestMapping("/api/admin")
class AdminInstitutionController(
    private val institutionService: InstitutionService,
    private val doctorService: DoctorService,
    private val doctorInstitutionService: DoctorInstitutionService,
    private val institutionProjectController: InstitutionProjectController,
    private val catalogIntegrityService: CatalogIntegrityService,
    private val managementAccessService: ManagementAccessService
) {

    @GetMapping("/institutions")
    fun listInstitutions(authentication: Authentication, @RequestParam(required = false) keyword: String?): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        val list = if (!keyword.isNullOrBlank()) {
            institutionService.searchInstitutions(keyword.trim())
        } else {
            institutionService.findAll()
        }
        return BaseResponse.success(if (actor.isAdmin) list else list.filter { it.id in actor.visibleInstitutionIds })
    }

    @GetMapping("/institutions/{id}")
    fun getInstitution(authentication: Authentication, @PathVariable id: String): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        managementAccessService.requireInstitutionVisible(actor, id)
        val institution = institutionService.findById(id)
            ?: return BaseResponse.error<Any>("机构不存在")
        return BaseResponse.success(institution)
    }

    @PostMapping("/institutions")
    fun createInstitution(@RequestBody entity: InstitutionEntity): BaseResponse<*> =
        BaseResponse.success(institutionService.save(entity.copy(id = UUID.randomUUID().toString())))

    @PutMapping("/institutions/{id}")
    @Transactional
    fun updateInstitution(authentication: Authentication, @PathVariable id: String, @RequestBody entity: InstitutionEntity): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        managementAccessService.requirePlatformAdmin(actor)
        val existing = institutionService.findById(id)
            ?: return BaseResponse.error<Any>("机构不存在")
        return BaseResponse.success(institutionService.save(entity.copy(
            id = id,
            createdAt = existing.createdAt,
            deletedAt = existing.deletedAt,
            rating = entity.rating,
            reviewCount = entity.reviewCount,
            isVerified = entity.isVerified,
            projectCount = entity.projectCount,
            doctorCount = entity.doctorCount,
            consultationCount = entity.consultationCount
        )))
    }

    @DeleteMapping("/institutions/{id}")
    @Transactional
    fun deleteInstitution(@PathVariable id: String): BaseResponse<*> {
        if (institutionService.findById(id) == null) return BaseResponse.error<Any>("机构不存在", 404)
        catalogIntegrityService.institutionDeletionBlocker(id)?.let { message ->
            return BaseResponse.error<Any>(message, 409)
        }
        institutionService.deleteById(id)
        return BaseResponse.success(null)
    }

    @GetMapping("/institutions/{id}/doctors")
    fun listDoctorsByInstitution(authentication: Authentication, @PathVariable id: String): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        managementAccessService.requireInstitutionVisible(actor, id)
        val doctors = doctorInstitutionService.findByInstitutionId(id)
            .filter { it.status == "APPROVED" }
            .mapNotNull { doctorService.findById(it.doctorId) }
        return BaseResponse.success(doctors)
    }

    @GetMapping("/institutions/{id}/projects")
    fun listProjectsByInstitution(authentication: Authentication, @PathVariable id: String): BaseResponse<*> {
        val institution = institutionService.findById(id)
            ?: return BaseResponse.error<Any>("机构不存在")
        return institutionProjectController.list(authentication, projectId = null, institutionId = id)
    }
}
