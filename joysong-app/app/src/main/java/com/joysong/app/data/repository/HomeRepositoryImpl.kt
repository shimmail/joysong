package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.domain.model.Banner
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.ExpertArticle
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.Project
import com.joysong.app.domain.model.RecommendedInstitutionProject
import com.joysong.app.domain.repository.HomeRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : HomeRepository {

    override suspend fun getBanners(): Result<List<Banner>> {
        return try {
            val response = apiService.getBanners()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getHotProjects(): Result<List<Project>> {
        return try {
            val response = apiService.getHotProjects()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getExpertArticles(): Result<List<ExpertArticle>> {
        return try {
            val response = apiService.getExpertArticles()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUserDiaries(): Result<List<Diary>> {
        return try {
            val response = apiService.getUserDiaries()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getInstitutions(): Result<List<Institution>> {
        return try {
            val response = apiService.getInstitutions()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getDoctors(): Result<List<Doctor>> {
        return try {
            val response = apiService.getDoctors()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getRecommendedInstitutionProjects(): Result<List<RecommendedInstitutionProject>> {
        return try {
            val response = apiService.getRecommendedInstitutionProjects()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
