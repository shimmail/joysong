package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.ExpertArticle
import com.joysong.app.domain.model.FilterOptions
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.InstitutionProject
import com.joysong.app.domain.model.Project
import com.joysong.app.domain.model.ProjectWithInstitutions
import com.joysong.app.domain.repository.DiscoverRepository
import com.joysong.app.domain.repository.DoctorDetail
import com.joysong.app.domain.repository.InstitutionDetail
import com.joysong.app.domain.repository.InstitutionProjectDetailInfo
import com.joysong.app.domain.repository.ProjectDetail
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscoverRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : DiscoverRepository {

    override suspend fun getProjects(categories: String, cities: String, tags: String, query: String): Result<List<ProjectWithInstitutions>> {
        return try {
            val response = apiService.getProjects(categories, cities, tags, query)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getFilterOptions(): Result<FilterOptions> {
        return try {
            val response = apiService.getFilterOptions()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getDiaries(query: String): Result<List<Diary>> {
        return try {
            val response = apiService.getDiaries(query)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getDoctors(query: String): Result<List<Doctor>> {
        return try {
            val response = apiService.getDoctors(query)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getInstitutions(query: String): Result<List<Institution>> {
        return try {
            val response = apiService.getInstitutions(query)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getArticles(query: String): Result<List<ExpertArticle>> {
        return try {
            val response = apiService.getArticles(query)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getProjectById(id: String): Result<ProjectDetail> {
        return try {
            val response = apiService.getProjectById(id)
            if (response.code == 200 && response.data != null) {
                val data = response.data
                Result.success(
                    ProjectDetail(
                        project = data.project.toDomain(),
                        institutionProjects = data.institutionProjects.map { ip ->
                            val baseProject = ip.project?.toDomain() ?: data.project.toDomain()
                            ip.institutionProject.toDomain(baseProject) to ip.institution.toDomain()
                        },
                        diaries = data.diaries.map { it.toDomain() }
                    )
                )
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getInstitutionById(id: String): Result<InstitutionDetail> {
        return try {
            val response = apiService.getInstitutionById(id)
            if (response.code == 200 && response.data != null) {
                val data = response.data
                Result.success(
                    InstitutionDetail(
                        institution = data.institution.toDomain(),
                        projects = data.projects.map { it.toDomain() },
                        doctors = data.doctors.map { it.toDomain() },
                        diaries = data.diaries.map { it.toDomain() },
                        reviews = data.reviews.map { it.toDomain() }
                    )
                )
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getDoctorById(id: String): Result<DoctorDetail> {
        return try {
            val response = apiService.getDoctorById(id)
            if (response.code == 200 && response.data != null) {
                val data = response.data
                Result.success(
                    DoctorDetail(
                        doctor = data.doctor.toDomain(),
                        institutionProjects = data.institutionProjects.map { it.toDomain() },
                        diaries = data.diaries.map { it.toDomain() },
                        institution = data.institution?.toDomain(),
                        institutions = data.institutions.map { it.toDomain() }
                    )
                )
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getArticleById(id: String): Result<ExpertArticle> {
        return try {
            val response = apiService.getArticleById(id)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getDiaryById(id: String): Result<Diary> {
        return try {
            val response = apiService.getDiaryById(id)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getInstitutionProjects(institutionId: String): Result<List<InstitutionProject>> {
        return try {
            val response = apiService.getInstitutionProjects(institutionId)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.institutionProject.toDomain(it.project.toDomain()) })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getInstitutionProjectDetail(institutionId: String, projectId: String): Result<InstitutionProjectDetailInfo> {
        return try {
            val response = apiService.getInstitutionProjectDetail(institutionId, projectId)
            if (response.code == 200 && response.data != null) {
                val data = response.data
                val effectiveProject = data.project.toDomain()
                Result.success(
                    InstitutionProjectDetailInfo(
                        institutionProject = data.institutionProject.toDomain(effectiveProject),
                        project = effectiveProject,
                        institution = data.institution.toDomain(),
                        diaries = data.diaries.map { it.toDomain() }
                    )
                )
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getInstitutionDiaries(institutionId: String): Result<List<Diary>> {
        return try {
            val response = apiService.getInstitutionDiaries(institutionId)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getInstitutionDoctors(institutionId: String): Result<List<Doctor>> {
        return try {
            val response = apiService.getInstitutionDoctors(institutionId)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getInstitutionProjectDoctors(institutionProjectId: String): Result<List<Doctor>> {
        return try {
            val response = apiService.getInstitutionProjectDoctors(institutionProjectId)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getConsultationFee(doctorId: String, institutionProjectId: String): Result<Double> {
        return try {
            val response = apiService.getConsultationFee(doctorId, institutionProjectId)
            if (response.code == 200 && response.data != null) {
                val fee = (response.data["consultationFee"] as? Number)?.toDouble() ?: 0.0
                Result.success(fee)
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
