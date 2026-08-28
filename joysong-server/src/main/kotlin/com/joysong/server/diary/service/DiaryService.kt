package com.joysong.server.diary.service

import com.joysong.server.diary.entity.DiaryEntity
import com.joysong.server.diary.entity.dto.PublishDiaryRequest
import com.joysong.server.diary.entity.dto.UpdateDiaryRequest
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.diary.repository.DiaryShareRepository
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.user.repository.UserRepository
import com.joysong.server.user.service.AccountLifecycleGuard
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
class DiaryService(
    private val diaryRepository: DiaryRepository,
    private val userRepository: UserRepository,
    private val projectRepository: ProjectRepository,
    private val doctorRepository: DoctorRepository,
    private val institutionRepository: InstitutionRepository,
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver,
    private val orderRepository: OrderRepository,
    private val diaryShareRepository: DiaryShareRepository,
    private val accountLifecycleGuard: AccountLifecycleGuard? = null,
) {

    /**
     * 解析机构-项目关联信息，统一处理 institutionProjectId 到 institutionId/projectId/name 的回填
     */
    private data class ResolvedAssociation(
        val institutionProjectId: String,
        val projectId: String,
        val projectName: String,
        val institutionId: String,
        val institutionName: String
    )

    private fun resolveInstitutionProject(
        institutionProjectId: String?,
        projectId: String?,
        institutionId: String?,
        fallbackProjectName: String = "",
        fallbackInstitutionName: String = ""
    ): ResolvedAssociation {
        var resolvedIpId = institutionProjectId ?: ""
        var resolvedProjectId = projectId ?: ""
        var resolvedProjectName = fallbackProjectName
        var resolvedInstitutionId = institutionId ?: ""
        var resolvedInstitutionName = fallbackInstitutionName

        if (resolvedIpId.isNotBlank()) {
            val ip = institutionProjectRepository.findById(resolvedIpId)
                .orElseThrow { IllegalArgumentException("机构项目不存在") }
            resolvedInstitutionId = ip.institutionId
            resolvedInstitutionName = institutionRepository.findById(ip.institutionId)
                .orElseThrow { IllegalArgumentException("机构项目关联的机构不存在") }.name
            resolvedProjectId = ip.projectId
            val project = projectRepository.findById(ip.projectId)
                .orElseThrow { IllegalArgumentException("机构项目关联的项目不存在") }
            resolvedProjectName = institutionProjectDetailResolver.resolve(ip, project).name
        }

        return ResolvedAssociation(
            institutionProjectId = resolvedIpId,
            projectId = resolvedProjectId,
            projectName = resolvedProjectName,
            institutionId = resolvedInstitutionId,
            institutionName = resolvedInstitutionName
        )
    }

    fun getMyDiaries(userId: String): List<DiaryEntity> {
        return diaryRepository.findByUserIdOrderByCreatedAtDesc(userId)
    }

    @Caching(evict = [
        CacheEvict(cacheNames = ["diaries"], allEntries = true, beforeInvocation = false),
        CacheEvict(cacheNames = ["home"], allEntries = true, beforeInvocation = false),
        CacheEvict(cacheNames = ["discover"], allEntries = true, beforeInvocation = false)
    ])
    @Transactional
    fun publishDiary(userId: String, request: PublishDiaryRequest): Any {
        val user = accountLifecycleGuard?.requireActiveForWrite(userId) ?: userRepository.findById(userId).orElse(null)
            ?: return mapOf("error" to "用户不存在", "code" to 404)
        validateText(request.title, request.content)
        validateRating(request.rating)
        val normalizedStatus = normalizeStatus(request.status ?: "published")
        validateReferences(userId, request.doctorId, request.projectId, request.institutionId, request.orderId)

        val projectName = if (!request.projectId.isNullOrBlank()) projectRepository.findById(request.projectId).orElse(null)?.name ?: "" else ""
        val doctorName = if (!request.doctorId.isNullOrBlank()) doctorRepository.findById(request.doctorId).orElse(null)?.name ?: "" else ""
        val institutionName = if (!request.institutionId.isNullOrBlank()) institutionRepository.findById(request.institutionId).orElse(null)?.name ?: "" else ""

        val resolved = resolveInstitutionProject(
            institutionProjectId = request.institutionProjectId,
            projectId = request.projectId,
            institutionId = request.institutionId,
            fallbackProjectName = projectName,
            fallbackInstitutionName = institutionName
        )

        val diary = DiaryEntity(
            id = UUID.randomUUID().toString(),
            title = request.title.trim(),
            userId = userId,
            authorName = user.nickname,
            authorAvatar = user.avatar,
            content = request.content.trim(),
            images = request.images,
            tags = request.tags,
            publishDate = LocalDate.now(),
            status = normalizedStatus,
            rating = request.rating ?: 0,
            doctorId = request.doctorId ?: "",
            projectId = resolved.projectId,
            institutionProjectId = resolved.institutionProjectId,
            institutionId = resolved.institutionId,
            projectName = resolved.projectName,
            doctorName = doctorName,
            institutionName = resolved.institutionName,
            orderId = request.orderId ?: "",
            beforeImages = request.beforeImages ?: "",
            afterImages = request.afterImages ?: ""
        )
        return diaryRepository.save(diary)
    }

    @Caching(evict = [
        CacheEvict(cacheNames = ["diaries"], allEntries = true, beforeInvocation = false),
        CacheEvict(cacheNames = ["home"], allEntries = true, beforeInvocation = false),
        CacheEvict(cacheNames = ["discover"], allEntries = true, beforeInvocation = false)
    ])
    @Transactional
    fun updateDiary(userId: String, id: String, request: UpdateDiaryRequest): Any {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val diary = diaryRepository.findById(id).orElse(null)
            ?: return mapOf("error" to "日记不存在", "code" to 404)

        if (diary.userId != userId) {
            return mapOf("error" to "无权编辑他人日记", "code" to 403)
        }
        request.title?.let { validateText(it, request.content ?: diary.content) }
        request.content?.let { validateText(request.title ?: diary.title, it) }
        validateRating(request.rating)
        request.status?.let(::normalizeStatus)
        validateReferences(userId, request.doctorId, request.projectId, request.institutionId, request.orderId)

        val newDoctorId = request.doctorId ?: diary.doctorId
        val doctorName = if (request.doctorId != null) doctorRepository.findById(request.doctorId).orElse(null)?.name ?: "" else diary.doctorName

        // 先计算非 institutionProject 情况下的 fallback 名称
        val fallbackProjectName = if (request.projectId != null) projectRepository.findById(request.projectId).orElse(null)?.name ?: "" else diary.projectName
        val fallbackInstitutionName = if (request.institutionId != null) institutionRepository.findById(request.institutionId).orElse(null)?.name ?: "" else diary.institutionName

        val resolved = resolveInstitutionProject(
            institutionProjectId = request.institutionProjectId,
            projectId = request.projectId ?: diary.projectId,
            institutionId = request.institutionId ?: diary.institutionId,
            fallbackProjectName = fallbackProjectName,
            fallbackInstitutionName = fallbackInstitutionName
        )

        val updated = diary.copy(
            title = request.title?.trim() ?: diary.title,
            content = request.content?.trim() ?: diary.content,
            images = request.images ?: diary.images,
            tags = request.tags ?: diary.tags,
            rating = request.rating ?: diary.rating,
            status = request.status?.let(::normalizeStatus) ?: diary.status,
            doctorId = newDoctorId,
            projectId = resolved.projectId,
            institutionProjectId = request.institutionProjectId ?: diary.institutionProjectId,
            institutionId = resolved.institutionId,
            projectName = resolved.projectName,
            doctorName = doctorName,
            institutionName = resolved.institutionName,
            orderId = request.orderId ?: diary.orderId,
            beforeImages = request.beforeImages ?: diary.beforeImages,
            afterImages = request.afterImages ?: diary.afterImages
        )
        val saved = diaryRepository.save(updated)
        if (saved.status != "published") {
            diaryShareRepository.revokeActiveByDiaryId(id, java.time.LocalDateTime.now())
        }
        return saved
    }

    private fun validateText(title: String, content: String) {
        require(title.isNotBlank()) { "标题不能为空" }
        require(title.trim().length <= 200) { "标题不能超过 200 字" }
        require(content.isNotBlank()) { "内容不能为空" }
        require(content.trim().length <= 20000) { "内容不能超过 20000 字" }
    }

    private fun validateRating(rating: Int?) {
        if (rating != null) require(rating in 0..5) { "评分必须在 0-5 之间" }
    }

    private fun normalizeStatus(status: String): String {
        val normalized = status.trim().lowercase()
        require(normalized in setOf("draft", "published")) { "日记状态仅支持 draft 或 published" }
        return normalized
    }

    private fun validateReferences(
        userId: String,
        doctorId: String?,
        projectId: String?,
        institutionId: String?,
        orderId: String?
    ) {
        doctorId?.takeIf(String::isNotBlank)?.let {
            require(doctorRepository.existsById(it)) { "医生不存在" }
        }
        projectId?.takeIf(String::isNotBlank)?.let {
            require(projectRepository.existsById(it)) { "项目不存在" }
        }
        institutionId?.takeIf(String::isNotBlank)?.let {
            require(institutionRepository.existsById(it)) { "机构不存在" }
        }
        orderId?.takeIf(String::isNotBlank)?.let { id ->
            val order = orderRepository.findById(id).orElseThrow { IllegalArgumentException("订单不存在") }
            require(order.userId == userId) { "订单不属于当前用户" }
        }
    }

    @Caching(evict = [
        CacheEvict(cacheNames = ["diaries"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    @Transactional
    fun deleteDiary(userId: String, id: String): Any {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val diary = diaryRepository.findById(id).orElse(null)
            ?: return mapOf("error" to "日记不存在", "code" to 404)

        if (diary.userId != userId) {
            return mapOf("error" to "无权删除他人日记", "code" to 403)
        }

        diaryShareRepository.revokeActiveByDiaryId(id, java.time.LocalDateTime.now())
        diaryRepository.delete(diary)
        return mapOf("message" to "删除日记成功")
    }

    // ---- Admin 方法 ----

    fun adminListDiaries(keyword: String?): List<DiaryEntity> {
        return if (!keyword.isNullOrBlank()) {
            diaryRepository.searchDiaries(keyword.trim())
        } else {
            diaryRepository.findAll()
        }
    }

    fun adminFindById(id: String): DiaryEntity? = diaryRepository.findById(id).orElse(null)

    @Caching(evict = [
        CacheEvict(cacheNames = ["diaries"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    @Transactional
    fun adminSave(entity: DiaryEntity): DiaryEntity {
        val saved = diaryRepository.save(entity)
        if (saved.status != "published") {
            diaryShareRepository.revokeActiveByDiaryId(saved.id, java.time.LocalDateTime.now())
        }
        return saved
    }

    @Caching(evict = [
        CacheEvict(cacheNames = ["diaries"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    @Transactional
    fun adminDeleteById(id: String) {
        diaryShareRepository.revokeActiveByDiaryId(id, java.time.LocalDateTime.now())
        diaryRepository.deleteById(id)
    }

    fun findByAuthorName(authorName: String): List<DiaryEntity> = diaryRepository.findByAuthorName(authorName)

    fun count(): Long = diaryRepository.count()
}
