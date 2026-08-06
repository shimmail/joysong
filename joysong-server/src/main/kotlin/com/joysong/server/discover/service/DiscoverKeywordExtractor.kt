package com.joysong.server.discover.service

import org.springframework.stereotype.Component

@Component
class DiscoverKeywordExtractor {
    fun extract(query: String, hints: Collection<String> = emptyList()): Set<String> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptySet()

        val keywords = linkedSetOf<String>()
        normalized.split(Regex("[\\s,，、;；:：?？!！/()（）]+"))
            .map(::clean)
            .filterTo(keywords) { it.length >= 2 }

        val cleanedSentence = clean(normalized)
        if (cleanedSentence.length >= 2) keywords += cleanedSentence

        domainTerms.filterTo(keywords) { normalized.contains(it) }
        hints.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 && normalized.contains(it) }
            .forEach(keywords::add)
        return keywords
    }

    private fun clean(value: String): String {
        var result = value
        removablePhrases.forEach { result = result.replace(it, " ") }
        return result.trim().replace(Regex("\\s+"), " ")
    }

    private companion object {
        val removablePhrases = listOf(
            "请问", "麻烦", "帮我", "我想", "我想要", "想了解", "想咨询", "查一下", "找一下",
            "有哪些", "有什么", "有没有", "哪一些", "哪个好", "哪家好", "怎么样", "如何", "推荐",
            "擅长", "可以做", "能做", "的医生", "的机构", "的医院", "医生", "医师", "机构", "医院", "诊所", "项目",
            "please", "could you", "can you", "i want", "i need", "show me", "find me", "looking for",
            "which", "what", "recommend", "doctor", "clinic", "hospital", "treatment", "procedure"
        )
        val domainTerms = setOf(
            "双眼皮", "重睑", "隆鼻", "鼻综合", "隆胸", "吸脂", "脂肪填充", "玻尿酸", "肉毒", "水光",
            "光子嫩肤", "光子", "热玛吉", "超声炮", "超声刀", "射频", "激光", "皮秒", "点阵", "线雕", "植发", "脱毛",
            "暗沉", "毛孔", "粗糙", "祛斑", "色沉", "痘印", "痤疮", "皱纹", "细纹", "松弛", "下垂", "凹陷", "抗衰",
            "double eyelid", "blepharoplasty", "rhinoplasty", "liposuction", "filler", "botox", "ipl", "aopt", "dpl",
            "thermage", "ultherapy", "laser", "acne", "wrinkle", "pigmentation", "sagging", "skin texture"
        )
    }
}
