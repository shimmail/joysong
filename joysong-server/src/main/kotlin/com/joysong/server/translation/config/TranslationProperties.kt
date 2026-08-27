package com.joysong.server.translation.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("translation")
data class TranslationProperties(
    var provider: String = "qwen",
    var apiKey: String = "",
    var baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    var model: String = "qwen3.7-flash"
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TranslationProperties::class)
class TranslationConfiguration
