package com.joysong.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

@Configuration
class CorsConfig {

    @Value("\${cors.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private lateinit var allowedOrigins: String

    @Bean
    fun corsFilter(): CorsFilter {
        val origins = allowedOrigins.split(",").map { it.trim() }.filter { it.isNotBlank() }
        require(origins.isNotEmpty() && origins.none { it == "*" }) {
            "CORS_ALLOWED_ORIGINS must contain explicit trusted origins"
        }
        val config = CorsConfiguration().apply {
            allowedOrigins = origins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "X-Requested-With")
            exposedHeaders = listOf(
                "Retry-After",
                "X-Total-Count",
                "X-Offset",
                "X-Limit",
                "X-Has-More"
            )
            allowCredentials = true
            maxAge = 3600L
        }
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return CorsFilter(source)
    }
}
