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

    /** 查找包括已软删除在内的用户（绕过 @Where 过滤） */
    @Query(value = "SELECT * FROM users WHERE phone = :phone", nativeQuery = true)
    fun findByPhoneIncludeDeleted(@Param("phone") phone: String): Optional<UserEntity>

    /** 查找包括已软删除在内的用户（绕过 @Where 过滤） */
    @Query(value = "SELECT * FROM users WHERE email = :email", nativeQuery = true)
    fun findByEmailIncludeDeleted(@Param("email") email: String): Optional<UserEntity>

    /** 按ID查找包括已软删除在内的用户（绕过 @Where 过滤） */
    @Query(value = "SELECT * FROM users WHERE id = :id", nativeQuery = true)
    fun findByIdIncludingDeleted(@Param("id") id: String): UserEntity?

    /** 查找所有用户（包括已注销的） */
    @Query(value = "SELECT * FROM users ORDER BY created_at DESC", nativeQuery = true)
    fun findAllIncludingDeleted(): List<UserEntity>

    /** 按关键词搜索用户（昵称/手机号/邮箱），包括已注销的 */
    @Query(
        value = "SELECT * FROM users WHERE nickname LIKE CONCAT('%', :keyword, '%') OR phone LIKE CONCAT('%', :keyword, '%') OR email LIKE CONCAT('%', :keyword, '%') OR id = :keyword ORDER BY created_at DESC",
        nativeQuery = true
    )
    fun searchUsersIncludingDeleted(@Param("keyword") keyword: String): List<UserEntity>

    /** 统计所有用户数（包括已注销的） */
    @Query(value = "SELECT COUNT(*) FROM users", nativeQuery = true)
    fun countIncludingDeleted(): Long
}
