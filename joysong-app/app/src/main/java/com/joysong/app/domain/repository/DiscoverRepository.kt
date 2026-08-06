package com.joysong.app.domain.repository

import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.DoctorInstitutionProjectInfo
import com.joysong.app.domain.model.ExpertArticle
import com.joysong.app.domain.model.FilterOptions
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.InstitutionProject
import com.joysong.app.domain.model.InstitutionProjectInfo
import com.joysong.app.domain.model.Project
import com.joysong.app.domain.model.ProjectWithInstitutions
import com.joysong.app.domain.model.Review

data class DoctorDetail(
    val doctor: Doctor,
    val institutionProjects: List<DoctorInstitutionProjectInfo> = emptyList(),
    val diaries: List<Diary> = emptyList(),
    val institution: Institution? = null,
    val institutions: List<Institution> = emptyList()
)

data class ProjectDetail(
    val project: Project,
    val institutionProjects: List<Pair<InstitutionProject, Institution>> = emptyList(),
    val diaries: List<Diary> = emptyList()
)

data class InstitutionDetail(
    val institution: Institution,
    val projects: List<InstitutionProjectInfo> = emptyList(),
    val doctors: List<Doctor> = emptyList(),
    val diaries: List<Diary> = emptyList(),
    val reviews: List<Review> = emptyList()
)

data class InstitutionProjectDetailInfo(
    val institutionProject: InstitutionProject,
    val project: Project,
    val institution: Institution,
    val diaries: List<Diary> = emptyList()
)

interface DiscoverRepository {
    suspend fun getProjects(categories: String = "", cities: String = "", tags: String = "", query: String = ""): Result<List<ProjectWithInstitutions>>
    suspend fun getFilterOptions(): Result<FilterOptions>
    suspend fun getDiaries(query: String = ""): Result<List<Diary>>
    suspend fun getDoctors(query: String = ""): Result<List<Doctor>>
    suspend fun getInstitutions(query: String = ""): Result<List<Institution>>
    suspend fun getArticles(query: String = ""): Result<List<ExpertArticle>>
    suspend fun getProjectById(id: String): Result<ProjectDetail>
    suspend fun getInstitutionById(id: String): Result<InstitutionDetail>
    suspend fun getDoctorById(id: String): Result<DoctorDetail>
    suspend fun getArticleById(id: String): Result<ExpertArticle>
    suspend fun getDiaryById(id: String): Result<Diary>
    suspend fun getInstitutionProjects(institutionId: String): Result<List<InstitutionProject>>
    suspend fun getInstitutionProjectDetail(institutionId: String, projectId: String): Result<InstitutionProjectDetailInfo>
    suspend fun getInstitutionDiaries(institutionId: String): Result<List<Diary>>
    suspend fun getInstitutionDoctors(institutionId: String): Result<List<Doctor>>
    suspend fun getInstitutionProjectDoctors(institutionProjectId: String): Result<List<Doctor>>
    suspend fun getConsultationFee(doctorId: String, institutionProjectId: String): Result<Double>
}
