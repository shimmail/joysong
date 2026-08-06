package com.joysong.server.home.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.discover.dto.ArticleResponse
import com.joysong.server.discover.dto.BannerResponse
import com.joysong.server.discover.dto.DiaryResponse
import com.joysong.server.discover.dto.ProjectResponse
import com.joysong.server.home.entity.dto.RecommendedInstitutionProjectDto
import com.joysong.server.home.service.HomeService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/home")
class HomeController(private val homeService: HomeService) {

    @GetMapping("/banners")
    fun getBanners(): BaseResponse<List<BannerResponse>> {
        return BaseResponse.success(homeService.getBanners())
    }

    @GetMapping("/hot-projects")
    fun getHotProjects(): BaseResponse<List<ProjectResponse>> {
        return BaseResponse.success(homeService.getHotProjects())
    }

    @GetMapping("/expert-articles")
    fun getExpertArticles(): BaseResponse<List<ArticleResponse>> {
        return BaseResponse.success(homeService.getExpertArticles())
    }

    @GetMapping("/user-diaries")
    fun getUserDiaries(): BaseResponse<List<DiaryResponse>> {
        return BaseResponse.success(homeService.getUserDiaries())
    }

    /**
     * 首页推荐机构项目：按销量倒序返回前 8 个机构项目，合并项目模板信息
     */
    @GetMapping("/recommended-institution-projects")
    fun getRecommendedInstitutionProjects(): BaseResponse<List<RecommendedInstitutionProjectDto>> {
        return BaseResponse.success(homeService.getRecommendedInstitutionProjects())
    }
}
