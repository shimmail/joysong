package com.joysong.server.common.initializer

import com.joysong.server.article.entity.ArticleEntity
import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.banner.entity.BannerEntity
import com.joysong.server.banner.repository.BannerRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "seed.demo", name = ["enabled"], havingValue = "true")
@Order(5)
class ContentDataInitializer(
    private val bannerRepository: BannerRepository,
    private val articleRepository: ArticleRepository
) : CommandLineRunner {

    @Transactional
    override fun run(args: Array<String>) {
        // ============================================================
        // 轮播图 Banner（3 条）
        // ============================================================
        if (bannerRepository.count() == 0L) {
            bannerRepository.saveAll(listOf(
                BannerEntity(
                    id = UUID.randomUUID().toString(),
                    title = "新人专享礼遇",
                    subtitle = "首次注射美容项目享 7.5 折",
                    imageUrl = "https://via.placeholder.com/750x400?text=BannerNewUser",
                    sortOrder = 1
                ),
                BannerEntity(
                    id = UUID.randomUUID().toString(),
                    title = "热玛吉限时特惠",
                    subtitle = "第五代热玛吉 FLX 限时优惠中",
                    imageUrl = "https://via.placeholder.com/750x400?text=BannerThermage",
                    sortOrder = 2
                ),
                BannerEntity(
                    id = UUID.randomUUID().toString(),
                    title = "发现优质机构",
                    subtitle = "认证医美机构，安心变美",
                    imageUrl = "https://via.placeholder.com/750x400?text=BannerInstitution",
                    sortOrder = 3
                )
            ))
        }

        // ============================================================
        // 文章（3 篇）
        // ============================================================
        if (articleRepository.count() == 0L) {
            articleRepository.saveAll(listOf(
                ArticleEntity(
                    id = UUID.randomUUID().toString(),
                    title = "玻尿酸填充全攻略：术前准备与术后护理",
                    summary = "详细介绍玻尿酸填充的术前注意事项、术后护理要点以及常见问题解答。",
                    coverImage = "https://via.placeholder.com/400x300?text=HyaluronicAcid",
                    content = "玻尿酸填充是目前最受欢迎的微整项目之一...",
                    publishDate = LocalDate.of(2026, 7, 1),
                    doctorId = SeedIds.DOC_ID_1
                ),
                ArticleEntity(
                    id = UUID.randomUUID().toString(),
                    title = "皮秒祛斑：你需要知道的一切",
                    summary = "从原理到恢复期，全面解读皮秒祛斑的效果与注意事项。",
                    coverImage = "https://via.placeholder.com/400x300?text=PicoLaser",
                    content = "皮秒激光技术是近年来祛斑领域的重大突破...",
                    publishDate = LocalDate.of(2026, 7, 5),
                    doctorId = SeedIds.DOC_ID_3
                ),
                ArticleEntity(
                    id = UUID.randomUUID().toString(),
                    title = "热玛吉抗衰：逆转肌龄的秘密",
                    summary = "深入解析热玛吉的工作原理、适合人群及效果维持时间。",
                    coverImage = "https://via.placeholder.com/400x300?text=Thermage",
                    content = "热玛吉作为非侵入式抗衰技术的代表...",
                    publishDate = LocalDate.of(2026, 7, 10),
                    doctorId = SeedIds.DOC_ID_4
                )
            ))
        }
    }
}
