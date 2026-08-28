package com.joysong.server.user.entity

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "users")
data class UserEntity(
    @Id val id: String,
    @Column(unique = true, nullable = true) val phone: String? = null,
    @Column(unique = true, nullable = true) val email: String? = null,
    @get:JsonIgnore
    @Column(name = "password_hash", nullable = false) val passwordHash: String,
    val nickname: String = "",
    val avatar: String = "",
    val gender: String = "",  // MALE, FEMALE, OTHER, 或空字符串
    val city: String = "",
    val bio: String = "",
    val birthday: LocalDate? = null,
    val role: String = "USER",
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null,
    @Column(name = "credentials_updated_at") val credentialsUpdatedAt: LocalDateTime = LocalDateTime.now(),
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_state", nullable = false)
    val accountState: AccountState = AccountState.ACTIVE,
    @get:JsonIgnore
    @Column(name = "erased_at") val erasedAt: LocalDateTime? = null,
    @get:JsonIgnore
    @Column(name = "erased_phone_digest", columnDefinition = "CHAR(64)", length = 64)
    val erasedPhoneDigest: String? = null,
    @get:JsonIgnore
    @Column(name = "erased_email_digest", columnDefinition = "CHAR(64)", length = 64)
    val erasedEmailDigest: String? = null,
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null
)
