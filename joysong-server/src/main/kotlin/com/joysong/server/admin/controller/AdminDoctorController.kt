package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.catalog.service.CatalogIntegrityService
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.doctor.service.DoctorService
import com.joysong.server.institution.service.InstitutionService
import com.joysong.server.identity.service.IdentityAuthorizationService
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.user.repository.UserRepository
import org.springframework.security.core.Authentication
import org.springframework.cache.annotation.CacheEvict
import org.springframework.web.bind.annotation.*
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@RestController
@RequestMapping("/api/admin")
class AdminDoctorController(
    private val doctorService: DoctorService,
    private val institutionService: InstitutionService,
    private val doctorInstitutionService: DoctorInstitutionService,
    private val catalogIntegrityService: CatalogIntegrityService,
    private val userRepository: UserRepository,
    private val identityAuthorizationService: IdentityAuthorizationService,
    private val managementAccessService: ManagementAccessService
) {

    @GetMapping("/doctors")
    fun listDoctors(authentication: Authentication, @RequestParam(required = false) keyword: String?): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        val list = if (!keyword.isNullOrBlank()) {
            doctorService.searchDoctors(keyword)
        } else {
            doctorService.findAll()
        }
        val visible = if (actor.isAdmin) list else list.filter { it.id in actor.manageableDoctorIds }
        return BaseResponse.success(visible.map(::toResponse))
    }

    @PostMapping("/doctors")
    @Transactional
    @CacheEvict(cacheNames = ["discover"], allEntries = true)
    fun createDoctor(@RequestBody request: DoctorAdminRequest): BaseResponse<*> {
        val userId = request.requiredUserId()
        validateDoctorUser(userId)
        identityAuthorizationService.requireActiveRole(userId, "DOCTOR")
        if (doctorService.findById(userId) != null) {
            return BaseResponse.error<Any>("该用户已绑定医生档案", 409)
        }
        val doctor = doctorService.save(request.toEntity(userId))
        val selection = doctorInstitutionService.sync(doctor.id, request.effectiveInstitutionIds(), request.effectivePrimaryInstitutionId())
        return BaseResponse.success(toResponse(syncLegacyPrimary(doctor, selection.primaryInstitutionId)))
    }

    @PutMapping("/doctors/{id}")
    @Transactional
    @CacheEvict(cacheNames = ["discover"], allEntries = true)
    fun updateDoctor(authentication: Authentication, @PathVariable id: String, @RequestBody request: DoctorAdminRequest): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        managementAccessService.requireDoctor(actor, id)
        val existing = doctorService.findById(id)
            ?: return BaseResponse.error<Any>("医生不存在")
        val userId = request.userId?.takeIf { it.isNotBlank() } ?: id
        require(userId == id) { "医生 ID 必须与绑定的 userId 一致" }
        validateDoctorUser(userId)
        val requested = request.toEntity(userId)
        val doctor = doctorService.save(requested.copy(
            createdAt = existing.createdAt,
            deletedAt = existing.deletedAt,
            rating = if (actor.isAdmin) requested.rating else existing.rating,
            reviewCount = if (actor.isAdmin) requested.reviewCount else existing.reviewCount,
            isVerified = if (actor.isAdmin) requested.isVerified else existing.isVerified,
            institutionId = if (actor.isAdmin) requested.institutionId else existing.institutionId,
            institutionName = if (actor.isAdmin) requested.institutionName else existing.institutionName
        ))
        if (!actor.isAdmin) return BaseResponse.success(toResponse(doctor))
        val selection = doctorInstitutionService.sync(doctor.id, request.effectiveInstitutionIds(), request.effectivePrimaryInstitutionId())
        return BaseResponse.success(toResponse(syncLegacyPrimary(doctor, selection.primaryInstitutionId)))
    }

    @DeleteMapping("/doctors/{id}")
    @Transactional
    @CacheEvict(cacheNames = ["discover"], allEntries = true)
    fun deleteDoctor(@PathVariable id: String): BaseResponse<*> {
        if (doctorService.findById(id) == null) return BaseResponse.error<Any>("医生不存在", 404)
        catalogIntegrityService.doctorDeletionBlocker(id)?.let { message ->
            return BaseResponse.error<Any>(message, 409)
        }
        doctorService.deleteById(id)
        return BaseResponse.success(null)
    }

    private fun syncLegacyPrimary(doctor: DoctorEntity, primaryInstitutionId: String?): DoctorEntity {
        val primary = primaryInstitutionId?.let(institutionService::findById)
        return doctorService.save(doctor.copy(
            institutionId = primary?.id.orEmpty(),
            institutionName = primary?.name.orEmpty()
        ))
    }

    private fun validateDoctorUser(userId: String) {
        require(userRepository.existsById(userId)) { "用户不存在" }
    }

    private fun toResponse(doctor: DoctorEntity): DoctorAdminResponse {
        val institutions = doctorInstitutionService.institutionsFor(doctor.id)
        val primary = institutions.firstOrNull { it.id == doctor.institutionId }
        return DoctorAdminResponse(doctor, institutions.map { InstitutionSummary(it.id, it.name) }, primary?.let { InstitutionSummary(it.id, it.name) })
    }
}

