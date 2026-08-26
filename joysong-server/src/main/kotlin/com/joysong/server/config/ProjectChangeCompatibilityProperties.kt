package com.joysong.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("app.project-change")
data class ProjectChangeCompatibilityProperties(
    var v1ProfileUpdateEnabled: Boolean = true
)

@Configuration
@EnableConfigurationProperties(ProjectChangeCompatibilityProperties::class)
class ProjectChangeCompatibilityConfiguration
