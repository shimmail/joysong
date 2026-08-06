package com.joysong.server.report.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.admin.entity.vo.ReportAdminVo
import com.joysong.server.comment.repository.CommentRepository
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.report.entity.ReportEntity
import com.joysong.server.report.entity.dto.ReportRequest
import com.joysong.server.report.entity.dto.ReportResponse
import com.joysong.server.report.repository.ReportRepository
import com.joysong.server.review.repository.ReviewRepository
import com.joysong.server.user.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class ReportService(
    private val reportRepository: ReportRepository,
    private val diaryRepository: DiaryRepository,
    private val reviewRepository: ReviewRepository,
    private val commentRepository: CommentRepository,
    private val userRepository: UserRepository,
    private val objectMapper: ObjectMapper
) {

    fun submitReport(userId: String, request: ReportRequest): Any {
        // 防重复举报
        if (reportRepository.existsByUserIdAndTargetTypeAndTargetId(userId, request.targetType, request.targetId)) {
            return mapOf("error" to "您已举报过该内容", "code" to 409)
        }

        // 验证 targetType
        if (request.targetType !in listOf("diary", "review", "comment", "user")) {
            return mapOf("error" to "不支持的举报类型", "code" to 400)
        }

        // 获取举报目标的内容快照（使用 ObjectMapper 序列化，防止 JSON 注入）
        val targetSummaryJson: String? = when (request.targetType) {
            "user" -> userRepository.findById(request.targetId).map { u ->
                objectMapper.writeValueAsString(mapOf(
                    "nickname" to u.nickname,
                    "bio" to (u.bio ?: "").take(100)
                ))
            }.orElse(null)
            "diary" -> diaryRepository.findById(request.targetId).map { d ->
                objectMapper.writeValueAsString(mapOf(
                    "title" to d.title,
                    "content" to d.content.take(100)
                ))
            }.orElse(null)
            "review" -> reviewRepository.findById(request.targetId).map { r ->
                objectMapper.writeValueAsString(mapOf(
                    "content" to r.content.take(100),
                    "rating" to r.rating
                ))
            }.orElse(null)
            "comment" -> commentRepository.findById(request.targetId).map { c ->
                objectMapper.writeValueAsString(mapOf(
                    "content" to c.content.take(200)
                ))
            }.orElse(null)
            else -> null
        }
        if (targetSummaryJson == null) {
            return mapOf("error" to "举报目标不存在", "code" to 404)
        }

        val report = ReportEntity(
            userId = userId,
            targetType = request.targetType,
            targetId = request.targetId,
            reason = request.reason,
            description = request.description,
            targetSummary = targetSummaryJson
        )
        val saved = reportRepository.save(report)
        return saved.toResponse()
    }

    fun checkReported(userId: String, targetType: String, targetId: String): Boolean {
        return reportRepository.existsByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId)
    }

    private fun ReportEntity.toResponse() = ReportResponse(
        id = id,
        userId = userId,
        targetType = targetType,
        targetId = targetId,
        reason = reason,
        description = description,
        status = status,
        createdAt = createdAt.toString()
    )

    // ---- Admin 方法 ----

    fun adminListReports(status: String?, deleted: Boolean): List<ReportAdminVo> {
        val reports = if (deleted) {
            reportRepository.findAllByDeletedTrueOrderByCreatedAtDesc()
        } else if (status != null) {
            reportRepository.findByStatusAndDeletedFalse(status)
        } else {
            reportRepository.findAllByDeletedFalseOrderByCreatedAtDesc()
        }
        return reports.map { report ->
            val targetSummary = resolveTargetSummary(report)
            ReportAdminVo(
                id = report.id,
                userId = report.userId,
                targetType = report.targetType,
                targetId = report.targetId,
                reason = report.reason,
                description = report.description,
                status = report.status,
                createdAt = report.createdAt,
                deleted = report.deleted,
                targetSummary = targetSummary
            )
        }
    }

    fun adminUpdateStatus(id: String, status: String): Pair<Boolean, String> {
        if (status !in listOf("pending", "resolved", "ignored")) return false to "无效的状态值"
        val report = reportRepository.findById(id).orElse(null) ?: return false to "举报记录不存在"
        reportRepository.save(report.copy(status = status))
        return true to "success"
    }

    fun adminSoftDelete(id: String): Pair<Boolean, String> {
        val report = reportRepository.findById(id).orElse(null) ?: return false to "举报记录不存在"
        reportRepository.save(report.copy(deleted = true))
        return true to "success"
    }

    fun adminRestore(id: String): Pair<Boolean, String> {
        val report = reportRepository.findById(id).orElse(null) ?: return false to "举报记录不存在"
        reportRepository.save(report.copy(deleted = false))
        return true to "success"
    }

    fun adminDeleteTarget(targetType: String, targetId: String) {
        when (targetType) {
            "diary" -> diaryRepository.deleteById(targetId)
            "review" -> reviewRepository.deleteById(targetId)
            "comment" -> commentRepository.deleteById(targetId)
        }
        reportRepository.findByTargetTypeAndTargetId(targetType, targetId).forEach { report ->
            reportRepository.save(report.copy(status = "resolved"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveTargetSummary(report: ReportEntity): Map<String, Any?>? {
        return when (report.targetType) {
            "user" -> userRepository.findById(report.targetId).map { u ->
                mapOf<String, Any?>("nickname" to u.nickname, "bio" to u.bio.take(100))
            }.orElse(null)
                ?: report.targetSummary?.let { runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() }
            "diary" -> diaryRepository.findById(report.targetId).map { d ->
                mapOf<String, Any?>("title" to d.title, "content" to d.content.take(100))
            }.orElse(null)
                ?: report.targetSummary?.let { runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() }
            "review" -> reviewRepository.findById(report.targetId).map { r ->
                mapOf<String, Any?>("content" to r.content.take(100), "rating" to r.rating)
            }.orElse(null)
                ?: report.targetSummary?.let { runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() }
            "comment" -> commentRepository.findById(report.targetId).map { c ->
                mapOf<String, Any?>("content" to c.content.take(200))
            }.orElse(null)
                ?: report.targetSummary?.let { runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() }
            else -> null
        }
    }
}
