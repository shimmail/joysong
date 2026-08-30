package com.joysong.server.refund.service

import com.joysong.server.common.privatefile.PrivateFilePolicy
import com.joysong.server.common.privatefile.PrivateFileStorageService
import com.joysong.server.refund.dto.RefundEvidenceFileResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Path

data class RefundEvidenceFileContent(
    val path: Path,
    val originalName: String,
    val contentType: String,
)

@Service
class RefundEvidenceFileService(
    private val jdbcTemplate: JdbcTemplate,
    private val privateFileStorageService: PrivateFileStorageService,
) {
    private val refundEvidencePolicy = PrivateFilePolicy(
        allowedContentTypes = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "application/pdf" to "pdf",
        ),
    )

    @Transactional
    fun storeForRefund(
        refundId: String,
        userId: String,
        files: List<MultipartFile>,
    ): List<RefundEvidenceFileResponse> {
        require(files.size <= MAX_FILES) { "退款凭证最多上传 5 个文件" }
        return files.mapIndexed { position, file ->
            val stored = privateFileStorageService.store(
                ownerUserId = userId,
                purpose = REFUND_EVIDENCE,
                file = file,
                policy = refundEvidencePolicy,
                assetType = REFUND_EVIDENCE,
            )
            jdbcTemplate.update(
                "INSERT INTO refund_evidence_files (file_id, refund_id, position) VALUES (?, ?, ?)",
                stored.fileId,
                refundId,
                position,
            )
            RefundEvidenceFileResponse(
                fileId = stored.fileId,
                originalName = stored.originalName,
                contentType = stored.contentType,
                sizeBytes = stored.sizeBytes,
                position = position,
            )
        }
    }

    fun listForRefund(refundId: String): List<RefundEvidenceFileResponse> = jdbcTemplate.query(
        """
        SELECT ref.file_id, pf.original_name, pf.content_type, pf.size_bytes, ref.position
        FROM refund_evidence_files ref
        JOIN private_files pf ON pf.id = ref.file_id
        WHERE ref.refund_id = ?
          AND pf.purpose = 'REFUND_EVIDENCE'
          AND pf.status = 'ACTIVE'
          AND pf.deleted_at IS NULL
        ORDER BY ref.position ASC
        """.trimIndent(),
        { rs, _ ->
            RefundEvidenceFileResponse(
                fileId = rs.getString("file_id"),
                originalName = rs.getString("original_name").orEmpty(),
                contentType = rs.getString("content_type"),
                sizeBytes = rs.getLong("size_bytes"),
                position = rs.getInt("position"),
            )
        },
        refundId,
    )

    fun loadContentForAdmin(refundId: String, fileId: String): RefundEvidenceFileContent {
        val associated = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM refund_evidence_files ref
            JOIN private_files pf ON pf.id = ref.file_id
            WHERE ref.refund_id = ?
              AND ref.file_id = ?
              AND pf.purpose = 'REFUND_EVIDENCE'
              AND pf.status = 'ACTIVE'
              AND pf.deleted_at IS NULL
            """.trimIndent(),
            Int::class.java,
            refundId,
            fileId,
        ) ?: 0
        if (associated == 0) throw IllegalArgumentException(REFUND_EVIDENCE_NOT_FOUND)
        val stored = try {
            privateFileStorageService.loadActive(fileId)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(REFUND_EVIDENCE_NOT_FOUND)
        }
        return RefundEvidenceFileContent(
            path = stored.path,
            originalName = stored.originalName,
            contentType = stored.contentType,
        )
    }

    companion object {
        private const val MAX_FILES = 5
        private const val REFUND_EVIDENCE = "REFUND_EVIDENCE"
        private const val REFUND_EVIDENCE_NOT_FOUND = "REFUND_EVIDENCE_NOT_FOUND"
    }
}
