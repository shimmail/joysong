package com.joysong.server.config

import com.aliyun.oss.OSS
import com.aliyun.oss.OSSClientBuilder
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["oss.enabled"], havingValue = "true", matchIfMissing = false)
class OssConfig {

    @Value("\${oss.endpoint}")
    lateinit var endpoint: String

    @Value("\${oss.access-key-id}")
    private lateinit var accessKeyId: String

    @Value("\${oss.access-key-secret}")
    private lateinit var accessKeySecret: String

    private var ossClient: OSS? = null

    private val logger = LoggerFactory.getLogger(OssConfig::class.java)

    @Bean
    fun ossClient(): OSS {
        logger.info("Initializing OSS client for endpoint {}", endpoint)
        val client = OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret)
        ossClient = client
        return client
    }

    @Value("\${oss.bucket-name}")
    lateinit var bucketName: String

    @PreDestroy
    fun shutdown() {
        ossClient?.shutdown()
    }
}
