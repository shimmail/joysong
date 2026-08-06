package com.joysong.server.dm.repository

import com.joysong.server.dm.entity.DmConversationEntity
import org.springframework.data.jpa.repository.JpaRepository

interface DmConversationRepository : JpaRepository<DmConversationEntity, String> {
    fun findByUserAIdOrUserBIdOrderByLastMessageAtDesc(userAId: String, userBId: String): List<DmConversationEntity>
    fun findByUserAIdAndUserBId(userAId: String, userBId: String): DmConversationEntity?
}
