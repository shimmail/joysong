package com.joysong.server.common.initializer

import com.joysong.server.diary.entity.DiaryEntity
import com.joysong.server.diary.repository.DiaryRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "seed.demo", name = ["enabled"], havingValue = "true")
@Order(7)
class DiaryDataInitializer(
    private val diaryRepository: DiaryRepository
) : CommandLineRunner {

    @Transactional
    override fun run(args: Array<String>) {
        if (diaryRepository.count() > 0L) return

        // ============================================================
        // 日记（5 条）
        // ============================================================
        diaryRepository.saveAll(listOf(
            DiaryEntity(
                id = UUID.randomUUID().toString(),
                title = "玻尿酸填充苹果肌恢复日记",
                userId = SeedIds.USER_ID_1,
                authorName = "小美",
                authorAvatar = "https://via.placeholder.com/100x100?text=User1",
                content = "第二次来上海娇颜颂找王医生了，这次是填充苹果肌。王医生建议用乔雅登填充，效果可以维持一年左右。注射过程很顺利，做完即刻效果就很明显，脸部饱满了许多！术后前三天有些肿胀，一周后完全恢复自然。现在每天照镜子都很开心。",
                images = "https://via.placeholder.com/400x300?text=DiaryPhoto1",
                beforeImages = "https://via.placeholder.com/400x300?text=BeforeFiller1,https://via.placeholder.com/400x300?text=BeforeFiller2",
                afterImages = "https://via.placeholder.com/400x300?text=AfterFiller1,https://via.placeholder.com/400x300?text=AfterFiller2",
                likeCount = 41,
                commentCount = 12,
                rating = 5,
                tags = "玻尿酸,苹果肌,填充,上海",
                publishDate = LocalDate.of(2026, 7, 12),
                status = "published",
                doctorId = SeedIds.DOC_ID_1,
                projectId = SeedIds.PROJ_ID_1,
                institutionId = SeedIds.INST_ID_1,
                institutionProjectId = SeedIds.IP_ID_1,
                orderId = SeedIds.ORDER_ID_3
            ),
            DiaryEntity(
                id = UUID.randomUUID().toString(),
                title = "皮秒祛斑真实经历分享",
                userId = SeedIds.USER_ID_2,
                authorName = "小红",
                authorAvatar = "https://via.placeholder.com/100x100?text=User2",
                content = "在北京美丽时光找张医生做了三次皮秒祛斑，效果真的太惊喜了！我脸上的雀斑从高中就开始长，试过各种护肤品都没用。张医生很专业，根据我的皮肤状况定制了治疗方案。三次治疗后斑点淡了百分之八十，肤色均匀了很多。术后一定要做好防晒，张医生反复强调这点。",
                images = "https://via.placeholder.com/400x300?text=DiaryPhoto2,https://via.placeholder.com/400x300?text=DiaryPhoto3",
                beforeImages = "https://via.placeholder.com/400x300?text=BeforeSkin1,https://via.placeholder.com/400x300?text=BeforeSkin2",
                afterImages = "https://via.placeholder.com/400x300?text=AfterSkin1,https://via.placeholder.com/400x300?text=AfterSkin2",
                likeCount = 36,
                commentCount = 15,
                rating = 5,
                tags = "皮秒,祛斑,北京,真实经历",
                publishDate = LocalDate.of(2026, 7, 8),
                status = "published",
                doctorId = SeedIds.DOC_ID_3,
                projectId = SeedIds.PROJ_ID_4,
                institutionId = SeedIds.INST_ID_2,
                institutionProjectId = SeedIds.IP_ID_5,
                orderId = ""
            ),
            DiaryEntity(
                id = UUID.randomUUID().toString(),
                title = "双眼皮术后一个月记录",
                userId = SeedIds.USER_ID_3,
                authorName = "小丽",
                authorAvatar = "https://via.placeholder.com/100x100?text=User3",
                content = "在上海娇颜颂找李医生做了全切双眼皮，现在一个月了来记录一下恢复过程。李医生术前帮我设计了很适合我脸型的双眼皮宽度，手术过程大概一个小时。术后前三天肿得比较厉害，一周拆线后就好多了。现在一个月，双眼皮线条已经很自然了，朋友们都说好看！",
                images = "https://via.placeholder.com/400x300?text=DiaryPhoto4,https://via.placeholder.com/400x300?text=DiaryPhoto5",
                beforeImages = "https://via.placeholder.com/400x300?text=BeforeEye1,https://via.placeholder.com/400x300?text=BeforeEye2",
                afterImages = "https://via.placeholder.com/400x300?text=AfterEye1,https://via.placeholder.com/400x300?text=AfterEye2",
                likeCount = 29,
                commentCount = 8,
                rating = 5,
                tags = "双眼皮,眼部整形,上海,恢复记录",
                publishDate = LocalDate.of(2026, 7, 15),
                status = "published",
                doctorId = SeedIds.DOC_ID_2,
                projectId = SeedIds.PROJ_ID_3,
                institutionId = SeedIds.INST_ID_1,
                institutionProjectId = SeedIds.IP_ID_4,
                orderId = ""
            ),
            DiaryEntity(
                id = UUID.randomUUID().toString(),
                title = "热玛吉抗衰真实体验",
                userId = SeedIds.USER_ID_2,
                authorName = "小红",
                authorAvatar = "https://via.placeholder.com/100x100?text=User2",
                content = "在广州悦颜找陈医生做了第五代热玛吉，分享一下真实感受。陈医生很专业，术前详细评估了我的皮肤状态。治疗过程大概一个小时，有温热感，疼痛可以忍受。做完即刻就有紧致感，医生说2-6个月效果会更好。现在两个月了，法令纹淡了不少，脸部轮廓也更清晰了。",
                images = "https://via.placeholder.com/400x300?text=DiaryPhoto6",
                beforeImages = "https://via.placeholder.com/400x300?text=BeforeThermage1,https://via.placeholder.com/400x300?text=BeforeThermage2",
                afterImages = "https://via.placeholder.com/400x300?text=AfterThermage1,https://via.placeholder.com/400x300?text=AfterThermage2",
                likeCount = 33,
                commentCount = 10,
                rating = 4,
                tags = "热玛吉,抗衰,紧致,广州",
                publishDate = LocalDate.of(2026, 7, 18),
                status = "published",
                doctorId = SeedIds.DOC_ID_4,
                projectId = SeedIds.PROJ_ID_5,
                institutionId = SeedIds.INST_ID_4,
                institutionProjectId = SeedIds.IP_ID_6,
                orderId = ""
            ),
            DiaryEntity(
                id = UUID.randomUUID().toString(),
                title = "水光针三次疗程记录",
                userId = SeedIds.USER_ID_1,
                authorName = "小美",
                authorAvatar = "https://via.placeholder.com/100x100?text=User1",
                content = "在上海娇颜颂找王医生做了三次水光针疗程来打卡。我是干性皮肤，毛孔也比较粗大。王医生根据我的皮肤状况调配了玻尿酸+维C的方案。三次做完皮肤真的水润了很多，毛孔也细腻了。每次注射后两三天就能感觉到皮肤的变化，上妆也更服帖了。打算三个月后再做一次维护。",
                images = "https://via.placeholder.com/400x300?text=DiaryPhoto7,https://via.placeholder.com/400x300?text=DiaryPhoto8",
                beforeImages = "https://via.placeholder.com/400x300?text=BeforeAqua1,https://via.placeholder.com/400x300?text=BeforeAqua2",
                afterImages = "https://via.placeholder.com/400x300?text=AfterAqua1,https://via.placeholder.com/400x300?text=AfterAqua2",
                likeCount = 25,
                commentCount = 7,
                rating = 4,
                tags = "水光针,补水,上海,疗程记录",
                publishDate = LocalDate.of(2026, 7, 20),
                status = "published",
                doctorId = SeedIds.DOC_ID_1,
                projectId = SeedIds.PROJ_ID_2,
                institutionId = SeedIds.INST_ID_1,
                institutionProjectId = SeedIds.IP_ID_3,
                orderId = ""
            )
        ))
    }
}
