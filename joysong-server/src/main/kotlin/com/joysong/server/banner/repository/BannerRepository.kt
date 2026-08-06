package com.joysong.server.banner.repository

import com.joysong.server.banner.entity.BannerEntity
import org.springframework.data.jpa.repository.JpaRepository

interface BannerRepository : JpaRepository<BannerEntity, String> {
    fun findAllByOrderBySortOrderAsc(): List<BannerEntity>
}
