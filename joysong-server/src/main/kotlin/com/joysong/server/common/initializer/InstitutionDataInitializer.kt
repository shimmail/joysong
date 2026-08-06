package com.joysong.server.common.initializer

import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

@Component
@ConditionalOnProperty(prefix = "seed.demo", name = ["enabled"], havingValue = "true")
@Order(2)
class InstitutionDataInitializer(
    private val institutionRepository: InstitutionRepository,
    private val institutionProjectRepository: InstitutionProjectRepository
) : CommandLineRunner {

    @Transactional
    override fun run(args: Array<String>) {
        // ============================================================
        // 机构（4 家）
        // ============================================================
        if (institutionRepository.count() == 0L) {
            institutionRepository.saveAll(listOf(
            InstitutionEntity(
                id = SeedIds.INST_ID_1,
                name = "上海娇颜颂医美中心",
                address = "上海市静安区南京西路 1266 号",
                city = "上海",
                description = "专注注射美容与皮肤管理，拥有多位资深医师。",
                rating = BigDecimal("4.8"),
                reviewCount = 128,
                isVerified = true,
                doctorCount = 2,
                projectCount = 4,
                consultationCount = 520,
                credentials = "国家卫健委认证的正规医疗美容机构，拥有三位主任医师级别的资深专家团队，配备国际一流的注射美容设备与无菌操作环境。",
                specialties = "注射美容,皮肤管理,眼部整形,抗衰紧致",
                credentialImages = "https://via.placeholder.com/300x200?text=BusinessLicense,https://via.placeholder.com/300x200?text=MedicalLicense",
                coverImage = "https://via.placeholder.com/400x300?text=InstShanghai",
                images = "https://via.placeholder.com/400x300?text=Env1,https://via.placeholder.com/400x300?text=Env2",
                establishedYear = 2015,
                certificationTime = LocalDate.of(2015, 6, 1),
                userCount = 3200,
                tags = "注射美容,热玛吉,双眼皮,水光针",
                contactPhone = "021-62881266",
                businessHours = "周一至周日 09:00-21:00",
                caseCount = 8600
            ),
            InstitutionEntity(
                id = SeedIds.INST_ID_2,
                name = "北京美丽时光医疗美容",
                address = "北京市朝阳区建国路 88 号",
                city = "北京",
                description = "综合医美机构，以激光美肤与色素性疾病治疗为核心优势。",
                rating = BigDecimal("4.6"),
                reviewCount = 96,
                isVerified = true,
                doctorCount = 1,
                projectCount = 1,
                consultationCount = 180,
                credentials = "北京市卫生局批准的专业医疗美容机构，以激光美肤与色素性疾病治疗为核心优势。",
                specialties = "激光美肤,皮秒祛斑",
                credentialImages = "https://via.placeholder.com/300x200?text=BusinessLicense,https://via.placeholder.com/300x200?text=MedicalLicense",
                coverImage = "https://via.placeholder.com/400x300?text=InstBeijing",
                images = "https://via.placeholder.com/400x300?text=Env1,https://via.placeholder.com/400x300?text=Env2",
                establishedYear = 2018,
                certificationTime = LocalDate.of(2018, 3, 1),
                userCount = 1500,
                tags = "皮秒祛斑,激光美肤",
                contactPhone = "010-85881088",
                businessHours = "周一至周六 09:30-20:00",
                caseCount = 4200
            ),
            InstitutionEntity(
                id = SeedIds.INST_ID_3,
                name = "深圳美莱医疗美容医院",
                address = "深圳市福田区深南中路 2038 号",
                city = "深圳",
                description = "大型综合医美连锁品牌，涵盖整形美容、注射美容等全品类服务。",
                rating = BigDecimal("4.7"),
                reviewCount = 156,
                isVerified = true,
                doctorCount = 2,
                projectCount = 2,
                consultationCount = 680,
                credentials = "全国连锁医美品牌，拥有整形外科与美容皮肤科双资质，专家团队实力雄厚，设备先进。",
                specialties = "脂肪填充,鼻整形,注射美容",
                credentialImages = "https://via.placeholder.com/300x200?text=BusinessLicense,https://via.placeholder.com/300x200?text=MedicalLicense",
                coverImage = "https://via.placeholder.com/400x300?text=InstShenzhen",
                images = "https://via.placeholder.com/400x300?text=Env1,https://via.placeholder.com/400x300?text=Env2",
                establishedYear = 2012,
                certificationTime = LocalDate.of(2012, 5, 1),
                userCount = 5800,
                tags = "鼻综合,脂肪填充,注射美容",
                contactPhone = "0755-83881088",
                businessHours = "周一至周日 09:00-21:30",
                caseCount = 15200
            ),
            InstitutionEntity(
                id = SeedIds.INST_ID_4,
                name = "广州悦颜整形医院",
                address = "广州市天河区珠江新城华夏路 10 号",
                city = "广州",
                description = "以整形手术与微创美容为特色，提供热玛吉等抗衰项目。",
                rating = BigDecimal("4.5"),
                reviewCount = 72,
                isVerified = true,
                doctorCount = 1,
                projectCount = 1,
                consultationCount = 95,
                credentials = "广东省卫生厅批准的整形专科医院，以整形外科与微创美容为特色。",
                specialties = "热玛吉抗衰,整形手术,微创美容",
                credentialImages = "https://via.placeholder.com/300x200?text=BusinessLicense,https://via.placeholder.com/300x200?text=MedicalLicense",
                coverImage = "https://via.placeholder.com/400x300?text=InstGuangzhou",
                images = "https://via.placeholder.com/400x300?text=Env1,https://via.placeholder.com/400x300?text=Env2",
                establishedYear = 2020,
                certificationTime = LocalDate.of(2020, 9, 1),
                userCount = 800,
                tags = "热玛吉,整形手术,微创美容",
                contactPhone = "020-38881010",
                businessHours = "周一至周日 10:00-20:00",
                caseCount = 2100
            )
        ))
        }

        // ============================================================
        // 机构-项目关联（institution_projects，8 条）
        // ============================================================
        if (institutionProjectRepository.count() == 0L) {
            institutionProjectRepository.saveAll(listOf(
            InstitutionProjectEntity(
                id = SeedIds.IP_ID_1,
                institutionId = SeedIds.INST_ID_1,
                projectId = SeedIds.PROJ_ID_1,
                name = "焕颜定制体验项目",
                slogan = "机构专属方案，其他详情继承公共项目",
                price = BigDecimal("2999.00"),
                originalPrice = BigDecimal("3999.00")
            ),
            InstitutionProjectEntity(id = SeedIds.IP_ID_3, institutionId = SeedIds.INST_ID_1, projectId = SeedIds.PROJ_ID_2, price = BigDecimal("1299.00"), originalPrice = BigDecimal("1999.00")),
            InstitutionProjectEntity(id = SeedIds.IP_ID_4, institutionId = SeedIds.INST_ID_1, projectId = SeedIds.PROJ_ID_3, price = BigDecimal("4999.00"), originalPrice = BigDecimal("6999.00")),
            InstitutionProjectEntity(id = SeedIds.IP_ID_5, institutionId = SeedIds.INST_ID_2, projectId = SeedIds.PROJ_ID_4, price = BigDecimal("1999.00"), originalPrice = BigDecimal("2999.00")),
            InstitutionProjectEntity(id = SeedIds.IP_ID_6, institutionId = SeedIds.INST_ID_4, projectId = SeedIds.PROJ_ID_5, price = BigDecimal("7999.00"), originalPrice = BigDecimal("9999.00")),
            InstitutionProjectEntity(id = SeedIds.IP_ID_7, institutionId = SeedIds.INST_ID_1, projectId = SeedIds.PROJ_ID_5, price = BigDecimal("8999.00"), originalPrice = BigDecimal("11999.00")),
            InstitutionProjectEntity(id = SeedIds.IP_ID_2, institutionId = SeedIds.INST_ID_3, projectId = SeedIds.PROJ_ID_1, price = BigDecimal("2899.00"), originalPrice = BigDecimal("3899.00")),
            InstitutionProjectEntity(id = SeedIds.IP_ID_8, institutionId = SeedIds.INST_ID_3, projectId = SeedIds.PROJ_ID_6, price = BigDecimal("14999.00"), originalPrice = BigDecimal("19999.00"))
        ))
        }
    }
}
