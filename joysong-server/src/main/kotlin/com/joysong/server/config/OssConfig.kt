package com.joysong.server.config

import com.aliyun.oss.OSS
import com.aliyun.oss.ClientBuilderConfiguration
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.common.auth.DefaultCredentialProvider
import com.aliyun.oss.common.comm.Protocol
import com.aliyun.oss.common.comm.SignVersion
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

    @Value("\${oss.region}")
    lateinit var region: String

    @Value("\${oss.access-key-id}")
    private lateinit var accessKeyId: String

    @Value("\${oss.access-key-secret}")
    private lateinit var accessKeySecret: String

    @Value("\${oss.bucket-name}")
    lateinit var bucketName: String

    private var ossClient: OSS? = null

    private val logger = LoggerFactory.getLogger(OssConfig::class.java)

    @Bean
    fun ossClient(): OSS {
        logger.info(
            "Initializing OSS client for endpoint {}, region {}, bucket {}",
            endpoint,
            region,
            bucketName,
        )
        val configuration = ClientBuilderConfiguration().apply {
            setProtocol(Protocol.HTTPS)
            setSignatureVersion(SignVersion.V4)
        }
        val client = OSSClientBuilder.create()
            .endpoint(endpoint)
            .credentialsProvider(DefaultCredentialProvider(accessKeyId, accessKeySecret))
            .clientConfiguration(configuration)
            .region(region)
            .build()
        ossClient = client
        return client
    }

    @PreDestroy
    fun shutdown() {
        ossClient?.shutdown()
    }
}
