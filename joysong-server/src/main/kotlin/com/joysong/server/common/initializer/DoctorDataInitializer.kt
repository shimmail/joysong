package com.joysong.server.common.initializer

import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.repository.DoctorInstitutionRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Component
@ConditionalOnProperty(prefix = "seed.demo", name = ["enabled"], havingValue = "true")
@Order(3)
class DoctorDataInitializer(
    private val doctorRepository: DoctorRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val doctorInstitutionRepository: DoctorInstitutionRepository,
    private val doctorInstitutionService: DoctorInstitutionService
) : CommandLineRunner {

    @Transactional
    override fun run(args: Array<String>) {
        // ============================================================
        // 医生（6 位）
        // ============================================================
        if (doctorRepository.count() == 0L) {
            doctorRepository.saveAll(listOf(
            // --- 上海娇颜颂医美中心 ---
            DoctorEntity(
                id = SeedIds.DOC_ID_1,
                name = "王医生",
                title = "主任医师",
                bio = "从事医美行业 15 年，擅长玻尿酸填充、水光针等注射美容项目。",
                avatar = "https://via.placeholder.com/200x200?text=DoctorWang",
                institutionId = SeedIds.INST_ID_1,
                institutionName = "上海娇颜颂医美中心",
                rating = BigDecimal("4.9"),
                reviewCount = 86,
                specialties = "玻尿酸,水光针,注射美容",
                isVerified = true,
                consultationCount = 320,
                credentials = "上海交通大学医学院毕业，从事注射美容 15 年，累计完成注射类手术超 5000 例，曾于韩国首尔大学医院进修学习。",
                credentialImages = "",
                caseCount = 5200,
                certificationTags = "姣兰姣妤官方认证注射医师,Allergan美学认证医师"
            ),
            DoctorEntity(
                id = SeedIds.DOC_ID_2,
                name = "李医生",
                title = "副主任医师",
                bio = "专注眼部整形与面部年轻化，双眼皮成形术经验丰富。",
                avatar = "https://via.placeholder.com/200x200?text=DoctorLi",
                institutionId = SeedIds.INST_ID_1,
                institutionName = "上海娇颜颂医美中心",
                rating = BigDecimal("4.7"),
                reviewCount = 54,
                specialties = "双眼皮,眼部整形,面部年轻化",
                isVerified = true,
                consultationCount = 180,
                credentials = "复旦大学附属华山医院整形外科硕士，专注眼部整形手术 10 年，累计完成眼整形手术超 2000 例。",
                credentialImages = "",
                caseCount = 2100,
                certificationTags = "中国医师协会美容分会会员"
            ),
            // --- 北京美丽时光医疗美容 ---
            DoctorEntity(
                id = SeedIds.DOC_ID_3,
                name = "张医生",
                title = "主治医师",
                bio = "擅长激光美肤与色素性疾病治疗，皮秒祛斑经验丰富。",
                avatar = "https://via.placeholder.com/200x200?text=DoctorZhang",
                institutionId = SeedIds.INST_ID_2,
                institutionName = "北京美丽时光医疗美容",
                rating = BigDecimal("4.6"),
                reviewCount = 43,
                specialties = "皮秒,激光祛斑,光子嫩肤",
                isVerified = true,
                consultationCount = 210,
                credentials = "北京协和医院皮肤科硕士，专注激光美肤 8 年，在色素性疾病治疗领域发表论文多篇。",
                credentialImages = "",
                caseCount = 3100,
                certificationTags = "赛诺秀官方认证操作医师"
            ),
            // --- 广州悦颜整形医院 ---
            DoctorEntity(
                id = SeedIds.DOC_ID_4,
                name = "陈医生",
                title = "主任医师",
                bio = "抗衰紧致领域专家，精通热玛吉等光电项目。",
                avatar = "https://via.placeholder.com/200x200?text=DoctorChen",
                institutionId = SeedIds.INST_ID_4,
                institutionName = "广州悦颜整形医院",
                rating = BigDecimal("4.5"),
                reviewCount = 38,
                specialties = "热玛吉,抗衰紧致,光电美容",
                isVerified = true,
                consultationCount = 150,
                credentials = "中山大学附属第一医院整形外科硕士，从事光电美容 12 年，累计完成热玛吉等抗衰项目超 1500 例。",
                credentialImages = "",
                caseCount = 1600,
                certificationTags = "热玛吉官方认证操作师"
            ),
            // --- 深圳美莱医疗美容医院 ---
            DoctorEntity(
                id = SeedIds.DOC_ID_5,
                name = "刘医生",
                title = "副主任医师",
                bio = "注射美容资深专家，精通玻尿酸填充与面部轮廓塑形。",
                avatar = "https://via.placeholder.com/200x200?text=DoctorLiu",
                institutionId = SeedIds.INST_ID_3,
                institutionName = "深圳美莱医疗美容医院",
                rating = BigDecimal("4.7"),
                reviewCount = 61,
                specialties = "玻尿酸,注射美容,面部轮廓",
                isVerified = true,
                consultationCount = 280,
                credentials = "华中科技大学同济医学院整形外科博士，注射美容领域资深专家，完成注射类项目超 3000 例。",
                credentialImages = "",
                caseCount = 3200,
                certificationTags = "中国医师协会美容分会会员"
            ),
            DoctorEntity(
                id = SeedIds.DOC_ID_6,
                name = "孙医生",
                title = "主任医师",
                bio = "鼻整形修复专家，从事鼻部整形手术超 3000 例，技术精湛。",
                avatar = "https://via.placeholder.com/200x200?text=DoctorSun",
                institutionId = SeedIds.INST_ID_3,
                institutionName = "深圳美莱医疗美容医院",
                rating = BigDecimal("4.8"),
                reviewCount = 78,
                specialties = "鼻整形,隆鼻,鼻修复",
                isVerified = true,
                consultationCount = 350,
                credentials = "南方医科大学整形外科教授，鼻整形修复领域权威，累计完成鼻部整形手术超 3000 例。",
                credentialImages = "",
                caseCount = 3100,
                certificationTags = "中华医学会整形外科分会鼻整形学组委员"
            )
        ))
        }

        // Flyway 在初始化器前执行；全新数据库需要在此为种子医生建立关联记录。
        if (doctorInstitutionRepository.count() == 0L) {
            doctorRepository.findAll()
                .filter { it.institutionId.isNotBlank() }
                .forEach { doctor -> doctorInstitutionService.sync(doctor.id, listOf(doctor.institutionId), doctor.institutionId) }
            // 示例：王医生可在上海娇颜颂及深圳美莱出诊；具体可预约项目仍由 doctor_projects 决定。
            doctorInstitutionService.sync(SeedIds.DOC_ID_1, listOf(SeedIds.INST_ID_1, SeedIds.INST_ID_3), SeedIds.INST_ID_1)
        }

        // ============================================================
        // 医生-项目关联（doctor_projects，7 条）
        // ============================================================
        if (doctorProjectRepository.count() == 0L) {
            doctorProjectRepository.saveAll(listOf(
            // 王医生 - 玻尿酸填充、水光针
            DoctorProjectEntity(doctorId = SeedIds.DOC_ID_1, projectId = SeedIds.PROJ_ID_1, institutionProjectId = SeedIds.IP_ID_1, price = BigDecimal("2999.00")),
            DoctorProjectEntity(doctorId = SeedIds.DOC_ID_1, projectId = SeedIds.PROJ_ID_2, institutionProjectId = SeedIds.IP_ID_3, price = BigDecimal("1299.00")),
            // 李医生 - 双眼皮成形
            DoctorProjectEntity(doctorId = SeedIds.DOC_ID_2, projectId = SeedIds.PROJ_ID_3, institutionProjectId = SeedIds.IP_ID_4, price = BigDecimal("4999.00")),
            // 张医生 - 皮秒祛斑
            DoctorProjectEntity(doctorId = SeedIds.DOC_ID_3, projectId = SeedIds.PROJ_ID_4, institutionProjectId = SeedIds.IP_ID_5, price = BigDecimal("1999.00")),
            // 陈医生 - 热玛吉抗衰
            DoctorProjectEntity(doctorId = SeedIds.DOC_ID_4, projectId = SeedIds.PROJ_ID_5, institutionProjectId = SeedIds.IP_ID_6, price = BigDecimal("7999.00")),
            // 刘医生 - 玻尿酸填充
            DoctorProjectEntity(doctorId = SeedIds.DOC_ID_5, projectId = SeedIds.PROJ_ID_1, institutionProjectId = SeedIds.IP_ID_2, price = BigDecimal("2899.00")),
            // 孙医生 - 鼻综合整形
            DoctorProjectEntity(doctorId = SeedIds.DOC_ID_6, projectId = SeedIds.PROJ_ID_6, institutionProjectId = SeedIds.IP_ID_8, price = BigDecimal("14999.00"))
        ))
        }
    }
}
