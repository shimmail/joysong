package com.joysong.server.admin.controller

import com.joysong.server.banner.entity.BannerEntity
import com.joysong.server.banner.service.BannerService
import com.joysong.server.common.BaseResponse
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/admin")
class AdminBannerController(
    private val bannerService: BannerService
) {

    @GetMapping("/banners")
    fun listBanners(): BaseResponse<*> = BaseResponse.success(bannerService.findAll())

    @PostMapping("/banners")
    fun createBanner(@RequestBody entity: BannerEntity): BaseResponse<*> =
        BaseResponse.success(bannerService.save(entity.copy(id = UUID.randomUUID().toString())))

    @PutMapping("/banners/{id}")
    fun updateBanner(@PathVariable id: String, @RequestBody entity: BannerEntity): BaseResponse<*> {
        val existing = bannerService.findById(id)
            ?: return BaseResponse.error<Any>("Banner不存在")
        return BaseResponse.success(bannerService.save(entity.copy(id = id, deletedAt = existing.deletedAt)))
    }

    @DeleteMapping("/banners/{id}")
    fun deleteBanner(@PathVariable id: String): BaseResponse<*> {
        bannerService.deleteById(id)
        return BaseResponse.success(null)
    }
}
