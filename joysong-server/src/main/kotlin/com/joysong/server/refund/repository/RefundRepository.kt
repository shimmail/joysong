package com.joysong.server.refund.repository

import com.joysong.server.refund.entity.RefundEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable

interface RefundRepository : JpaRepository<RefundEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefundEntity r WHERE r.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): RefundEntity?

    /** 查询订单所有退款记录（按创建时间倒序） */
    fun findAllByOrderIdOrderByCreatedAtDesc(orderId: String): List<RefundEntity>

    /** 查询订单最新的一条退款记录 */
    fun findFirstByOrderIdOrderByCreatedAtDesc(orderId: String): RefundEntity?

    /** 查询订单中处于指定状态的退款记录 */
    fun findAllByOrderIdAndStatusIn(orderId: String, statuses: List<String>): List<RefundEntity>
    fun findTop50ByStatusOrderByUpdatedAtAsc(status: String): List<RefundEntity>

    @Query(
        "SELECT r FROM RefundEntity r WHERE r.status = :status " +
            "AND r.revenueReversalStatus = :reversalStatus AND r.id > :afterId ORDER BY r.id ASC"
    )
    fun findPendingRevenueReversalsAfter(
        @Param("status") status: String,
        @Param("reversalStatus") reversalStatus: String,
        @Param("afterId") afterId: String,
        pageable: Pageable
    ): List<RefundEntity>
}
