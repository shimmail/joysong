package com.joysong.server.discover.service

import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.discover.dto.InstitutionProjectWithProject
import com.joysong.server.discover.dto.DoctorResponse
import com.joysong.server.discover.dto.toResponse
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.stereotype.Service

@Service
class DiscoverService(
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val projectRepository: ProjectRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val doctorRepository: DoctorRepository,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver
) {

    /**
     * 获取某机构下的项目列表，批量查询 Project 避免 N+1
     */
    fun getInstitutionProjectsWithProject(institutionId: String): List<InstitutionProjectWithProject> {
        val institutionProjects = institutionProjectRepository.findByInstitutionId(institutionId).filter { it.isActive }
        if (institutionProjects.isEmpty()) return emptyList()

        // 批量查询所有关联的 Project
        val projectIds = institutionProjects.map { it.projectId }.distinct()
        val projectMap: Map<String, ProjectEntity> = projectRepository.findAllById(projectIds)
            .associateBy { it.id }

        return institutionProjects.mapNotNull { ip ->
            val project = projectMap[ip.projectId] ?: return@mapNotNull null
            InstitutionProjectWithProject(
                institutionProject = ip.toResponse(),
                project = institutionProjectDetailResolver.resolve(ip, project).toResponse()
            )
        }
    }

    /**
     * 获取某机构项目关联的医生列表，批量查询 Doctor 避免 N+1
     */
    fun getDoctorsByInstitutionProject(institutionProjectId: String): List<DoctorResponse> {
        val institutionProject = institutionProjectRepository.findById(institutionProjectId).orElse(null)
        if (institutionProject?.isActive != true) return emptyList()
        val doctorProjects = doctorProjectRepository.findByInstitutionProjectId(institutionProjectId)
        if (doctorProjects.isEmpty()) return emptyList()

        // 批量查询所有关联的 Doctor
        val doctorIds = doctorProjects.map { it.doctorId }.distinct()
        val doctorMap: Map<String, DoctorEntity> = doctorRepository.findAllById(doctorIds)
            .associateBy { it.id }

        return doctorProjects.mapNotNull { dp -> doctorMap[dp.doctorId]?.toResponse() }
    }
}
