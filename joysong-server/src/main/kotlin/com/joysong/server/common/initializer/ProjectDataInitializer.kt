package com.joysong.server.common.initializer

import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Component
@ConditionalOnProperty(prefix = "seed.demo", name = ["enabled"], havingValue = "true")
@Order(1)
class ProjectDataInitializer(
    private val projectRepository: ProjectRepository
) : CommandLineRunner {

    @Transactional
    override fun run(args: Array<String>) {
        if (projectRepository.count() > 0L) return

        // ============================================================
        // 项目（6 个，覆盖 4 个分类）
        // ============================================================
        projectRepository.saveAll(listOf(
            // --- 注射美容 ---
            ProjectEntity(
                id = SeedIds.PROJ_ID_1,
                name = "玻尿酸填充",
                category = "注射美容",
                description = "进口玻尿酸，塑形自然持久。",
                rating = BigDecimal("4.8"),
                reviewCount = 62,
                tags = "玻尿酸,填充,微整",
                coverImage = "https://via.placeholder.com/400x300?text=HyaluronicAcid",
                images = "https://via.placeholder.com/400x300?text=HyaluronicAcid1,https://via.placeholder.com/400x300?text=HyaluronicAcid2",
                referencePrice = BigDecimal("3000.00"),
                slogan = "精准填充，自然塑形，午休式美容",
                detailContent = "<h1>作用原理</h1><p>玻尿酸填充是一种非手术注射美容方式，通过将透明质酸注入皮肤真皮层，达到填充凹陷、抚平皱纹、塑形提升的效果。常用部位包括鼻唇沟、泪沟、下巴、苹果肌等。</p><h1>适合人群</h1><ul><li>面部凹陷、法令纹明显者</li><li>苹果肌下垂、下巴后缩者</li><li>希望快速改善面部轮廓者</li></ul><h1>禁忌人群</h1><ul><li>孕妇及哺乳期女性</li><li>过敏体质者</li><li>注射部位有炎症者</li></ul><h1>恢复周期</h1><p>注射后1-3天轻微肿胀，1周内完全恢复自然。效果通常可维持6-12个月。</p><h1>项目亮点</h1><p>进口品牌玻尿酸，注射精准自然，当天见效，午休式美容无需长时间恢复。</p><h1>潜在风险及副作用</h1><p>玻尿酸填充总体安全性较高，但仍需注意以下风险：</p><ul><li>注射部位可能出现短暂红肿、淤青，通常3-7天自行消退</li><li>罕见但严重的血管栓塞风险，需由经验丰富的医师操作</li><li>效果非永久性，通常6-12个月后需补充注射</li></ul>",
                salesCount = 1280,
                categoryTags = "注射美容,填充,玻尿酸"
            ),
            ProjectEntity(
                id = SeedIds.PROJ_ID_2,
                name = "水光针",
                category = "注射美容",
                description = "深层补水，改善肤质。",
                rating = BigDecimal("4.6"),
                reviewCount = 45,
                tags = "水光针,补水,嫩肤",
                coverImage = "https://via.placeholder.com/400x300?text=AquaInjection",
                images = "https://via.placeholder.com/400x300?text=AquaInjection1,https://via.placeholder.com/400x300?text=AquaInjection2",
                referencePrice = BigDecimal("1500.00"),
                slogan = "深层补水，焕活肌肤，打造水光肌",
                detailContent = "<h1>作用原理</h1><p>水光针通过微针将透明质酸、维生素等营养成分直接注入皮肤真皮层，实现深层补水、改善肤质的效果。</p><h1>适合人群</h1><ul><li>皮肤干燥、缺水者</li><li>肤色暗沉、毛孔粗大者</li><li>希望改善皮肤质感者</li></ul><h1>禁忌人群</h1><ul><li>孕妇及哺乳期女性</li><li>皮肤有炎症或感染者</li><li>凝血功能异常者</li></ul><h1>恢复周期</h1><p>注射后1-2天有轻微针眼，3-5天完全恢复。建议每月1次，连续3次为一个疗程。</p><h1>项目亮点</h1><p>采用进口水光仪器，精准控制注射深度和剂量，术后即可上妆，不影响日常生活。</p><h1>潜在风险及副作用</h1><p>水光针安全性较高，常见副作用包括：</p><ul><li>注射部位轻微红肿、出血点，通常1-3天消退</li><li>个别敏感肌肤可能出现过敏反应</li><li>效果非永久性，需定期维护</li></ul>",
                salesCount = 860,
                categoryTags = "注射美容,补水,水光针"
            ),
            // --- 眼部整形 ---
            ProjectEntity(
                id = SeedIds.PROJ_ID_3,
                name = "双眼皮成形",
                category = "眼部整形",
                description = "自然双眼皮，放大双眼。",
                rating = BigDecimal("4.7"),
                reviewCount = 58,
                tags = "双眼皮,眼部,整形",
                coverImage = "https://via.placeholder.com/400x300?text=DoubleEyelid",
                images = "https://via.placeholder.com/400x300?text=DoubleEyelid1,https://via.placeholder.com/400x300?text=DoubleEyelid2",
                referencePrice = BigDecimal("5000.00"),
                slogan = "精准设计，自然灵动，让双眼会说话",
                detailContent = "<h1>作用原理</h1><p>双眼皮成形术通过手术方式在上眼睑形成自然褶皱，包括埋线法、韩式三点和全切法三种主流术式，根据个人眼部条件选择最适合的方案。</p><h1>适合人群</h1><ul><li>单眼皮或内双者</li><li>双眼皮不对称者</li><li>上眼睑皮肤松弛者</li></ul><h1>禁忌人群</h1><ul><li>眼部有炎症或感染者</li><li>凝血功能异常者</li><li>严重干眼症患者</li></ul><h1>恢复周期</h1><p>埋线法3-7天恢复，全切法7-15天消肿，1-3个月完全自然。术后需冰敷、避免揉眼。</p><h1>项目亮点</h1><p>术前精准测量设计，术中精细缝合，术后双眼皮线条流畅自然，闭眼无痕。</p><h1>潜在风险及副作用</h1><p>双眼皮手术为常规整形项目，风险较低，但仍需注意：</p><ul><li>术后短期肿胀、淤青属正常现象</li><li>极少数可能出现双侧不对称，需二次修复</li><li>全切法可能留下细微疤痕，但通常不明显</li></ul>",
                salesCount = 920,
                categoryTags = "眼部整形,双眼皮"
            ),
            // --- 皮肤管理 ---
            ProjectEntity(
                id = SeedIds.PROJ_ID_4,
                name = "皮秒祛斑",
                category = "皮肤管理",
                description = "精准祛斑，还原净白肌肤。",
                rating = BigDecimal("4.7"),
                reviewCount = 51,
                tags = "皮秒,祛斑,美白",
                coverImage = "https://via.placeholder.com/400x300?text=PicoLaser",
                images = "https://via.placeholder.com/400x300?text=PicoLaser1,https://via.placeholder.com/400x300?text=PicoLaser2",
                referencePrice = BigDecimal("2500.00"),
                slogan = "皮秒级精准祛斑，还原无瑕净白肌",
                detailContent = "<h1>作用原理</h1><p>皮秒激光以万亿分之一秒的脉宽将色素颗粒击碎为微小粉尘状，由人体代谢自然排出，达到祛斑、美白、均匀肤色的效果。</p><h1>适合人群</h1><ul><li>雀斑、晒斑、老年斑患者</li><li>黄褐斑、咖啡斑等色素沉着者</li><li>肤色不均、暗沉者</li></ul><h1>禁忌人群</h1><ul><li>孕妇及哺乳期女性</li><li>光敏性皮肤或正在服用光敏药物者</li><li>治疗部位有开放伤口者</li></ul><h1>恢复周期</h1><p>治疗后24小时内有轻微红肿，3-7天结痂脱落，1个月后可见明显效果。通常需2-3次治疗。</p><h1>项目亮点</h1><p>采用赛诺秀PicoSure设备，皮秒级脉宽比传统激光更精准，热损伤更小，恢复更快。</p><h1>潜在风险及副作用</h1><p>皮秒祛斑安全性高，常见副作用包括：</p><ul><li>治疗后短暂红肿、轻微结痂，属正常反应</li><li>深色斑可能需要多次治疗才能达到理想效果</li><li>术后需严格防晒，否则可能出现色素沉着反弹</li></ul>",
                salesCount = 750,
                categoryTags = "皮肤管理,祛斑,皮秒"
            ),
            // --- 抗衰紧致 ---
            ProjectEntity(
                id = SeedIds.PROJ_ID_5,
                name = "热玛吉抗衰",
                category = "抗衰紧致",
                description = "非侵入式紧致提升，逆转肌龄。",
                rating = BigDecimal("4.8"),
                reviewCount = 67,
                tags = "热玛吉,抗衰,紧致",
                coverImage = "https://via.placeholder.com/400x300?text=Thermage",
                images = "https://via.placeholder.com/400x300?text=Thermage1,https://via.placeholder.com/400x300?text=Thermage2",
                referencePrice = BigDecimal("8000.00"),
                slogan = "一次治疗，紧致提升，逆转时光",
                detailContent = "<h1>作用原理</h1><p>热玛吉利用射频能量加热皮肤深层胶原纤维，刺激胶原蛋白收缩与新生，达到紧致提升、改善皱纹的效果。治疗深度可达4.3mm，覆盖真皮层和皮下组织。</p><h1>适合人群</h1><ul><li>面部皮肤松弛、下垂者</li><li>法令纹、颈纹明显者</li><li>希望非手术方式抗衰老者</li></ul><h1>禁忌人群</h1><ul><li>孕妇及哺乳期女性</li><li>体内有金属植入物者（如心脏起搏器）</li><li>治疗部位有开放性伤口或皮肤病者</li></ul><h1>恢复周期</h1><p>治疗后即刻可正常生活，2-6个月效果逐步显现并持续优化。一次治疗效果可维持1-2年。</p><h1>项目亮点</h1><p>第五代热玛吉FLX技术，智能优化能量输出，治疗更精准舒适。非侵入式治疗，无需恢复期，一次治疗即可见效。</p><h1>潜在风险及副作用</h1><p>热玛吉总体安全性高，但需注意：</p><ul><li>治疗后可能出现短暂红肿、轻微疼痛，通常数小时内消退</li><li>极少数可能出现水泡或灼伤，需由认证医师操作</li><li>效果因人而异，部分人可能需要辅助治疗</li></ul>",
                salesCount = 620,
                categoryTags = "抗衰紧致,热玛吉"
            ),
            // --- 鼻部整形 ---
            ProjectEntity(
                id = SeedIds.PROJ_ID_6,
                name = "鼻综合整形",
                category = "鼻部整形",
                description = "全方位鼻部塑形，打造立体五官。",
                rating = BigDecimal("4.8"),
                reviewCount = 43,
                tags = "鼻综合,隆鼻,鼻整形",
                coverImage = "https://via.placeholder.com/400x300?text=Rhinoplasty",
                images = "https://via.placeholder.com/400x300?text=Rhinoplasty1,https://via.placeholder.com/400x300?text=Rhinoplasty2",
                referencePrice = BigDecimal("15000.00"),
                slogan = "精雕细琢，立体鼻型，侧颜杀",
                detailContent = "<h1>作用原理</h1><p>鼻综合整形是对鼻部进行全方位改善的手术项目，包括鼻梁抬高、鼻尖塑形、鼻翼缩小等多项调整，采用自体软骨或假体材料，实现自然立体的鼻部形态。</p><h1>适合人群</h1><ul><li>鼻梁低平、鼻头圆钝者</li><li>鼻翼宽大、鼻孔外露者</li><li>鼻部形态不对称者</li></ul><h1>禁忌人群</h1><ul><li>鼻部有炎症或感染者</li><li>凝血功能异常者</li><li>未满18岁者</li></ul><h1>恢复周期</h1><p>术后7天拆线，2周消肿约70%，1-3个月完全恢复自然。术后需避免碰撞鼻部。</p><h1>项目亮点</h1><p>术前3D模拟设计，精准预估术后效果。采用自体耳软骨+假体复合方案，形态自然不惧揉捏。</p><h1>潜在风险及副作用</h1><p>鼻综合整形为较大手术项目，需注意以下风险：</p><ul><li>术后肿胀期较长，需耐心等待恢复</li><li>假体可能出现排异反应，概率较低</li><li>术后形态不满意时，需等待6个月以上方可修复</li></ul>",
                salesCount = 380,
                categoryTags = "鼻部整形,鼻综合"
            )
        ))
    }
}
