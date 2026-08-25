package com.joysong.server.notification.repository

import com.joysong.server.notification.entity.NotificationEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface NotificationUnreadCountSummary {
    val total: Long
    val activity: Long
}

interface NotificationRepository : JpaRepository<NotificationEntity, String> {
    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.userId = :userId
          AND n.deletedAt IS NULL
          AND (
              UPPER(TRIM(n.type)) <> 'DM_NEW'
              OR EXISTS (
                  SELECT conversation.id FROM DmConversationEntity conversation
                  WHERE conversation.id = n.targetId
                    AND (conversation.userAId = 'CS_ADMIN' OR conversation.userBId = 'CS_ADMIN')
              )
          )
        ORDER BY n.createdAt DESC
    """)
    fun findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
        @Param("userId") userId: String,
        pageable: Pageable
    ): List<NotificationEntity>

    @Query("""
        SELECT COUNT(n) FROM NotificationEntity n
        WHERE n.userId = :userId
          AND n.isRead = :isRead
          AND n.deletedAt IS NULL
          AND (
              UPPER(TRIM(n.type)) <> 'DM_NEW'
              OR EXISTS (
                  SELECT conversation.id FROM DmConversationEntity conversation
                  WHERE conversation.id = n.targetId
                    AND (conversation.userAId = 'CS_ADMIN' OR conversation.userBId = 'CS_ADMIN')
              )
          )
    """)
    fun countByUserIdAndIsReadAndDeletedAtIsNull(
        @Param("userId") userId: String,
        @Param("isRead") isRead: Boolean
    ): Long

    @Query("""
        SELECT COUNT(n) AS total,
               COALESCE(SUM(CASE
                   WHEN UPPER(TRIM(n.type)) IN :types THEN 1
                   ELSE 0
               END), 0) AS activity
        FROM NotificationEntity n
        WHERE n.userId = :userId
          AND n.isRead = false
          AND n.deletedAt IS NULL
          AND (
              UPPER(TRIM(n.type)) <> 'DM_NEW'
              OR EXISTS (
                  SELECT conversation.id FROM DmConversationEntity conversation
                  WHERE conversation.id = n.targetId
                    AND (conversation.userAId = 'CS_ADMIN' OR conversation.userBId = 'CS_ADMIN')
              )
          )
    """)
    fun summarizeUnreadByUserIdAndTypes(
        @Param("userId") userId: String,
        @Param("types") types: Set<String>
    ): NotificationUnreadCountSummary

    fun findByIdAndUserIdAndDeletedAtIsNull(id: String, userId: String): NotificationEntity?

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false AND n.deletedAt IS NULL")
    fun markAllAsReadByUserId(userId: String): Int

    // ========== 管理员查询 ==========

    /** 管理员分页查询：按 type + isRead 过滤（无关键词） */
    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.deletedAt IS NULL
          AND (:type IS NULL OR n.type = :type)
          AND (:isRead IS NULL OR n.isRead = :isRead)
        ORDER BY n.createdAt DESC
    """)
    fun adminFindAll(
        @Param("type") type: String?,
        @Param("isRead") isRead: Boolean?,
        pageable: Pageable
    ): Page<NotificationEntity>

    /** 管理员分页查询：按 type + isRead + 关键词过滤（关键词匹配用户昵称、通知标题、内容） */
    @Query(value = """
        SELECT n.* FROM notifications n
        LEFT JOIN users u ON n.user_id = u.id
        WHERE n.deleted_at IS NULL
          AND (:type IS NULL OR n.type = :type)
          AND (:isRead IS NULL OR n.is_read = :isRead)
          AND (:keyword IS NULL OR u.nickname LIKE CONCAT('%', :keyword, '%')
               OR n.title LIKE CONCAT('%', :keyword, '%')
               OR n.content LIKE CONCAT('%', :keyword, '%'))
        ORDER BY n.created_at DESC
    """, countQuery = """
        SELECT COUNT(*) FROM notifications n
        LEFT JOIN users u ON n.user_id = u.id
        WHERE n.deleted_at IS NULL
          AND (:type IS NULL OR n.type = :type)
          AND (:isRead IS NULL OR n.is_read = :isRead)
          AND (:keyword IS NULL OR u.nickname LIKE CONCAT('%', :keyword, '%')
               OR n.title LIKE CONCAT('%', :keyword, '%')
               OR n.content LIKE CONCAT('%', :keyword, '%'))
    """, nativeQuery = true)
    fun adminFindAllWithKeyword(
        @Param("type") type: String?,
        @Param("isRead") isRead: Boolean?,
        @Param("keyword") keyword: String?,
        pageable: Pageable
    ): Page<NotificationEntity>

    /** 管理员将所有未删除且未读的通知标记为已读 */
    @Modifying
    @Query("UPDATE NotificationEntity n SET n.isRead = true WHERE n.deletedAt IS NULL AND n.isRead = false")
    fun adminMarkAllAsRead(): Int
}
