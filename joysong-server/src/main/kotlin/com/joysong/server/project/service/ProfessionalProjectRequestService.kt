package com.joysong.server.project.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.common.money.CurrencyCode
import com.joysong.server.identity.service.InstitutionRelationshipReviewAuthorityOperations
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.service.OrderSplitRatePolicy
import org.springframework.cache.CacheManager
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

private val PROJECT_REQUEST_DECISIONS = setOf("APPROVED", "REJECTED")
private val MAX_PROJECT_REQUEST_MONEY = BigDecimal("99999999.99")
private val HUNDRED_PERCENT = BigDecimal("100.00")

private const val MAX_NAME_LENGTH = 200
private const val MAX_CATEGORY_LENGTH = 100
private const val MAX_DESCRIPTION_LENGTH = 5_000
private const val MAX_SLOGAN_LENGTH = 500
private const val MAX_COVER_IMAGE_LENGTH = 500
private const val MAX_DETAIL_CONTENT_LENGTH = 20_000
private const val MAX_NOTES_LENGTH = 2_000
private const val MAX_TAG_ITEMS = 20
private const val MAX_IMAGE_ITEMS = 20
private const val MAX_TAG_ITEM_LENGTH = 100
private const val MAX_IMAGE_ITEM_LENGTH = 500
private const val MAX_ENCODED_LIST_LENGTH = 20_000
private const val MAX_TARGET_TAGS_LENGTH = 500
private const val MAX_TARGET_IMAGES_LENGTH = 2_000

