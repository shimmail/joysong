package com.joysong.server.dm.repository

import com.joysong.server.dm.entity.DmConversationEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DmConversationRepository : JpaRepository<DmConversationEntity, String> {
    fun findByUserAIdOrUserBIdOrderByLastMessageAtDesc(userAId: String, userBId: String): List<DmConversationEntity>
    fun findByUserAIdAndUserBId(userAId: String, userBId: String): DmConversationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT conversation FROM DmConversationEntity conversation WHERE conversation.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): DmConversationEntity?
}
