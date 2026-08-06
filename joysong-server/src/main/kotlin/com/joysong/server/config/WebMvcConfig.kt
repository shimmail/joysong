package com.joysong.server.config

import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Path

@Configuration
class WebMvcConfig(
    @Value("\${upload.local-dir:./data/uploads}") private val uploadDirectory: String
) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // 开发环境由 Spring 提供图片；生产环境建议由 Nginx 直接映射相同目录。
        val resourceLocation = Path.of(uploadDirectory).toAbsolutePath().normalize().toUri().toString()
        registry.addResourceHandler("/images/**")
            .addResourceLocations(resourceLocation)
    }
}
