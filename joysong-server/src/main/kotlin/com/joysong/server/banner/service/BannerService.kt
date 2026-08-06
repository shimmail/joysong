package com.joysong.server.banner.service

import com.joysong.server.banner.entity.BannerEntity
import com.joysong.server.banner.repository.BannerRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service

@Service
class BannerService(private val bannerRepository: BannerRepository) {

    @Cacheable(cacheNames = ["banners"], key = "'all'")
    fun findAll(): List<BannerEntity> = bannerRepository.findAll()

    @Cacheable(cacheNames = ["banners"], key = "'sorted'")
    fun findAllByOrderBySortOrderAsc(): List<BannerEntity> = bannerRepository.findAllByOrderBySortOrderAsc()

    @Cacheable(cacheNames = ["banners"], key = "#id")
    fun findById(id: String): BannerEntity? = bannerRepository.findById(id).orElse(null)

    @Caching(evict = [
        CacheEvict(cacheNames = ["banners"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true)
    ])
    fun save(entity: BannerEntity): BannerEntity = bannerRepository.save(entity)

    @Caching(evict = [
        CacheEvict(cacheNames = ["banners"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true)
    ])
    fun deleteById(id: String) = bannerRepository.deleteById(id)

    fun count(): Long = bannerRepository.count()
}
