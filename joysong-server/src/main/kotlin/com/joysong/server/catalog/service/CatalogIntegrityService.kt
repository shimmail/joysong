package com.joysong.server.catalog.service

import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.order.repository.OrderRepository
import org.springframework.stereotype.Service

/**
 * 目录主数据删除前的统一保护。
 * 历史订单、日记和评价保留其 ID/名称快照，因此只阻断仍会影响可售、可预约关系的删除。
 */
@Service
class CatalogIntegrityService(
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val doctorInstitutionService: DoctorInstitutionService,
    private val orderRepository: OrderRepository
) {
    private val unfinishedOrderStatuses = listOf("CANCELLED", "REFUNDED", "SETTLED", "COMPLETED")

    fun institutionDeletionBlocker(institutionId: String): String? = when {
        institutionProjectRepository.findByInstitutionId(institutionId).isNotEmpty() ->
            "该机构仍有关联机构项目，请先停用并删除机构项目"
        doctorInstitutionService.findByInstitutionId(institutionId).isNotEmpty() ->
            "该机构仍绑定医生，请先在医生管理中解除绑定或改设主机构"
        orderRepository.existsByInstitutionIdAndStatusNotIn(institutionId, unfinishedOrderStatuses) ->
            "该机构存在进行中的订单，不能删除"
        else -> null
    }

    fun projectDeletionBlocker(projectId: String): String? = when {
        institutionProjectRepository.findByProjectId(projectId).isNotEmpty() ->
            "该项目仍有关联机构项目，请先停用并删除机构项目"
        doctorProjectRepository.findByProjectId(projectId).isNotEmpty() ->
            "该项目仍绑定医生，请先解除医生项目绑定"
        orderRepository.existsByProjectIdAndStatusNotIn(projectId, unfinishedOrderStatuses) ->
            "该项目存在进行中的订单，不能删除"
        else -> null
    }

    fun doctorDeletionBlocker(doctorId: String): String? = when {
        doctorProjectRepository.findByDoctorId(doctorId).isNotEmpty() ->
            "该医生仍绑定机构项目或项目，请先解除项目绑定"
        orderRepository.existsByDoctorIdAndStatusNotIn(doctorId, unfinishedOrderStatuses) ->
            "该医生存在进行中的订单，不能删除"
        else -> null
    }
}
