package com.joysong.server.discover.service

import com.joysong.server.diary.entity.DiaryEntity
import com.joysong.server.discover.dto.DoctorDetailDto
import com.joysong.server.discover.dto.DoctorResponse
import com.joysong.server.discover.dto.DoctorInstitutionProjectInfo
import com.joysong.server.discover.dto.DiaryResponse
import com.joysong.server.discover.dto.InstitutionDetailDto
import com.joysong.server.discover.dto.InstitutionProjectResponse
import com.joysong.server.discover.dto.InstitutionProjectDetailDto
import com.joysong.server.discover.dto.InstitutionProjectInfo
import com.joysong.server.discover.dto.InstitutionProjectWithInstitution
import com.joysong.server.discover.dto.ProjectDetailDto
import com.joysong.server.discover.dto.toResponse
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.review.dto.ReviewResponse
import com.joysong.server.review.entity.ReviewEntity
import com.joysong.server.review.repository.ReviewRepository
import com.joysong.server.user.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class DiscoverDetailService(
    private val doctorRepository: DoctorRepository,
    private val projectRepository: ProjectRepository,
    private val diaryRepository: DiaryRepository,
    private val institutionRepository: InstitutionRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val reviewRepository: ReviewRepository,
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val userRepository: UserRepository,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver,
    private val doctorInstitutionService: DoctorInstitutionService
) {
    fun getDoctorDetail(doctorId: String): DoctorDetailDto? {
        val doctor = doctorRepository.findById(doctorId).orElse(null) ?: return null

        // 通过关联表获取医生的项目列表，优先使用 institutionProjectId
        val doctorProjects = doctorProjectRepository.findByDoctorId(doctorId)

        // N+1 修复：批量查询所有关联的 InstitutionProject、Project、Institution
        val institutionProjectIds = doctorProjects
            .filter { it.institutionProjectId.isNotBlank() }
            .map { it.institutionProjectId }
            .distinct()
        val projectIdsFromIp = doctorProjects
            .filter { it.institutionProjectId.isBlank() && it.projectId.isNotBlank() }
            .map { it.projectId }
            .distinct()

        val ipMap: Map<String, InstitutionProjectEntity> = institutionProjectRepository.findAllById(institutionProjectIds)
            .associateBy { it.id }

        // 收集所有需要查询的 projectId（来自 ip 的 + 来自 doctorProject 直连的）
        val allProjectIds = (ipMap.values.map { it.projectId } + projectIdsFromIp).distinct()
        val projectMap: Map<String, ProjectEntity> = projectRepository.findAllById(allProjectIds)
            .associateBy { it.id }

        val institutionIds = ipMap.values.map { it.institutionId }.distinct()
        val institutionMap: Map<String, InstitutionEntity> =
            institutionRepository.findAllById(institutionIds).associateBy { it.id }

        val institutionProjectInfos = doctorProjects.mapNotNull { dp ->
            if (dp.institutionProjectId.isNotBlank()) {
                val ip = ipMap[dp.institutionProjectId] ?: return@mapNotNull null
                val project = projectMap[ip.projectId]
                val institution = institutionMap[ip.institutionId]
                if (!ip.isActive || project == null) return@mapNotNull null
                val effective = institutionProjectDetailResolver.resolve(ip, project)
                DoctorInstitutionProjectInfo(
                    institutionProjectId = ip.id,
                    projectId = ip.projectId,
                    projectName = effective.name,
                    institutionId = ip.institutionId,
                    institutionName = institution?.name ?: "",
                    price = dp.price,
                    originalPrice = ip.originalPrice,
                    currency = ip.currency,
                    coverImage = effective.coverImage,
                    salesCount = ip.salesCount,
                    category = effective.category,
                    description = effective.description,
                    rating = effective.rating,
                    reviewCount = effective.reviewCount,
                    tags = effective.tags,
                    slogan = effective.slogan,
                    detailContent = effective.detailContent
                )
            } else if (dp.projectId.isNotBlank()) {
                val project = projectMap[dp.projectId] ?: return@mapNotNull null
                DoctorInstitutionProjectInfo(
                    institutionProjectId = "",
                    projectId = project.id,
                    projectName = project.name,
                    institutionId = "",
                    institutionName = "",
                    price = dp.price,
                    originalPrice = null,
                    currency = project.currency,
                    coverImage = project.coverImage,
                    salesCount = project.salesCount
                )
            } else null
        }

        // 获取医生相关的日记
        val diaries = diaryRepository.findPublishedByDoctorId(doctorId)

        // 主机构保留给旧客户端；完整机构列表用于新客户端展示。
        val institutions = doctorInstitutionService.institutionsFor(doctorId)
        val institution = institutions.firstOrNull { it.id == doctor.institutionId } ?: institutions.firstOrNull()
        val reviews = reviewsWithUsers(
            reviewRepository.findByDoctorIdAndTargetType(doctorId, "INSTITUTION")
        )

        return DoctorDetailDto(
            doctor = doctor.toResponse(),
            institutionProjects = institutionProjectInfos,
            diaries = diaries.map { it.toResponse() },
            reviews = reviews,
            institution = institution?.toResponse(),
            institutions = institutions.map { it.toResponse() }
        )
    }

    fun getProjectDetail(projectId: String): ProjectDetailDto? {
        val project = projectRepository.findById(projectId).orElse(null) ?: return null

        // 获取项目的所有机构项目关联
        val institutionProjects = institutionProjectRepository.findByProjectId(projectId).filter { it.isActive }

        // N+1 修复：批量查询所有关联的 Institution
        val institutionIds = institutionProjects.map { it.institutionId }.distinct()
        val institutionMap: Map<String, InstitutionEntity> =
            institutionRepository.findAllById(institutionIds).associateBy { it.id }

        val institutionProjectsWithInstitutions = institutionProjects.mapNotNull { ip ->
            val institution = institutionMap[ip.institutionId] ?: return@mapNotNull null
            InstitutionProjectWithInstitution(
                ip.toResponse(),
                institution.toResponse(),
                institutionProjectDetailResolver.resolve(ip, project).toResponse()
            )
        }

        // 获取项目相关的日记
        val diaries = diaryRepository.findPublishedByProjectId(projectId)
        val reviews = reviewsWithUsers(
            institutionProjects.flatMap { reviewRepository.findByInstitutionProjectId(it.id) }
        )

        return ProjectDetailDto(
            project = project.toResponse(),
            institutionProjects = institutionProjectsWithInstitutions,
            diaries = diaries.map { it.toResponse() },
            reviews = reviews
        )
    }

    fun getInstitutionProjectDetail(institutionId: String, projectId: String): InstitutionProjectDetailDto? {
        val ip = institutionProjectRepository.findByInstitutionIdAndProjectId(institutionId, projectId)
            ?.takeIf { it.isActive } ?: return null
        val project = projectRepository.findById(projectId).orElse(null) ?: return null
        val institution = institutionRepository.findById(institutionId).orElse(null) ?: return null
        val diaries = diaryRepository.findPublishedByProjectId(projectId)
        val doctorProjects = doctorProjectRepository.findByInstitutionProjectId(ip.id)
        val doctorIds = doctorProjects
            .map { it.doctorId }
            .filter { it.isNotBlank() }
            .distinct()
        val doctorsById = doctorRepository.findAllById(doctorIds).associateBy { it.id }
        val doctors = doctorIds.mapNotNull(doctorsById::get)
        val reviews = reviewsWithUsers(reviewRepository.findByInstitutionProjectId(ip.id))
        return InstitutionProjectDetailDto(
            institutionProject = ip.toResponse(),
            project = institutionProjectDetailResolver.resolve(ip, project).toResponse(),
            institution = institution.toResponse(),
            diaries = diaries.map { it.toResponse() },
            doctors = doctors.map { doctor ->
                doctor.toResponse(doctorProjects.first { it.doctorId == doctor.id }.price)
            },
            reviews = reviews
        )
    }

    fun getInstitutionProjects(institutionId: String): List<InstitutionProjectResponse> {
        return institutionProjectRepository.findByInstitutionId(institutionId)
            .filter { it.isActive }
            .map { it.toResponse() }
    }

    fun getInstitutionDiaries(institutionId: String): List<DiaryResponse> {
        return diaryRepository.findPublishedByInstitutionId(institutionId).map { it.toResponse() }
    }

    fun getInstitutionDetail(institutionId: String): InstitutionDetailDto? {
        val institution = institutionRepository.findById(institutionId).orElse(null) ?: return null

        // 通过机构项目关联表获取机构的项目列表，合并项目模板信息（名称、描述、分类）与机构特定信息（价格、封面）
        val institutionProjects = institutionProjectRepository.findByInstitutionId(institutionId).filter { it.isActive }

        // N+1 修复：批量查询所有关联的 Project
        val projectIds = institutionProjects.map { it.projectId }.distinct()
        val projectMap: Map<String, ProjectEntity> = projectRepository.findAllById(projectIds)
            .associateBy { it.id }

        val projects = institutionProjects.mapNotNull { ip ->
            val startingPrice = doctorProjectRepository.findActiveByInstitutionProjectId(ip.id)
                .minOfOrNull { it.price }
                ?: return@mapNotNull null
            val project = projectMap[ip.projectId] ?: return@mapNotNull null
            val effective = institutionProjectDetailResolver.resolve(ip, project)
            InstitutionProjectInfo(
                institutionProjectId = ip.id,
                projectId = ip.projectId,
                projectName = effective.name,
                price = startingPrice,
                originalPrice = ip.originalPrice,
                currency = ip.currency,
                coverImage = effective.coverImage,
                description = effective.description,
                category = effective.category,
                categoryTags = project.categoryTags,
                salesCount = ip.salesCount,
                rating = effective.rating,
                reviewCount = effective.reviewCount,
                tags = effective.tags,
                slogan = effective.slogan,
                detailContent = effective.detailContent,
                images = effective.images
            )
        }

        // 医生与机构已通过 doctor_institutions 解耦，不能再依赖 doctors.institution_id。
        val doctors = findDoctorsByInstitution(institutionId)

        // 获取机构的日记列表
        val diaries = diaryRepository.findPublishedByInstitutionId(institutionId)

        // 获取机构的评价列表，并填充用户名
        val reviews = reviewsWithUsers(
            reviewRepository.findByTargetTypeAndTargetId("INSTITUTION", institutionId)
        )

        return InstitutionDetailDto(
            institution = institution.toResponse(),
            projects = projects,
            doctors = doctors.map { it.toResponse() },
            diaries = diaries.map { it.toResponse() },
            reviews = reviews
        )
    }

    fun getInstitutionDoctors(institutionId: String): List<DoctorResponse> {
        return findDoctorsByInstitution(institutionId).map { it.toResponse() }
    }

    private fun findDoctorsByInstitution(
        institutionId: String
    ): List<com.joysong.server.doctor.entity.DoctorEntity> {
        val doctorIds = doctorInstitutionService.findByInstitutionId(institutionId)
            .map { it.doctorId }
            .distinct()
        if (doctorIds.isEmpty()) return emptyList()

        val doctorsById = doctorRepository.findAllById(doctorIds).associateBy { it.id }
        return doctorIds.mapNotNull(doctorsById::get)
    }

    private fun reviewsWithUsers(entities: List<ReviewEntity>): List<ReviewResponse> {
        if (entities.isEmpty()) return emptyList()
        val userMap = userRepository.findAllById(entities.map { it.userId }.distinct())
            .associateBy { it.id }
        return entities
            .sortedByDescending { it.createdAt }
            .map { entity ->
                ReviewResponse.from(entity, userMap[entity.userId]?.nickname ?: "")
            }
    }
}
