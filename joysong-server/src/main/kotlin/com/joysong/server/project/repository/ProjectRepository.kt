package com.joysong.server.project.repository

import com.joysong.server.project.entity.ProjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProjectRepository : JpaRepository<ProjectEntity, String> {
    fun findByCategoryContainingOrNameContaining(category: String, name: String): List<ProjectEntity>
    fun findByCategory(category: String): List<ProjectEntity>
    fun findByNameContainingOrCategoryContaining(name: String, category: String): List<ProjectEntity>

    @Query("SELECT p FROM ProjectEntity p WHERE p.name LIKE %:keyword% OR p.id = :keyword")
    fun searchProjects(@Param("keyword") keyword: String): List<ProjectEntity>
}
