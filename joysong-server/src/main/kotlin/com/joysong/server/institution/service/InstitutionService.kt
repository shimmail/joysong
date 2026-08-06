package com.joysong.server.institution.service

import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service

@Service
class InstitutionService(
    private val institutionRepository: InstitutionRepository,
    private val institutionProjectRepository: InstitutionProjectRepository
) {

    @Cacheable(cacheNames = ["institutions"], key = "'all'")
    fun findAll(): List<InstitutionEntity> = institutionRepository.findAll()

    @Cacheable(cacheNames = ["institutions"], key = "#id")
    fun findById(id: String): InstitutionEntity? = institutionRepository.findById(id).orElse(null)

    @Caching(evict = [
        CacheEvict(cacheNames = ["institutions"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    fun save(entity: InstitutionEntity): InstitutionEntity = institutionRepository.save(entity)

    @Caching(evict = [
        CacheEvict(cacheNames = ["institutions"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    fun deleteById(id: String) = institutionRepository.deleteById(id)

    fun findByNameContainingOrCityContaining(name: String, city: String): List<InstitutionEntity> =
        institutionRepository.findByNameContainingOrCityContaining(name, city)

    @Cacheable(cacheNames = ["institutions"], key = "'search:' + #keyword")
    fun searchInstitutions(keyword: String): List<InstitutionEntity> =
        institutionRepository.searchInstitutions(keyword)

    // InstitutionProject 相关方法

    fun findProjectsByInstitutionId(institutionId: String): List<InstitutionProjectEntity> =
        institutionProjectRepository.findByInstitutionId(institutionId)

    fun findProjectsByProjectId(projectId: String): List<InstitutionProjectEntity> =
        institutionProjectRepository.findByProjectId(projectId)

    fun findInstitutionProject(institutionId: String, projectId: String): InstitutionProjectEntity? =
        institutionProjectRepository.findByInstitutionIdAndProjectId(institutionId, projectId)

    fun saveInstitutionProject(entity: InstitutionProjectEntity): InstitutionProjectEntity =
        institutionProjectRepository.save(entity)

    fun deleteInstitutionProjectById(id: String) = institutionProjectRepository.deleteById(id)

    fun count(): Long = institutionRepository.count()
}
