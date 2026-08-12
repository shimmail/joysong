package com.joysong.server.order.repository

import com.joysong.server.order.entity.OrderEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime

interface OrderRepository : JpaRepository<OrderEntity, String> {
    @Query("""
        SELECT o FROM OrderEntity o
        WHERE (:doctorId IS NULL OR o.doctorId = :doctorId)
          AND (:status IS NULL OR o.status = :status)
        ORDER BY o.createdAt DESC, o.id DESC
    """)
    fun findManagementOrders(
        @Param("doctorId") doctorId: String?,
        @Param("status") status: String?,
        pageable: Pageable
    ): Page<OrderEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderEntity o WHERE o.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): OrderEntity?

    fun findByUserIdOrderByCreatedAtDesc(userId: String): List<OrderEntity>
    fun findByUserIdAndStatusOrderByCreatedAtDesc(userId: String, status: String): List<OrderEntity>
    fun findByStatusAndCreatedAtBefore(status: String, createdAt: LocalDateTime): List<OrderEntity>
    fun findByStatusAndCompletedAtBefore(status: String, completedAt: LocalDateTime): List<OrderEntity>
    fun findByStatusAndVerifiedAtBefore(status: String, verifiedAt: LocalDateTime): List<OrderEntity>
    fun findByStatusAndPaymentTimeBefore(status: String, paymentTime: LocalDateTime): List<OrderEntity>
    fun findByStatus(status: String): List<OrderEntity>
    fun existsByInstitutionProjectIdAndStatusNotIn(institutionProjectId: String, statuses: Collection<String>): Boolean
    fun existsByInstitutionIdAndStatusNotIn(institutionId: String, statuses: Collection<String>): Boolean
    fun existsByProjectIdAndStatusNotIn(projectId: String, statuses: Collection<String>): Boolean
    fun existsByDoctorIdAndStatusNotIn(doctorId: String, statuses: Collection<String>): Boolean

    /** 管理员查询所有订单（包含软删除），绕过 @Where 过滤 */
    @Query(value = "SELECT * FROM orders ORDER BY created_at DESC", nativeQuery = true)
    fun findAllIncludeDeleted(): List<OrderEntity>

    /** 管理员查询指定用户订单（包含软删除），绕过 @Where 过滤 */
    @Query(value = "SELECT * FROM orders WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    fun findByUserIdIncludeDeleted(userId: String): List<OrderEntity>

    /** 管理员根据ID查询订单（包含软删除），绕过 @Where 过滤 */
    @Query(value = "SELECT * FROM orders WHERE id = :id", nativeQuery = true)
    fun findByIdIncludeDeleted(id: String): OrderEntity?
}
