package com.joysong.server.institution.repository

import com.joysong.server.institution.entity.InstitutionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InstitutionRepository : JpaRepository<InstitutionEntity, String> {
    fun findByNameContainingOrCityContaining(name: String, city: String): List<InstitutionEntity>

    @Query("SELECT i FROM InstitutionEntity i WHERE i.name LIKE %:keyword% OR i.id = :keyword")
    fun searchInstitutions(@Param("keyword") keyword: String): List<InstitutionEntity>
}
