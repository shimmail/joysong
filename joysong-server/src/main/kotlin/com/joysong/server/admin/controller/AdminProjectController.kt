package com.joysong.server.admin.controller

import com.joysong.server.admin.entity.dto.ProjectRequest
import com.joysong.server.common.BaseResponse
import com.joysong.server.project.service.ProjectService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin")
class AdminProjectController(
    private val projectService: ProjectService
) {

    @GetMapping("/projects")
    fun listProjects(@RequestParam(required = false) keyword: String?): BaseResponse<*> {
        return BaseResponse.success(projectService.listAdminProjects(keyword))
    }

    @PostMapping("/projects")
    fun createProject(@RequestBody request: ProjectRequest): BaseResponse<*> {
        return BaseResponse.success(projectService.adminCreateProject(request))
    }

    @PutMapping("/projects/{id}")
    fun updateProject(@PathVariable id: String, @RequestBody request: ProjectRequest): BaseResponse<*> {
        val result = projectService.adminUpdateProject(id, request)
            ?: return BaseResponse.error<Any>("项目不存在")
        return BaseResponse.success(result)
    }

    @DeleteMapping("/projects/{id}")
    fun deleteProject(@PathVariable id: String): BaseResponse<*> {
        projectService.deleteByIdIfSafe(id)?.let { message ->
            return BaseResponse.error<Any>(message, 409)
        }
        return BaseResponse.success(null)
    }
}
