package com.joysong.server.admin.controller

import com.joysong.server.article.service.ArticleService
import com.joysong.server.banner.service.BannerService
import com.joysong.server.common.BaseResponse
import com.joysong.server.diary.service.DiaryService
import com.joysong.server.doctor.service.DoctorService
import com.joysong.server.institution.service.InstitutionService
import com.joysong.server.order.service.OrderService
import com.joysong.server.project.service.ProjectService
import com.joysong.server.user.service.UserProfileService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
class AdminStatsController(
    private val projectService: ProjectService,
    private val doctorService: DoctorService,
    private val institutionService: InstitutionService,
    private val articleService: ArticleService,
    private val diaryService: DiaryService,
    private val orderService: OrderService,
    private val userProfileService: UserProfileService,
    private val bannerService: BannerService
) {

    @GetMapping("/stats")
    fun getStats(): BaseResponse<*> {
        return BaseResponse.success(mapOf(
            "projects" to projectService.count(),
            "doctors" to doctorService.count(),
            "institutions" to institutionService.count(),
            "articles" to articleService.count(),
            "diaries" to diaryService.count(),
            "orders" to orderService.count(),
            "users" to userProfileService.count(),
            "banners" to bannerService.count()
        ))
    }
}