@Service
class ProfessionalProjectRequestService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val splitRatePolicy: OrderSplitRatePolicy,
    private val reviewAuthority: InstitutionRelationshipReviewAuthorityOperations,
    private val cacheManager: CacheManager
) {
    @Transactional
    fun submitPlatform(
        actor: ManagementActor,
        request: DoctorPlatformProjectRequest
    ): ProjectRequestSubmissionResult {
        val doctorId = requireDoctor(actor)
        val name = requiredText("项目名称", request.name, MAX_NAME_LENGTH)
        val category = requiredText("项目分类", request.category, MAX_CATEGORY_LENGTH)
        val description = requiredText("项目说明", request.description, MAX_DESCRIPTION_LENGTH)
        requireMoney("参考价格", request.referencePrice)
        requireCount("销量", request.salesCount)
        val tags = normalizeTargetList(
            "项目标签", request.tags, MAX_TAG_ITEMS, MAX_TAG_ITEM_LENGTH, MAX_TARGET_TAGS_LENGTH
        )
        val categoryTags = normalizeTargetList(
            "分类标签", request.categoryTags, MAX_TAG_ITEMS, MAX_TAG_ITEM_LENGTH, MAX_TARGET_TAGS_LENGTH
        )
        val images = normalizeTargetList(
            "项目图片", request.images, MAX_IMAGE_ITEMS, MAX_IMAGE_ITEM_LENGTH, MAX_TARGET_IMAGES_LENGTH
        )
        val slogan = normalizeText("项目标语", request.slogan, MAX_SLOGAN_LENGTH)
        val detailContent = normalizeOptionalText("项目详情", request.detailContent, MAX_DETAIL_CONTENT_LENGTH)
        val coverImage = normalizeText("封面图片", request.coverImage, MAX_COVER_IMAGE_LENGTH)
        val notes = normalizeOptionalText("申请备注", request.notes, MAX_NOTES_LENGTH)
        if (count(
            """
            SELECT COUNT(*) FROM professional_project_requests
            WHERE request_type = 'PLATFORM' AND doctor_id = ? AND name = ? AND category = ?
              AND status = 'PENDING'
            """.trimIndent(),
            doctorId, name, category
        ) != 0L) {
            throw ProfessionalProjectRequestConflictException("同一项目已有待处理申请")
        }

        val id = UUID.randomUUID().toString()
        insertRequest(
            ProjectRequestSnapshot(
                id = id,
                requestType = "PLATFORM",
                doctorId = doctorId,
                name = name,
                category = category,
                description = description,
                tags = encodeList(tags),
                slogan = slogan,
                detailContent = detailContent,
                currency = request.currency.name,
                coverImage = coverImage,
                images = encodeList(images),
                salesCount = request.salesCount,
                referencePrice = request.referencePrice,
                categoryTags = encodeList(categoryTags),
                notes = notes
            )
        )
        return ProjectRequestSubmissionResult(id, "PLATFORM", "PENDING")
    }

    @Transactional
    fun submitInstitution(
        actor: ManagementActor,
        institutionId: String,
        request: DoctorInstitutionProjectRequest
    ): ProjectRequestSubmissionResult {
        val doctorId = requireDoctor(actor)
        val targetInstitutionId = required(institutionId, "机构不能为空")
        if (targetInstitutionId !in actor.doctorInstitutionIds) {
            throw AccessDeniedException("只能向已通过执业关系的机构提交项目申请")
        }
        if (count(
            "SELECT COUNT(*) FROM institutions WHERE id = ? AND deleted_at IS NULL",
            targetInstitutionId
        ) != 1L) {
            throw ProfessionalProjectRequestNotFoundException("机构不存在")
        }
        val projectId = required(request.projectId, "平台项目不能为空")
        val name = normalizeOptionalText("项目名称", request.name, MAX_NAME_LENGTH)
        val category = normalizeOptionalText("项目分类", request.category, MAX_CATEGORY_LENGTH)
        val description = normalizeOptionalText("服务内容", request.description, MAX_DESCRIPTION_LENGTH)
        val tags = request.tags
            ?.takeIf(List<String>::isNotEmpty)
            ?.let {
                normalizeTargetList(
                    "项目标签", it, MAX_TAG_ITEMS, MAX_TAG_ITEM_LENGTH, MAX_TARGET_TAGS_LENGTH
                )
            }
        val slogan = normalizeOptionalText("项目标语", request.slogan, MAX_SLOGAN_LENGTH).orEmpty()
        val detailContent = normalizeOptionalText("项目详情", request.detailContent, MAX_DETAIL_CONTENT_LENGTH)
        val coverImage = normalizeOptionalText("封面图片", request.coverImage, MAX_COVER_IMAGE_LENGTH).orEmpty()
        val images = request.images
            ?.takeIf(List<String>::isNotEmpty)
            ?.let {
                normalizeTargetList(
                    "项目图片", it, MAX_IMAGE_ITEMS, MAX_IMAGE_ITEM_LENGTH, MAX_TARGET_IMAGES_LENGTH
                )
            }
        val notes = normalizeOptionalText("申请备注", request.notes, MAX_NOTES_LENGTH)
        requireMoney("项目价格", request.price)
        optionalMoney("原价", request.originalPrice)
        requireMoney("咨询费", request.consultationFee)
        requireCount("销量", request.salesCount)
        splitRatePolicy.resolve(request.institutionRate, request.commissionRate)

        if (count("SELECT COUNT(*) FROM projects WHERE id = ? AND deleted_at IS NULL", projectId) != 1L) {
            throw ProfessionalProjectRequestNotFoundException("平台项目不存在")
        }
        if (count(
            "SELECT COUNT(*) FROM institution_projects WHERE institution_id = ? AND project_id = ?",
            targetInstitutionId,
            projectId
        ) != 0L) {
            throw ProfessionalProjectRequestConflictException("该机构已存在此平台项目，请申请加入机构项目")
        }
        if (count(
            """
            SELECT COUNT(*) FROM professional_project_requests
            WHERE request_type = 'INSTITUTION' AND doctor_id = ? AND institution_id = ? AND project_id = ?
              AND status = 'PENDING'
            """.trimIndent(),
            doctorId, targetInstitutionId, projectId
        ) != 0L) {
            throw ProfessionalProjectRequestConflictException("同一项目已有待处理申请")
        }

        val id = UUID.randomUUID().toString()
        insertRequest(
            ProjectRequestSnapshot(
                id = id,
                requestType = "INSTITUTION",
                doctorId = doctorId,
                institutionId = targetInstitutionId,
                projectId = projectId,
                name = name,
                category = category,
                description = description,
                tags = tags?.let(::encodeList),
                slogan = slogan,
                detailContent = detailContent,
                currency = request.currency.name,
                coverImage = coverImage,
                images = images?.let(::encodeList),
                salesCount = request.salesCount,
                price = request.price,
                originalPrice = request.originalPrice,
                isActive = request.isActive,
                consultationFee = request.consultationFee,
                commissionRate = request.commissionRate,
                institutionRate = request.institutionRate,
                notes = notes
            )
        )
        return ProjectRequestSubmissionResult(id, "INSTITUTION", "PENDING")
    }

    fun list(actor: ManagementActor): List<ProfessionalProjectRequestView> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (!actor.isAdmin) {
            actor.doctorId?.let {
                conditions += "r.doctor_id = ?"
                args += it
            }
            if (actor.managedInstitutionIds.isNotEmpty()) {
                val institutionIds = actor.managedInstitutionIds.sorted()
                conditions += "(r.request_type = 'INSTITUTION' AND r.institution_id IN (${institutionIds.joinToString(",") { "?" }}))"
                args.addAll(institutionIds)
            }
            if (conditions.isEmpty()) return emptyList()
        }
        val where = if (conditions.isEmpty()) "" else "WHERE (${conditions.joinToString(" OR ")})"
        val currentPlatformRate = splitRatePolicy.currentPlatformRate()
        return jdbcTemplate.query(
            """
            SELECT r.id, r.request_type, r.doctor_id, d.name AS doctor_name,
                   r.institution_id, i.name AS institution_name, r.project_id,
                   p.name AS project_name, r.name, r.category, r.description,
                   r.tags, r.slogan, r.detail_content, r.currency, r.cover_image,
                   r.images, r.sales_count, r.reference_price, r.category_tags,
                   r.price, r.original_price, r.is_active, r.consultation_fee,
                   r.commission_rate, r.institution_rate, r.notes, r.status,
                   r.review_note, r.reviewed_by, r.reviewed_at,
                   r.resulting_project_id, r.resulting_institution_project_id,
                   r.submitted_at, r.updated_at
            FROM professional_project_requests r
            JOIN doctors d ON d.id = r.doctor_id
            LEFT JOIN institutions i ON i.id = r.institution_id
            LEFT JOIN projects p ON p.id = r.project_id
            $where
            ORDER BY CASE r.status WHEN 'PENDING' THEN 0 ELSE 1 END, r.submitted_at DESC
            """.trimIndent(),
            { rs, _ ->
                val requestType = rs.getString("request_type")
                val tagsJson = rs.getString("tags")
                val imagesJson = rs.getString("images")
                val categoryTagsJson = rs.getString("category_tags")
                val storedSlogan = rs.getString("slogan")
                val storedCoverImage = rs.getString("cover_image")
                val consultationFee = rs.getBigDecimal("consultation_fee")
                val commissionRate = rs.getBigDecimal("commission_rate")
                val institutionRate = rs.getBigDecimal("institution_rate")
                ProfessionalProjectRequestView(
                    id = rs.getString("id"),
                    requestType = requestType,
                    doctorId = rs.getString("doctor_id"),
                    doctorName = rs.getString("doctor_name"),
                    institutionId = rs.getString("institution_id"),
                    institutionName = rs.getString("institution_name"),
                    projectId = rs.getString("project_id"),
                    projectName = rs.getString("project_name"),
                    name = rs.getString("name"),
                    category = rs.getString("category"),
                    description = rs.getString("description"),
                    tags = if (requestType == "INSTITUTION" && tagsJson == null) null else decodeList(tagsJson),
                    slogan = if (requestType == "PLATFORM") storedSlogan.orEmpty() else storedSlogan?.takeIf(String::isNotBlank),
                    detailContent = rs.getString("detail_content"),
                    currency = parseCurrency(rs.getString("currency")),
                    coverImage = if (requestType == "PLATFORM") storedCoverImage.orEmpty() else storedCoverImage?.takeIf(String::isNotBlank),
                    images = if (requestType == "INSTITUTION" && imagesJson == null) null else decodeList(imagesJson),
                    salesCount = rs.getInt("sales_count"),
                    referencePrice = rs.getBigDecimal("reference_price")
                        ?: if (requestType == "PLATFORM") BigDecimal.ZERO else null,
                    categoryTags = if (requestType == "PLATFORM") decodeList(categoryTagsJson) else null,
                    price = rs.getBigDecimal("price"),
                    originalPrice = rs.getBigDecimal("original_price"),
                    isActive = rs.getObject("is_active")?.let { rs.getBoolean("is_active") },
                    institutionSplit = if (requestType == "INSTITUTION") {
                        val submittedConsultationFee = requireNotNull(consultationFee) { "机构项目申请缺少咨询费快照" }
                        val submittedCommissionRate = requireNotNull(commissionRate) { "机构项目申请缺少顾问分账快照" }
                        val submittedInstitutionRate = requireNotNull(institutionRate) { "机构项目申请缺少机构分账快照" }
                        InstitutionProjectSplitView(
                            consultationFee = submittedConsultationFee,
                            commissionRate = submittedCommissionRate,
                            institutionRate = submittedInstitutionRate,
                            platformRate = currentPlatformRate,
                            doctorRate = HUNDRED_PERCENT - currentPlatformRate - submittedInstitutionRate - submittedCommissionRate
                        )
                    } else null,
                    notes = rs.getString("notes"),
                    status = rs.getString("status"),
                    reviewNote = rs.getString("review_note"),
                    reviewedBy = rs.getString("reviewed_by"),
                    reviewedAt = rs.getTimestamp("reviewed_at")?.toLocalDateTime(),
                    resultingProjectId = rs.getString("resulting_project_id"),
                    resultingInstitutionProjectId = rs.getString("resulting_institution_project_id"),
                    submittedAt = rs.getTimestamp("submitted_at").toLocalDateTime(),
                    updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
                )
            },
            *args.toTypedArray()
        )
    }

    @Transactional
    fun reviewPlatform(
        actor: ManagementActor,
        id: String,
        review: ProjectRequestReview
    ): ProjectRequestReviewResult {
        val normalizedReview = normalizeReview(review)
        return review(actor, id, normalizedReview, "PLATFORM")
    }

    @Transactional
    fun reviewInstitution(
        actor: ManagementActor,
        id: String,
        review: ProjectRequestReview
    ): ProjectRequestReviewResult = review(actor, id, normalizeReview(review), "INSTITUTION")

    private fun review(
        actor: ManagementActor,
        id: String,
        review: NormalizedProjectRequestReview,
        expectedType: String
    ): ProjectRequestReviewResult {
        val target = lockedTarget(required(id, "申请不能为空"))
            ?: throw ProfessionalProjectRequestNotFoundException("项目申请不存在")
        require(target.requestType == expectedType) { "项目申请类型不正确" }
        if (target.status != "PENDING") {
            throw ProfessionalProjectRequestConflictException("项目申请已处理")
        }
        if (expectedType == "PLATFORM" && !actor.isAdmin) {
            throw AccessDeniedException("该操作仅限平台管理员")
        }
        if (expectedType == "INSTITUTION" && !actor.isAdmin) {
            reviewAuthority.requireCurrentAuthority(actor, requireNotNull(target.institutionId))
        }

        var resultingProjectId: String? = null
        var resultingInstitutionProjectId: String? = null
        if (review.decision == "APPROVED") {
            if (expectedType == "PLATFORM") {
                resultingProjectId = createProject(target)
            } else {
                val institutionId = requireNotNull(target.institutionId)
                lockInstitution(institutionId)
                lockActiveApplicantRelationship(target.doctorId, institutionId)
                val platformProject = lockPlatformProject(requireNotNull(target.projectId))
                requireNoInstitutionProject(institutionId, platformProject.id)
                revalidateInstitutionApproval(target)
                resultingInstitutionProjectId = try {
                    createInstitutionProject(target, platformProject)
                } catch (_: DuplicateKeyException) {
                    throw ProfessionalProjectRequestConflictException("该机构已存在此平台项目，请改为申请加入机构项目")
                }
            }
        }
        val updated = jdbcTemplate.update(
            """
            UPDATE professional_project_requests
            SET status = ?, review_note = ?, reviewed_by = ?, reviewed_at = NOW(),
                resulting_project_id = ?, resulting_institution_project_id = ?
            WHERE id = ? AND status = 'PENDING'
            """.trimIndent(),
            review.decision, review.reviewNote, actor.userId,
            resultingProjectId, resultingInstitutionProjectId, target.id
        )
        if (updated != 1) {
            throw ProfessionalProjectRequestConflictException("项目申请已被其他审核人处理")
        }
        if (review.decision == "APPROVED") evictProjectCatalogCachesAfterCommit()
        return ProjectRequestReviewResult(target.id, review.decision, resultingProjectId, resultingInstitutionProjectId)
    }

    private fun createProject(target: ProjectRequestTarget): String {
        requireMoney("参考价格", requireNotNull(target.referencePrice))
        requireCount("销量", target.salesCount)
        val tags = toTargetList(
            "项目标签", target.tags, MAX_TAG_ITEMS, MAX_TAG_ITEM_LENGTH, MAX_TARGET_TAGS_LENGTH
        ).orEmpty()
        val categoryTags = toTargetList(
            "分类标签", target.categoryTags, MAX_TAG_ITEMS, MAX_TAG_ITEM_LENGTH, MAX_TARGET_TAGS_LENGTH
        ).orEmpty()
        val images = toTargetList(
            "项目图片", target.images, MAX_IMAGE_ITEMS, MAX_IMAGE_ITEM_LENGTH, MAX_TARGET_IMAGES_LENGTH
        ).orEmpty()
        val projectId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO projects
                (id, name, category, description, tags, category_tags, cover_image, images,
                 reference_price, currency, slogan, detail_content, rating, review_count, sales_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            projectId,
            requireNotNull(target.name),
            requireNotNull(target.category),
            requireNotNull(target.description),
            tags,
            categoryTags,
            target.coverImage.orEmpty(),
            images,
            target.referencePrice,
            target.currency.name,
            target.slogan.orEmpty(),
            target.detailContent,
            BigDecimal.ZERO,
            0,
            target.salesCount
        )
        return projectId
    }

    private fun createInstitutionProject(
        target: ProjectRequestTarget,
        platformProject: LockedPlatformProject
    ): String {
        val effective = resolveInstitutionProject(target, platformProject)
        val institutionProjectId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO institution_projects
                (id, institution_id, project_id, name, category, description, rating, review_count,
                 tags, slogan, detail_content, price, original_price, currency, cover_image, images,
                 sales_count, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            institutionProjectId, target.institutionId, platformProject.id,
            effective.name, effective.category, effective.description,
            BigDecimal.ZERO, 0, effective.tags, effective.slogan, effective.detailContent,
            requireNotNull(target.price), target.originalPrice, target.currency.name,
            effective.coverImage, effective.images, target.salesCount, requireNotNull(target.isActive)
        )
        jdbcTemplate.update(
            """
            INSERT INTO doctor_projects
                (doctor_id, project_id, institution_project_id, service_description, service_tags,
                 schedule_note, cover_image, images, price)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            target.doctorId, platformProject.id, institutionProjectId,
            effective.description, effective.tags, "", effective.coverImage, effective.images,
            target.price
        )
        jdbcTemplate.update(
            """
            INSERT INTO doctor_institution_project_configs
                (id, doctor_id, institution_project_id, consultation_fee, commission_rate, institution_rate)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID().toString(), target.doctorId, institutionProjectId,
            requireNotNull(target.consultationFee), requireNotNull(target.commissionRate),
            requireNotNull(target.institutionRate)
        )
        return institutionProjectId
    }

    private fun lockInstitution(institutionId: String) {
        val locked = jdbcTemplate.queryForList(
            "SELECT id FROM institutions WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
            String::class.java,
            institutionId
        ).firstOrNull()
        if (locked == null) throw ProfessionalProjectRequestNotFoundException("机构不存在")
    }

    private fun lockActiveApplicantRelationship(doctorId: String, institutionId: String) {
        val relationship = jdbcTemplate.queryForList(
            """
            SELECT id FROM doctor_institutions
            WHERE doctor_id = ? AND institution_id = ? AND status = 'APPROVED'
              AND revoked_at IS NULL AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            String::class.java,
            doctorId,
            institutionId
        ).firstOrNull()
        if (relationship == null) {
            throw ProfessionalProjectRequestConflictException("医生已不具备该机构的有效执业关系")
        }
    }

    private fun lockPlatformProject(projectId: String): LockedPlatformProject = jdbcTemplate.query(
        """
        SELECT id, name, category, description, tags, category_tags, cover_image, images,
               reference_price, currency, slogan, detail_content, sales_count
        FROM projects
        WHERE id = ? AND deleted_at IS NULL
        FOR UPDATE
        """.trimIndent(),
        { rs, _ ->
            LockedPlatformProject(
                id = rs.getString("id"),
                name = rs.getString("name"),
                category = rs.getString("category"),
                description = rs.getString("description"),
                tags = rs.getString("tags").orEmpty(),
                categoryTags = rs.getString("category_tags").orEmpty(),
                coverImage = rs.getString("cover_image").orEmpty(),
                images = rs.getString("images").orEmpty(),
                referencePrice = rs.getBigDecimal("reference_price") ?: BigDecimal.ZERO,
                currency = parseCurrency(rs.getString("currency")),
                slogan = rs.getString("slogan").orEmpty(),
                detailContent = rs.getString("detail_content"),
                salesCount = rs.getInt("sales_count")
            )
        },
        projectId
    ).firstOrNull() ?: throw ProfessionalProjectRequestNotFoundException("平台项目不存在")

    private fun requireNoInstitutionProject(institutionId: String, projectId: String) {
        val existing = jdbcTemplate.queryForList(
            "SELECT id FROM institution_projects WHERE institution_id = ? AND project_id = ? LIMIT 1",
            String::class.java,
            institutionId,
            projectId
        ).firstOrNull()
        if (existing != null) {
            throw ProfessionalProjectRequestConflictException("该机构已存在此平台项目，请改为申请加入机构项目")
        }
    }

    private fun revalidateInstitutionApproval(target: ProjectRequestTarget) {
        try {
            requireMoney("项目价格", requireNotNull(target.price))
            optionalMoney("原价", target.originalPrice)
            requireMoney("咨询费", requireNotNull(target.consultationFee))
            requireCount("销量", target.salesCount)
            splitRatePolicy.resolve(requireNotNull(target.institutionRate), requireNotNull(target.commissionRate))
        } catch (_: IllegalArgumentException) {
            throw ProfessionalProjectRequestConflictException("申请分账比例与当前平台规则冲突")
        } catch (_: IllegalStateException) {
            throw ProfessionalProjectRequestConflictException("申请分账比例与当前平台规则冲突")
        }
    }

    private fun resolveInstitutionProject(
        target: ProjectRequestTarget,
        platformProject: LockedPlatformProject
    ): EffectiveInstitutionProject = EffectiveInstitutionProject(
        name = target.name?.takeIf(String::isNotBlank) ?: platformProject.name,
        category = target.category?.takeIf(String::isNotBlank) ?: platformProject.category,
        description = target.description?.takeIf(String::isNotBlank) ?: platformProject.description,
        tags = target.tags
            ?.takeIf(List<String>::isNotEmpty)
            ?.let {
                toTargetList("项目标签", it, MAX_TAG_ITEMS, MAX_TAG_ITEM_LENGTH, MAX_TARGET_TAGS_LENGTH)
            }
            ?: platformProject.tags,
        slogan = target.slogan?.takeIf(String::isNotBlank) ?: platformProject.slogan,
        detailContent = target.detailContent?.takeIf(String::isNotBlank) ?: platformProject.detailContent,
        coverImage = target.coverImage?.takeIf(String::isNotBlank) ?: platformProject.coverImage,
        images = target.images
            ?.takeIf(List<String>::isNotEmpty)
            ?.let {
                toTargetList("项目图片", it, MAX_IMAGE_ITEMS, MAX_IMAGE_ITEM_LENGTH, MAX_TARGET_IMAGES_LENGTH)
            }
            ?: platformProject.images
    )

    private fun normalizeReview(review: ProjectRequestReview): NormalizedProjectRequestReview {
        val decision = review.decision.trim().uppercase()
        require(decision in PROJECT_REQUEST_DECISIONS) { "审核决定不正确" }
        val reviewNote = review.reviewNote.trim().takeIf(String::isNotEmpty)
        require(decision != "REJECTED" || reviewNote != null) { "拒绝时必须填写审核意见" }
        return NormalizedProjectRequestReview(decision, reviewNote)
    }

    private fun toTargetList(
        label: String,
        values: List<String>?,
        maxItems: Int,
        maxItemLength: Int,
        maxTargetLength: Int
    ): String? = values?.let {
        normalizeTargetList(label, it, maxItems, maxItemLength, maxTargetLength).joinToString(",")
    }

    private fun evictProjectCatalogCachesAfterCommit() {
        val evict = {
            cacheManager.getCache("discover")?.clear()
            cacheManager.getCache("home")?.clear()
            cacheManager.getCache("projects")?.clear()
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                evict()
            }
        })
    }

    private fun lockedTarget(id: String): ProjectRequestTarget? = jdbcTemplate.query(
        """
        SELECT id, request_type, doctor_id, institution_id, project_id, name, category,
               description, tags, slogan, detail_content, currency, cover_image, images,
               sales_count, reference_price, category_tags, price, original_price, is_active,
               consultation_fee, commission_rate, institution_rate, notes, status
        FROM professional_project_requests WHERE id = ? FOR UPDATE
        """.trimIndent(),
        { rs, _ ->
            ProjectRequestTarget(
                id = rs.getString("id"),
                requestType = rs.getString("request_type"),
                doctorId = rs.getString("doctor_id"),
                institutionId = rs.getString("institution_id"),
                projectId = rs.getString("project_id"),
                name = rs.getString("name"),
                category = rs.getString("category"),
                description = rs.getString("description"),
                tags = rs.getString("tags")?.let(::decodeList),
                slogan = rs.getString("slogan"),
                detailContent = rs.getString("detail_content"),
                currency = parseCurrency(rs.getString("currency")),
                coverImage = rs.getString("cover_image"),
                images = rs.getString("images")?.let(::decodeList),
                salesCount = rs.getInt("sales_count"),
                referencePrice = rs.getBigDecimal("reference_price"),
                categoryTags = rs.getString("category_tags")?.let(::decodeList),
                price = rs.getBigDecimal("price"),
                originalPrice = rs.getBigDecimal("original_price"),
                isActive = rs.getObject("is_active")?.let { rs.getBoolean("is_active") },
                consultationFee = rs.getBigDecimal("consultation_fee"),
                commissionRate = rs.getBigDecimal("commission_rate"),
                institutionRate = rs.getBigDecimal("institution_rate"),
                notes = rs.getString("notes"),
                status = rs.getString("status")
            )
        },
        id
    ).firstOrNull()

    private fun insertRequest(snapshot: ProjectRequestSnapshot) {
        try {
            jdbcTemplate.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, institution_id, project_id, name, category,
                     description, tags, slogan, detail_content, currency, cover_image, images,
                     sales_count, reference_price, category_tags, price, original_price, is_active,
                     consultation_fee, commission_rate, institution_rate, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                snapshot.id, snapshot.requestType, snapshot.doctorId, snapshot.institutionId, snapshot.projectId,
                snapshot.name, snapshot.category, snapshot.description, snapshot.tags, snapshot.slogan,
                snapshot.detailContent, snapshot.currency, snapshot.coverImage, snapshot.images, snapshot.salesCount,
                snapshot.referencePrice, snapshot.categoryTags, snapshot.price, snapshot.originalPrice, snapshot.isActive,
                snapshot.consultationFee, snapshot.commissionRate, snapshot.institutionRate, snapshot.notes
            )
        } catch (_: DuplicateKeyException) {
            throw ProfessionalProjectRequestConflictException("同一项目已有待处理申请")
        }
    }

    private fun requireDoctor(actor: ManagementActor): String = actor.doctorId
        ?: throw AccessDeniedException("只有医生可以提交项目申请")

    private fun required(value: String, message: String): String =
        value.trim().also { require(it.isNotEmpty()) { message } }

    private fun requiredText(label: String, value: String, maxLength: Int): String =
        normalizeText(label, value, maxLength).also {
            require(it.isNotEmpty()) { "$label\u4e0d\u80fd\u4e3a\u7a7a" }
        }

    private fun normalizeText(label: String, value: String, maxLength: Int): String = value.trim().also {
        require(it.length <= maxLength) { "$label\u4e0d\u80fd\u8d85\u8fc7 $maxLength \u4e2a\u5b57\u7b26" }
    }

    private fun normalizeOptionalText(label: String, value: String?, maxLength: Int): String? =
        value?.trim()?.takeIf(String::isNotEmpty)?.also {
            require(it.length <= maxLength) { "$label\u4e0d\u80fd\u8d85\u8fc7 $maxLength \u4e2a\u5b57\u7b26" }
        }

    private fun requireMoney(label: String, value: BigDecimal) {
        require(value >= BigDecimal.ZERO && value <= MAX_PROJECT_REQUEST_MONEY) {
            "$label\u987b\u5728 0..99999999.99 \u4e4b\u95f4"
        }
        require(value.stripTrailingZeros().scale() <= 2) { "$label\u6700\u591a\u4fdd\u7559\u4e24\u4f4d\u5c0f\u6570" }
    }

    private fun optionalMoney(label: String, value: BigDecimal?) {
        value?.let { requireMoney(label, it) }
    }

    private fun requireCount(label: String, value: Int) {
        require(value >= 0) { "$label\u4e0d\u80fd\u4e3a\u8d1f\u6570" }
    }

    private fun normalizeList(
        label: String,
        values: List<String>?,
        maxItems: Int,
        maxItemLength: Int
    ): List<String> {
        val normalized = values.orEmpty()
        require(normalized.size <= maxItems) { "$label\u6700\u591a\u5305\u542b $maxItems \u9879" }
        return normalized.mapIndexed { index, value ->
            value.trim().also {
                require(it.isNotEmpty()) { "$label\u7b2c ${index + 1} \u9879\u4e0d\u80fd\u4e3a\u7a7a" }
                require(it.length <= maxItemLength) { "$label\u6bcf\u9879\u4e0d\u80fd\u8d85\u8fc7 $maxItemLength \u4e2a\u5b57\u7b26" }
            }
        }
    }

    private fun normalizeTargetList(
        label: String,
        values: List<String>?,
        maxItems: Int,
        maxItemLength: Int,
        maxTargetLength: Int
    ): List<String> = normalizeList(label, values, maxItems, maxItemLength).also { normalized ->
        require(normalized.joinToString(",").length <= maxTargetLength) {
            "$label\u4e0d\u80fd\u8d85\u8fc7 $maxTargetLength \u4e2a\u5b57\u7b26"
        }
    }

    private fun encodeList(values: List<String>?): String? = values?.let {
        objectMapper.writeValueAsString(it).also { encoded ->
            require(encoded.length <= MAX_ENCODED_LIST_LENGTH) { "\u6570\u7ec4\u5185\u5bb9\u8fc7\u957f" }
        }
    }

    private fun decodeList(value: String?): List<String> = if (value.isNullOrBlank()) {
        emptyList()
    } else {
        objectMapper.readValue(value, object : TypeReference<List<String>>() {})
    }

    private fun parseCurrency(value: String?): CurrencyCode = value
        ?.takeIf(String::isNotBlank)
        ?.let(CurrencyCode::valueOf)
        ?: CurrencyCode.DEFAULT

    private fun count(sql: String, vararg args: Any): Long =
        jdbcTemplate.queryForObject(sql, Long::class.java, *args)
}

@JsonIgnoreProperties(ignoreUnknown = false)
data class DoctorPlatformProjectRequest(
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val referencePrice: BigDecimal = BigDecimal.ZERO,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val slogan: String = "",
    val salesCount: Int = 0,
    val coverImage: String = "",
    val images: List<String> = emptyList(),
    val detailContent: String? = null,
    val tags: List<String> = emptyList(),
    val categoryTags: List<String> = emptyList(),
    val notes: String = ""
) {
}

@JsonIgnoreProperties(ignoreUnknown = false)
data class DoctorInstitutionProjectRequest(
    val projectId: String = "",
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val slogan: String? = null,
    val detailContent: String? = null,
    val price: BigDecimal = BigDecimal.ZERO,
    val originalPrice: BigDecimal? = null,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val coverImage: String? = null,
    val images: List<String>? = null,
    val salesCount: Int = 0,
    val isActive: Boolean = true,
    val consultationFee: BigDecimal = BigDecimal.ZERO,
    val commissionRate: BigDecimal = BigDecimal.ZERO,
    val institutionRate: BigDecimal = BigDecimal.ZERO,
    val notes: String = ""
) {
}

data class InstitutionProjectApplicationFormConfig(
    val platformRate: BigDecimal
)

data class ProjectRequestReview(
    val decision: String = "",
    val reviewNote: String = ""
)

data class ProjectRequestSubmissionResult(
    val id: String,
    val requestType: String,
    val status: String
)

data class ProjectRequestReviewResult(
    val id: String,
    val status: String,
    val resultingProjectId: String?,
    val resultingInstitutionProjectId: String?
)

data class ProfessionalProjectRequestView(
    val id: String,
    val requestType: String,
    val doctorId: String,
    val doctorName: String,
    val institutionId: String?,
    val institutionName: String?,
    val projectId: String?,
    val projectName: String?,
    val name: String?,
    val category: String?,
    val description: String?,
    val tags: List<String>?,
    val slogan: String?,
    val detailContent: String?,
    val currency: CurrencyCode,
    val coverImage: String?,
    val images: List<String>?,
    val salesCount: Int,
    val referencePrice: BigDecimal?,
    val categoryTags: List<String>?,
    val price: BigDecimal?,
    val originalPrice: BigDecimal?,
    val isActive: Boolean?,
    val institutionSplit: InstitutionProjectSplitView?,
    val notes: String?,
    val status: String,
    val reviewNote: String?,
    val reviewedBy: String?,
    val reviewedAt: LocalDateTime?,
    val resultingProjectId: String?,
    val resultingInstitutionProjectId: String?,
    val submittedAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class InstitutionProjectSplitView(
    val consultationFee: BigDecimal,
    val commissionRate: BigDecimal,
    val institutionRate: BigDecimal,
    val platformRate: BigDecimal,
    val doctorRate: BigDecimal
)

private data class ProjectRequestSnapshot(
    val id: String,
    val requestType: String,
    val doctorId: String,
    val institutionId: String? = null,
    val projectId: String? = null,
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    val tags: String? = null,
    val slogan: String = "",
    val detailContent: String? = null,
    val currency: String = CurrencyCode.DEFAULT_CODE,
    val coverImage: String = "",
    val images: String? = null,
    val salesCount: Int = 0,
    val referencePrice: BigDecimal? = null,
    val categoryTags: String? = null,
    val price: BigDecimal? = null,
    val originalPrice: BigDecimal? = null,
    val isActive: Boolean? = null,
    val consultationFee: BigDecimal? = null,
    val commissionRate: BigDecimal? = null,
    val institutionRate: BigDecimal? = null,
    val notes: String? = null
)

private data class NormalizedProjectRequestReview(
    val decision: String,
    val reviewNote: String?
)

private data class LockedPlatformProject(
    val id: String,
    val name: String,
    val category: String,
    val description: String?,
    val tags: String,
    val categoryTags: String,
    val coverImage: String,
    val images: String,
    val referencePrice: BigDecimal,
    val currency: CurrencyCode,
    val slogan: String,
    val detailContent: String?,
    val salesCount: Int
)

private data class EffectiveInstitutionProject(
    val name: String,
    val category: String,
    val description: String?,
    val tags: String?,
    val slogan: String,
    val detailContent: String?,
    val coverImage: String,
    val images: String?
)

private data class ProjectRequestTarget(
    val id: String,
    val requestType: String,
    val doctorId: String,
    val institutionId: String?,
    val projectId: String?,
    val name: String?,
    val category: String?,
    val description: String?,
    val tags: List<String>?,
    val slogan: String?,
    val detailContent: String?,
    val currency: CurrencyCode,
    val coverImage: String?,
    val images: List<String>?,
    val salesCount: Int,
    val referencePrice: BigDecimal?,
    val categoryTags: List<String>?,
    val price: BigDecimal?,
    val originalPrice: BigDecimal?,
    val isActive: Boolean?,
    val consultationFee: BigDecimal?,
    val commissionRate: BigDecimal?,
    val institutionRate: BigDecimal?,
    val notes: String?,
    val status: String
)

class ProfessionalProjectRequestNotFoundException(message: String) : RuntimeException(message)

class ProfessionalProjectRequestConflictException(message: String) : RuntimeException(message)
