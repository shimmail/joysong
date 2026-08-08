package com.joysong.server.discover.service

import com.joysong.server.discover.dto.InstitutionProjectItemResponse
import com.joysong.server.discover.dto.ProjectWithInstitutionsResponse
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.stereotype.Service

@Service
class DiscoverSearchService(
    private val projectRepository: ProjectRepository,
    private val institutionRepository: InstitutionRepository,
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val doctorRepository: DoctorRepository,
    private val keywordExtractor: DiscoverKeywordExtractor,
    private val doctorInstitutionService: DoctorInstitutionService,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver
) {
    fun search(request: DiscoverSearchRequest): DiscoverSearchResult {
        val normalized = request.query.trim().lowercase()
        val terms = keywordExtractor.extract(request.query, request.cities)
            .filter { it !in searchStopWords }

        val projects = if (DiscoverSearchScope.PROJECT in request.scopes) {
            searchProjectResults(
                categories = request.categories,
                cities = request.cities,
                query = request.query,
                tags = request.tags,
                fallbackWhenNoMatch = request.fallbackWhenNoMatch
            ).take(request.limit)
        } else emptyList()

        val institutions = if (DiscoverSearchScope.INSTITUTION in request.scopes) {
            institutionRepository.findAll().filter { institution ->
                val requestedCityMatches = request.cities.any { it.equals(institution.city, true) }
                val cityMentionedInQuery = institution.city.isNotBlank() &&
                    normalized.contains(institution.city.trim().lowercase())
                normalized.isBlank() || requestedCityMatches || cityMentionedInQuery ||
                    entityMatches(
                        request.query,
                        terms,
                        "${institution.name} ${institution.city} ${institution.specialties} ${institution.tags}"
                    )
            }.sortedByDescending { it.rating }.take(request.limit)
        } else emptyList()

        val doctorInstitutionNames = doctorRepository.findAll().associate { doctor ->
            doctor.id to doctorInstitutionService.institutionsFor(doctor.id).joinToString(" ") { it.name }
        }
        val doctors = if (DiscoverSearchScope.DOCTOR in request.scopes) {
            doctorRepository.findAll().filter { doctor ->
                normalized.isBlank() || entityMatches(
                    request.query,
                    terms,
                    "${doctor.name} ${doctorInstitutionNames[doctor.id].orEmpty()} ${doctor.title} ${doctor.specialties}"
                )
            }.sortedByDescending { it.rating }.take(request.limit)
        } else emptyList()

        return DiscoverSearchResult(projects, institutions, doctors)
    }

    fun searchProjects(
        categories: Collection<String> = emptyList(),
        cities: Collection<String> = emptyList(),
        query: String = "",
        tags: Collection<String> = emptyList(),
        fallbackWhenNoMatch: Boolean = false
    ): List<ProjectWithInstitutionsResponse> = search(
        DiscoverSearchRequest(
            query = query,
            categories = categories,
            cities = cities,
            tags = tags,
            fallbackWhenNoMatch = fallbackWhenNoMatch,
            scopes = setOf(DiscoverSearchScope.PROJECT)
        )
    ).projects

    private fun searchProjectResults(
        categories: Collection<String>,
        cities: Collection<String>,
        query: String,
        tags: Collection<String>,
        fallbackWhenNoMatch: Boolean
    ): List<ProjectWithInstitutionsResponse> {
        val allInstitutionProjects = institutionProjectRepository.findAll().filter { it.isActive }
        val institutionMap = institutionRepository.findAll().associateBy { it.id }
        val allProjects = projectRepository.findAll()
        val projectMap = allProjects.associateBy { it.id }
        val normalizedQuery = query.trim().lowercase()
        val queryTerms = keywordExtractor.extract(query, cities)
        val matchingFragments = extractMatchingFragments(query)
        val fuzzyTerms = fuzzyTermsFor(normalizedQuery)

        val offeringsByProject = allInstitutionProjects.groupBy { it.projectId }.mapValues { (_, offerings) ->
            offerings.map { offering ->
                val institution = institutionMap[offering.institutionId]
                val project = projectMap[offering.projectId] ?: return@map null
                val effective = institutionProjectDetailResolver.resolve(offering, project)
                InstitutionProjectItemResponse(
                    id = offering.id,
                    institutionId = offering.institutionId,
                    institutionName = institution?.name.orEmpty(),
                    institutionCity = institution?.city.orEmpty(),
                    projectId = offering.projectId,
                    name = effective.name,
                    category = effective.category,
                    description = effective.description,
                    rating = effective.rating,
                    reviewCount = effective.reviewCount,
                    tags = effective.tags,
                    slogan = effective.slogan,
                    detailContent = effective.detailContent,
                    price = offering.price,
                    originalPrice = offering.originalPrice,
                    currency = offering.currency,
                    coverImage = effective.coverImage,
                    images = effective.images,
                    salesCount = offering.salesCount,
                    isActive = offering.isActive
                )
            }.filterNotNull()
        }

        val matchedProjects = allProjects.filter { project ->
            val searchable = "${project.name} ${project.category} ${project.tags} ${project.categoryTags} ${project.description} ${project.slogan}".lowercase()
            val projectOfferings = offeringsByProject[project.id].orEmpty()
            val offeringSearchable = projectOfferings.joinToString(" ") {
                "${it.institutionName} ${it.institutionCity} ${it.name} ${it.category} ${it.description} ${it.tags} ${it.slogan} ${plainText(it.detailContent)}"
            }.lowercase()
            val combinedSearchable = "$searchable $offeringSearchable"
            val projectCities = projectOfferings.map { it.institutionCity }
            (categories.isEmpty() || categories.any { category ->
                category.equals(project.category, true) || projectOfferings.any { category.equals(it.category, true) }
            }) &&
                (cities.isEmpty() || projectCities.any { city -> cities.any { it.equals(city, true) } }) &&
                (normalizedQuery.isBlank() || combinedSearchable.contains(normalizedQuery) || queryTerms.any(combinedSearchable::contains) ||
                    matchingFragments.any(combinedSearchable::contains) || fuzzyTerms.any(combinedSearchable::contains) ||
                    listOf(project.name, project.category)
                        .filter { it.isNotBlank() }
                        .any { normalizedQuery.contains(it.lowercase()) } ||
                    project.tags.split(",").map { it.trim().lowercase() }
                        .filter { it.length >= 2 }
                        .any(normalizedQuery::contains)) &&
                (tags.isEmpty() || tags.any { tag ->
                    project.tags.split(",").any { it.trim().equals(tag, true) } ||
                        projectOfferings.any { offering -> offering.tags.split(",").any { it.trim().equals(tag, true) } }
                })
        }
        // Popular-project fallback is allowed only for genuinely vague concerns.
        // An explicit treatment name must never fall through to unrelated offers.
        val isVagueConcern = vagueConcernTriggers.any(normalizedQuery::contains)
        val selectedProjects = if (matchedProjects.isEmpty() && fallbackWhenNoMatch && isVagueConcern) {
            allProjects.filter { project ->
                offeringsByProject[project.id].orEmpty().any { offering ->
                    offering.isActive && (cities.isEmpty() || cities.any { it.equals(offering.institutionCity, true) })
                }
            }.sortedWith(compareByDescending<com.joysong.server.project.entity.ProjectEntity> { it.rating }.thenByDescending { it.reviewCount }).take(3)
        } else matchedProjects

        return selectedProjects.map { project ->
            val offerings = offeringsByProject[project.id].orEmpty()
                .filter { it.isActive && (cities.isEmpty() || cities.any { city -> city.equals(it.institutionCity, true) }) }
                .sortedWith(compareByDescending<InstitutionProjectItemResponse> { it.salesCount }.thenBy { it.price })
            ProjectWithInstitutionsResponse(
                id = project.id, name = project.name, category = project.category,
                description = project.description, tags = project.tags, categoryTags = project.categoryTags,
                coverImage = project.coverImage, images = project.images, referencePrice = project.referencePrice, currency = project.currency,
                slogan = project.slogan, detailContent = project.detailContent, salesCount = project.salesCount,
                rating = project.rating, reviewCount = project.reviewCount, institutionProjects = offerings
            )
        }.sortedWith(compareByDescending<ProjectWithInstitutionsResponse> { it.rating }.thenByDescending { it.reviewCount })
    }

    fun citiesMentionedIn(query: String): List<String> = institutionRepository.findAll()
        .map { it.city.trim() }
        .filter { it.isNotBlank() && query.contains(it, true) }
        .distinct()

    /** Atomic domain keywords used for agent context/auditing; sentence fragments are excluded. */
    fun extractKeywords(query: String): List<String> = keywordExtractor
        .extract(query, citiesMentionedIn(query))
        .map { it.trim() }
        .filter { it.isNotBlank() && it !in searchStopWords && it in contextDomainTerms }
        .distinct()

    /** Short meaningful fragments cover custom offering names and descriptions not present in the fixed vocabulary. */
    fun extractMatchingFragments(query: String): Set<String> {
        val normalized = query.trim().lowercase()
        val fragments = normalized
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .flatMap { token ->
                if (token.any { it.code in 0x3400..0x9FFF }) {
                    buildList {
                        if (token.length >= 2) addAll(token.windowed(2))
                        if (token.length >= 3) addAll(token.windowed(3))
                    }
                } else listOf(token).filter { it.length >= 3 }
            }
            .filterNot { it in matchingFragmentStopWords }
        return (extractKeywords(query) + fragments).map(String::trim).filter { it.length >= 2 }.toSet()
    }

    fun isExplicitTreatmentQuery(query: String): Boolean {
        val normalized = query.lowercase()
        if (explicitTreatmentTerms.any(normalized::contains)) return true
        if (namedTreatmentPattern.findAll(query).any { match ->
                val candidate = match.groupValues[1].trim().lowercase()
                candidate.length >= 2 && genericTreatmentPhrases.none(candidate::contains)
            }
        ) return true
        val projects = projectRepository.findAll()
        if (projects.any { project ->
            listOf(project.name, project.category)
                .filter { it.length >= 2 }
                .any { normalized.contains(it.lowercase()) }
        }) return true
        val projectMap = projects.associateBy { it.id }
        return institutionProjectRepository.findAll().asSequence()
            .filter { it.isActive }
            .mapNotNull { offering ->
                projectMap[offering.projectId]?.let { institutionProjectDetailResolver.resolve(offering, it) }
            }
            .any { effective ->
                (listOf(effective.name, effective.category, effective.slogan) + effective.tags.split(",", "，"))
                    .map(String::trim)
                    .filter { it.length >= 2 }
                    .any { normalized.contains(it.lowercase()) }
            }
    }

    fun explicitlyRequestedEntityTypes(query: String): Set<RequestedEntityType> {
        val requested = linkedSetOf<RequestedEntityType>()
        if (isExplicitTreatmentQuery(query)) requested += RequestedEntityType.TREATMENT

        val normalized = query.trim().lowercase()
        val institutions = institutionRepository.findAll()
        val namedUnknownInstitution = namedInstitutionPattern.findAll(query).any { match ->
            val candidate = match.groupValues[1].trim().lowercase()
            candidate !in genericInstitutionPrefixes &&
                genericInstitutionPhrases.none(candidate::contains) &&
                explicitTreatmentTerms.none(candidate::contains)
        }
        if (institutions.any { it.name.length >= 2 && normalized.contains(it.name.lowercase()) } || namedUnknownInstitution) {
            requested += RequestedEntityType.INSTITUTION
        }

        // A doctor name already present in the database is authoritative. For an
        // unknown name, require a name immediately followed by a professional title
        // so generic requests such as "推荐医生" are not treated as a named doctor.
        val knownDoctorRequested = doctorRepository.findAll()
            .any { it.name.length >= 2 && normalized.contains(it.name.lowercase()) }
        val namedDoctorRequested = namedDoctorPattern.findAll(query).any { match ->
            match.groupValues[1].trim().lowercase() !in genericDoctorPrefixes
        }
        if (knownDoctorRequested || namedDoctorRequested) requested += RequestedEntityType.DOCTOR
        return requested
    }

    private fun fuzzyTermsFor(query: String): Set<String> = concernVocabulary
        .filterKeys { triggers -> triggers.any(query::contains) }
        .values.flatten().toSet()

    private fun entityMatches(query: String, terms: List<String>, searchable: String): Boolean {
        val normalizedSearchable = searchable.lowercase()
        val primaryName = searchable.substringBefore(' ').trim()
        return query.contains(primaryName, true) || terms.any(normalizedSearchable::contains)
    }

    private fun plainText(content: String?): String = content.orEmpty()
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(500)

    private companion object {
        val vagueConcernTriggers = setOf(
            "皮肤不好", "皮肤差", "肤质", "暗沉", "没气色", "不细腻", "显老", "不饱满",
            "bad skin", "dull", "texture", "aging", "volume loss"
        )
        val explicitTreatmentTerms = setOf(
            "双眼皮", "重睑", "隆鼻", "鼻综合", "隆胸", "吸脂", "脂肪填充", "玻尿酸", "肉毒", "水光", "光子嫩肤",
            "热玛吉", "超声炮", "超声刀", "射频", "激光", "皮秒", "点阵", "线雕", "植发", "脱毛",
            "double eyelid", "blepharoplasty", "rhinoplasty", "liposuction", "filler", "botox", "ipl", "thermage", "ultherapy", "laser"
        )
        val concernVocabulary = mapOf(
            setOf("皮肤不好", "皮肤差", "肤质", "暗沉", "没气色", "提亮", "dull", "skin tone", "bad skin") to
                listOf("嫩肤", "光子", "提亮", "肤色", "ipl", "aopt", "dpl"),
            setOf("毛孔", "粗糙", "不细腻", "pores", "texture", "rough skin") to
                listOf("毛孔", "嫩肤", "点阵", "射频", "微针", "laser"),
            setOf("斑", "色沉", "痘印", "印子", "spots", "pigmentation", "acne marks") to
                listOf("祛斑", "色素", "光子", "激光", "皮秒", "ipl"),
            setOf("痘", "爆痘", "粉刺", "acne", "breakout") to
                listOf("痤疮", "祛痘", "红蓝光", "光子"),
            setOf("皱纹", "细纹", "显老", "抗老", "wrinkle", "fine lines", "aging") to
                listOf("抗衰", "除皱", "肉毒", "射频", "超声"),
            setOf("松弛", "下垂", "轮廓", "紧致", "sagging", "lifting", "firming") to
                listOf("紧致", "提升", "射频", "超声", "轮廓"),
            setOf("凹陷", "不饱满", "填充", "hollow", "volume loss", "filler") to
                listOf("填充", "玻尿酸", "胶原", "脂肪"),
            setOf("双眼皮", "重睑", "double eyelid", "blepharoplasty") to
                listOf("双眼皮", "重睑", "切开", "埋线", "double eyelid", "blepharoplasty")
        )
        val namedInstitutionPattern = Regex(
            "([\\p{L}\\p{N}·•]{2,30})\\s*(?:医院|医疗美容(?:医院|门诊部|诊所)?|医美机构|诊所|clinic|hospital)",
            RegexOption.IGNORE_CASE
        )
        val namedDoctorPattern = Regex(
            "([\\p{L}·•]{2,30})\\s*(?:医生|医师|主任|院长|doctor|dr\\.?)",
            RegexOption.IGNORE_CASE
        )
        val namedTreatmentPattern = Regex(
            "[“\"']?([\\p{L}\\p{N}·•]{2,30})[”\"']?\\s*(?:项目|手术|治疗|疗程|procedure|treatment)",
            RegexOption.IGNORE_CASE
        )
        val genericDoctorPrefixes = setOf(
            "推荐", "找个", "寻找", "咨询", "机构", "哪个", "哪位", "什么", "好的", "靠谱", "专业",
            "recommend", "find", "which", "good", "a", "the"
        )
        val genericInstitutionPrefixes = setOf("医美", "美容", "整形", "医疗", "正规", "靠谱", "专业", "好的", "clinic", "hospital")
        val genericInstitutionPhrases = setOf(
            "推荐", "哪家", "哪些", "有什么", "有没有", "找", "附近", "当地", "北京", "上海", "广州", "深圳",
            "recommend", "which", "find", "nearby", "best"
        )
        val genericTreatmentPhrases = setOf(
            "推荐", "哪些", "什么", "哪个", "医美", "美容", "机构", "对比", "比较", "总结", "其他", "适合", "想了解", "有什么",
            "recommend", "which", "what", "compare", "comparison", "summary", "other", "suitable"
        )
        val searchStopWords = setOf(
            "对比", "比较", "区别", "总结", "报告", "机构", "医生", "项目", "推荐", "一下",
            "compare", "summary", "report", "clinic", "doctor", "treatment", "recommend", "please"
        )
        val matchingFragmentStopWords = searchStopWords + setOf(
            "我想", "想要", "请问", "怎么", "怎样", "么样", "可以", "这个", "那个", "一下", "了解",
            "多少", "价格", "费用", "哪些", "什么", "有没有", "是否", "服务", "the", "and", "for", "with"
        )
        val contextDomainTerms = setOf(
            "双眼皮", "重睑", "隆鼻", "鼻综合", "隆胸", "吸脂", "脂肪填充", "玻尿酸", "肉毒", "水光",
            "光子嫩肤", "光子", "热玛吉", "超声炮", "超声刀", "射频", "激光", "皮秒", "点阵", "线雕", "植发", "脱毛",
            "暗沉", "毛孔", "粗糙", "祛斑", "色沉", "痘印", "痤疮", "皱纹", "细纹", "松弛", "下垂", "凹陷", "抗衰",
            "double eyelid", "blepharoplasty", "rhinoplasty", "liposuction", "filler", "botox", "ipl", "aopt", "dpl",
            "thermage", "ultherapy", "laser", "acne", "wrinkle", "pigmentation", "sagging", "skin texture"
        )
    }
}

data class DiscoverSearchRequest(
    val query: String = "",
    val categories: Collection<String> = emptyList(),
    val cities: Collection<String> = emptyList(),
    val tags: Collection<String> = emptyList(),
    val fallbackWhenNoMatch: Boolean = false,
    val scopes: Set<DiscoverSearchScope> = DiscoverSearchScope.entries.toSet(),
    val limit: Int = Int.MAX_VALUE
)

data class DiscoverSearchResult(
    val projects: List<ProjectWithInstitutionsResponse> = emptyList(),
    val institutions: List<InstitutionEntity> = emptyList(),
    val doctors: List<DoctorEntity> = emptyList()
)

enum class DiscoverSearchScope {
    PROJECT,
    INSTITUTION,
    DOCTOR
}

enum class RequestedEntityType {
    TREATMENT,
    DOCTOR,
    INSTITUTION
}
