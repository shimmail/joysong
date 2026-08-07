package com.joysong.server.diary.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "diary_shares")
data class DiaryShareEntity(
    @Id val id: String,
    @Column(name = "diary_id", nullable = false) val diaryId: String,
    @Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "CHAR(64)") val tokenHash: String,
    @Column(name = "expires_at") val expiresAt: LocalDateTime? = null,
    @Column(name = "revoked_at") var revokedAt: LocalDateTime? = null,
    @Column(name = "created_at", insertable = false, updatable = false) val createdAt: LocalDateTime? = null,
    @Column(name = "last_access_at") var lastAccessAt: LocalDateTime? = null,
    @Column(name = "access_count", nullable = false) var accessCount: Int = 0
)
