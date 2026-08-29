package com.joysong.server.user.repository

import com.joysong.server.user.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface UserRepository : JpaRepository<UserEntity, String> {
    fun findByPhone(phone: String): Optional<UserEntity>
    fun findByEmail(email: String): Optional<UserEntity>
    fun existsByPhone(phone: String): Boolean
    fun existsByEmail(email: String): Boolean

    @Query(value = "SELECT * FROM users WHERE phone = :phone FOR UPDATE", nativeQuery = true)
    fun findByPhoneForUpdate(@Param("phone") phone: String): UserEntity?

    @Query(value = "SELECT * FROM users WHERE id = :id", nativeQuery = true)
    fun findByIdAnyState(@Param("id") id: String): UserEntity?

    @Query(value = "SELECT * FROM users WHERE id = :id", nativeQuery = true)
    fun findByIdIncludingDeleted(@Param("id") id: String): UserEntity?

    @Query(value = "SELECT * FROM users WHERE id = :id FOR UPDATE", nativeQuery = true)
    fun findByIdForUpdate(@Param("id") id: String): UserEntity?

    /**
     * 必须在锁定 admin_account_guard 后调用；FOR UPDATE 强制 MySQL RR 使用当前读，
     * 避免复用外层事务先前建立的旧一致性快照。
     */
    @Query(
        value = """
            SELECT COUNT(*)
            FROM users FORCE INDEX (idx_users_admin_lifecycle)
            WHERE role = 'ADMIN'
              AND account_state = 'ACTIVE'
              AND phone REGEXP '^1[0-9]{10}$'
              AND TRIM(password_hash) <> ''
            FOR UPDATE
        """,
        nativeQuery = true,
    )
    fun countAvailableAdministrators(): Long

    @Query(
        value = """
            SELECT COUNT(*)
            FROM users
            WHERE role = 'ADMIN'
              AND account_state <> 'ERASED'
            FOR UPDATE
        """,
        nativeQuery = true,
    )
    fun countNonErasedAdministratorsForUpdate(): Long

    @Query(
        value = """
            SELECT COUNT(*)
            FROM users
            WHERE account_state <> 'ERASED'
              AND phone IN (:barePhone, :e164Phone)
            FOR UPDATE
        """,
        nativeQuery = true,
    )
    fun countNonErasedPhoneOwnersForUpdate(
        @Param("barePhone") barePhone: String,
        @Param("e164Phone") e164Phone: String,
    ): Long

    @Query(value = "SELECT * FROM users ORDER BY created_at DESC", nativeQuery = true)
    fun findAllAnyState(): List<UserEntity>

    @Query(
        value = "SELECT * FROM users WHERE nickname LIKE CONCAT('%', :keyword, '%') OR phone LIKE CONCAT('%', :keyword, '%') OR email LIKE CONCAT('%', :keyword, '%') OR id = :keyword ORDER BY created_at DESC",
        nativeQuery = true
    )
    fun searchUsersAnyState(@Param("keyword") keyword: String): List<UserEntity>

    @Query(value = "SELECT COUNT(*) FROM users", nativeQuery = true)
    fun countAnyState(): Long
}
