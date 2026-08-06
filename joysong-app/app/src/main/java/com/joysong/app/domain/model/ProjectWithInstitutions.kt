package com.joysong.app.domain.model

data class ProjectWithInstitutions(
    val project: Project,
    val institutionProjects: List<InstitutionProjectItem> = emptyList()
)