data class DoctorAdminRequest(
    /** 新增医生时必填，并直接作为医生档案主键。 */
    val userId: String? = null,
    val name: String = "", val title: String = "", val bio: String = "", val avatar: String = "",
    val contactPhone: String = "",
    val rating: BigDecimal = BigDecimal("4.5"), val reviewCount: Int = 0, val specialties: String = "",
    val isVerified: Boolean = false, val consultationCount: Int = 0, val credentials: String = "",
    /** 医生主页公开展示图片；默认空，与 identity_applications/private_files 认证材料隔离。 */
    val credentialImages: String = "", val caseCount: Int = 0, val certificationTags: String = "",
    /** 旧客户端单机构输入，服务端转换为主机构绑定。 */
    val institutionId: String? = null,
    val institutionIds: List<String> = emptyList(), val primaryInstitutionId: String? = null
) {
    fun requiredUserId(): String = requireNotNull(userId?.trim()?.takeIf { it.isNotEmpty() }) { "userId 不能为空" }
    fun toEntity(userId: String) = DoctorEntity(id = userId, name = name, title = title, bio = bio, avatar = avatar,
        contactPhone = contactPhone,
        rating = rating, reviewCount = reviewCount, specialties = specialties, isVerified = isVerified,
        consultationCount = consultationCount, credentials = credentials, credentialImages = credentialImages,
        caseCount = caseCount, certificationTags = certificationTags)
    fun effectiveInstitutionIds(): List<String> = institutionIds.ifEmpty { listOfNotNull(institutionId?.takeIf { it.isNotBlank() }) }
    fun effectivePrimaryInstitutionId(): String? = primaryInstitutionId ?: institutionId
}

data class InstitutionSummary(val id: String, val name: String)

data class DoctorAdminResponse(
    val id: String, val userId: String, val name: String, val title: String, val bio: String, val avatar: String,
    val contactPhone: String,
    val institutionId: String, val institutionName: String, val rating: BigDecimal, val reviewCount: Int,
    val specialties: String, val isVerified: Boolean, val consultationCount: Int, val credentials: String,
    val credentialImages: String, val caseCount: Int, val certificationTags: String,
    val institutions: List<InstitutionSummary>, val primaryInstitution: InstitutionSummary?, val institutionCount: Int
) {
    constructor(doctor: DoctorEntity, institutions: List<InstitutionSummary>, primary: InstitutionSummary?) : this(
        doctor.id, doctor.id, doctor.name, doctor.title, doctor.bio, doctor.avatar, doctor.contactPhone,
        doctor.institutionId, doctor.institutionName,
        doctor.rating, doctor.reviewCount, doctor.specialties, doctor.isVerified, doctor.consultationCount,
        doctor.credentials, doctor.credentialImages, doctor.caseCount, doctor.certificationTags,
        institutions, primary, institutions.size
    )
}
