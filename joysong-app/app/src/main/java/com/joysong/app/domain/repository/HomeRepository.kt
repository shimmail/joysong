package com.joysong.app.domain.repository

import com.joysong.app.domain.model.Banner
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.ExpertArticle
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.Project
import com.joysong.app.domain.model.RecommendedInstitutionProject

interface HomeRepository {
    suspend fun getBanners(): Result<List<Banner>>
    suspend fun getHotProjects(): Result<List<Project>>
    suspend fun getExpertArticles(): Result<List<ExpertArticle>>
    suspend fun getUserDiaries(): Result<List<Diary>>
    suspend fun getInstitutions(): Result<List<Institution>>
    suspend fun getDoctors(): Result<List<Doctor>>
    suspend fun getRecommendedInstitutionProjects(): Result<List<RecommendedInstitutionProject>>
}
