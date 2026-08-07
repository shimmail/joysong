package com.joysong.server.discover.dto

import com.joysong.server.review.dto.ReviewResponse
import java.math.BigDecimal

data class DoctorDetailDto(
    val doctor: DoctorResponse,
    val institutionProjects: List<DoctorInstitutionProjectInfo> = emptyList(),
    val diaries: List<DiaryResponse> = emptyList(),
    val reviews: List<ReviewResponse> = emptyList(),
    /** 兼容字段：当前主机构。 */
    val institution: InstitutionResponse? = null,
    /** 医生全部有效出诊机构，主机构排在首位。 */
    val institutions: List<InstitutionResponse> = emptyList()
)

/**
 * 医生详情页中的机构项目信息（包含项目名、机构名、价格等）
 */
data class DoctorInstitutionProjectInfo(
    val institutionProjectId: String,
    val projectId: String,
    val projectName: String,
    val institutionId: String,
    val institutionName: String,
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val coverImage: String,
    val salesCount: Int,
    val category: String = "",
    val description: String = "",
    val rating: BigDecimal = BigDecimal.ZERO,
    val reviewCount: Int = 0,
    val tags: String = "",
    val slogan: String = "",
    val detailContent: String? = null
)

data class ProjectDetailDto(
    val project: ProjectResponse,
    val institutionProjects: List<InstitutionProjectWithInstitution> = emptyList(),
    val diaries: List<DiaryResponse> = emptyList(),
    val reviews: List<ReviewResponse> = emptyList()
)

data class InstitutionProjectWithInstitution(
    val institutionProject: InstitutionProjectResponse,
    val institution: InstitutionResponse,
    val project: ProjectResponse
)

data class InstitutionProjectDetailDto(
    val institutionProject: InstitutionProjectResponse,
    val project: ProjectResponse,
    val institution: InstitutionResponse,
    val diaries: List<DiaryResponse> = emptyList(),
    val doctors: List<DoctorResponse> = emptyList(),
    val reviews: List<ReviewResponse> = emptyList()
)

data class InstitutionProjectWithProject(
    val institutionProject: InstitutionProjectResponse,
    val project: ProjectResponse
)

/**
 * 机构详情页中的可预约项目信息（合并机构项目关联信息与项目模板信息）
 */
data class InstitutionProjectInfo(
    val institutionProjectId: String,
    val projectId: String,
    val projectName: String,
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val coverImage: String,
    val description: String,
    val category: String,
    val categoryTags: String,
    val salesCount: Int,
    val rating: BigDecimal,
    val reviewCount: Int,
    val tags: String,
    val slogan: String,
    val detailContent: String?,
    val images: String
)

data class InstitutionDetailDto(
    val institution: InstitutionResponse,
    val projects: List<InstitutionProjectInfo> = emptyList(),
    val doctors: List<DoctorResponse> = emptyList(),
    val diaries: List<DiaryResponse> = emptyList(),
    val reviews: List<ReviewResponse> = emptyList()
)

/**
 * 发现页筛选选项响应
 */
data class FilterOptionsResponse(
    val categories: List<String>,
    val tags: List<String>,
    val cities: List<String>
)

/**
 * 项目列表响应（包含机构项目嵌套）
 */
data class ProjectWithInstitutionsResponse(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val tags: String,
    val categoryTags: String,
    val coverImage: String,
    val images: String,
    val referencePrice: BigDecimal,
    val slogan: String,
    val detailContent: String?,
    val salesCount: Int,
    val rating: BigDecimal,
    val reviewCount: Int,
    val institutionProjects: List<InstitutionProjectItemResponse> = emptyList()
)

/**
 * 机构项目嵌套响应（包含机构名称和城市）
 */
data class InstitutionProjectItemResponse(
    val id: String,
    val institutionId: String,
    val institutionName: String,
    val institutionCity: String,
    val projectId: String,
    val name: String,
    val category: String,
    val description: String,
    val rating: BigDecimal,
    val reviewCount: Int,
    val tags: String,
    val slogan: String,
    val detailContent: String?,
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val coverImage: String,
    val images: String,
    val salesCount: Int,
    val isActive: Boolean
)
