package com.joysong.server.agent.repository

import com.joysong.server.agent.entity.AgentAssessmentEntity
import com.joysong.server.agent.entity.AgentPlanEntity
import com.joysong.server.agent.entity.AgentPlanItemEntity
import com.joysong.server.agent.entity.AgentSafetyEventEntity
import com.joysong.server.agent.entity.AgentToolAuditEntity
import com.joysong.server.agent.entity.AgentUserProfileEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface AgentUserProfileRepository : JpaRepository<AgentUserProfileEntity, String> {
    fun findByUserId(userId: String): AgentUserProfileEntity?
}

interface AgentAssessmentRepository : JpaRepository<AgentAssessmentEntity, String> {
    fun findByIdAndUserId(id: String, userId: String): AgentAssessmentEntity?
}

interface AgentPlanRepository : JpaRepository<AgentPlanEntity, String> {
    fun findByIdAndUserId(id: String, userId: String): AgentPlanEntity?
    fun findByUserIdOrderByVersionDesc(userId: String): List<AgentPlanEntity>
    fun countByUserId(userId: String): Long
}

interface AgentPlanItemRepository : JpaRepository<AgentPlanItemEntity, String> {
    fun findByPlanIdOrderBySortOrderAsc(planId: String): List<AgentPlanItemEntity>
}

interface AgentSafetyEventRepository : JpaRepository<AgentSafetyEventEntity, String>

@Repository
class AgentToolAuditRepository {
    fun save(audit: AgentToolAuditEntity): AgentToolAuditEntity = audit
    fun findByUserIdOrderByCreatedAtDesc(userId: String): List<AgentToolAuditEntity> = emptyList()
    fun findBySessionIdOrderByCreatedAtDesc(sessionId: String): List<AgentToolAuditEntity> = emptyList()
}
