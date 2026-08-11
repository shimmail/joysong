package com.joysong.server.doctor.service

import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DoctorService(
    private val doctorRepository: DoctorRepository,
    private val doctorInstitutionService: DoctorInstitutionService
) {

    @Cacheable(cacheNames = ["doctors"], key = "'all'")
    fun findAll(): List<DoctorEntity> = doctorRepository.findAll()

    @Cacheable(cacheNames = ["doctors"], key = "#id")
    fun findById(id: String): DoctorEntity? = doctorRepository.findById(id).orElse(null)

    @Caching(evict = [
        CacheEvict(cacheNames = ["doctors"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    fun save(entity: DoctorEntity): DoctorEntity = doctorRepository.save(entity)

    @Transactional
    @Caching(evict = [
        CacheEvict(cacheNames = ["doctors"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    fun updateEditableProfile(
        id: String,
        command: DoctorProfileUpdateCommand
    ): DoctorEntity {
        val updatedRows = doctorRepository.updateEditableProfile(
            id = id,
            name = command.name,
            title = command.title,
            bio = command.bio,
            avatar = command.avatar,
            contactPhone = command.contactPhone,
            specialties = command.specialties,
            credentials = command.credentials,
            credentialImages = command.credentialImages,
            certificationTags = command.certificationTags
        )
        if (updatedRows == 0) throw DoctorProfileNotFoundException()
        return doctorRepository.findById(id).orElseThrow(::DoctorProfileNotFoundException)
    }

    @Caching(evict = [
        CacheEvict(cacheNames = ["doctors"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    fun deleteById(id: String) = doctorRepository.deleteById(id)

    fun findByNameContainingOrSpecialtiesContaining(name: String, specialties: String): List<DoctorEntity> =
        doctorRepository.findByNameContainingOrSpecialtiesContaining(name, specialties)

    fun findByNameContaining(name: String): List<DoctorEntity> = doctorRepository.findByNameContaining(name)

    @Cacheable(cacheNames = ["doctors"], key = "'institution:' + #institutionId")
    fun findByInstitutionId(institutionId: String): List<DoctorEntity> =
        doctorInstitutionService.findByInstitutionId(institutionId)
            .map { it.doctorId }
            .distinct()
            .let { ids -> doctorRepository.findAllById(ids) }

    @Cacheable(cacheNames = ["doctors"], key = "'search:' + #keyword")
    fun searchDoctors(keyword: String): List<DoctorEntity> = doctorRepository.searchDoctors(keyword)

    fun count(): Long = doctorRepository.count()
}
