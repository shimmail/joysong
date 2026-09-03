package com.joysong.server.config

import com.aliyun.credentials.Client as AlibabaCredentialsClient
import com.aliyun.credentials.models.Config as AlibabaCredentialsConfig
import com.aliyun.oss.OSS
import com.aliyun.oss.ClientBuilderConfiguration
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.common.auth.Credentials
import com.aliyun.oss.common.auth.CredentialsProvider
import com.aliyun.oss.common.auth.DefaultCredentialProvider
import com.aliyun.oss.common.auth.DefaultCredentials
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

    @Value("\${oss.credential-mode:static}")
    lateinit var credentialMode: String

    @Value("\${oss.ecs-ram-role-name:}")
    lateinit var ecsRamRoleName: String

    @Value("\${oss.access-key-id}")
    private lateinit var accessKeyId: String

    @Value("\${oss.access-key-secret}")
    private lateinit var accessKeySecret: String

    @Value("\${oss.bucket-name}")
    lateinit var bucketName: String

    @Value("\${oss.connection-timeout-ms:5000}")
    var connectionTimeoutMs: Int = 5_000

    @Value("\${oss.socket-timeout-ms:30000}")
    var socketTimeoutMs: Int = 30_000

    @Value("\${oss.request-timeout-ms:60000}")
    var requestTimeoutMs: Int = 60_000

    @Value("\${oss.max-error-retry:1}")
    var maxErrorRetry: Int = 1

    @Value("\${oss.max-connections:32}")
    var maxConnections: Int = 32

    private var ossClient: OSS? = null

    private val logger = LoggerFactory.getLogger(OssConfig::class.java)

    @Bean
    fun ossClient(): OSS {
        val normalizedEndpoint = endpoint.trim()
        val normalizedRegion = region.trim().lowercase()
        logger.info(
            "Initializing OSS client for endpoint {}, region {}, credential mode {}",
            normalizedEndpoint,
            normalizedRegion,
            credentialMode.trim(),
        )
        val configuration = ClientBuilderConfiguration().apply {
            setProtocol(Protocol.HTTPS)
            setSignatureVersion(SignVersion.V4)
            setConnectionTimeout(connectionTimeoutMs.validatedPositive("oss.connection-timeout-ms"))
            setSocketTimeout(socketTimeoutMs.validatedPositive("oss.socket-timeout-ms"))
            setRequestTimeout(requestTimeoutMs.validatedPositive("oss.request-timeout-ms"))
            setRequestTimeoutEnabled(true)
            setMaxErrorRetry(
                this@OssConfig.maxErrorRetry.validatedNonNegative("oss.max-error-retry"),
            )
            setMaxConnections(
                this@OssConfig.maxConnections.validatedPositive("oss.max-connections"),
            )
        }
        val client = OSSClientBuilder.create()
            .endpoint(normalizedEndpoint)
            .credentialsProvider(credentialsProvider())
            .clientConfiguration(configuration)
            .region(normalizedRegion)
            .build()
        ossClient = client
        return client
    }

    internal fun credentialsProvider(): CredentialsProvider = when (credentialMode.trim().lowercase()) {
        CREDENTIAL_MODE_STATIC -> DefaultCredentialProvider(accessKeyId, accessKeySecret)
        CREDENTIAL_MODE_ECS_RAM_ROLE -> EcsRamRoleOssCredentialsProvider(ecsRamRoleName.trim())
        else -> throw IllegalArgumentException(
            "Unsupported OSS credential mode. Use $CREDENTIAL_MODE_STATIC or $CREDENTIAL_MODE_ECS_RAM_ROLE.",
        )
    }

    @PreDestroy
    fun shutdown() {
        ossClient?.shutdown()
    }

    private companion object {
        const val CREDENTIAL_MODE_STATIC = "static"
        const val CREDENTIAL_MODE_ECS_RAM_ROLE = "ecs-ram-role"
    }

    private fun Int.validatedPositive(property: String): Int = also {
        require(it > 0) { "$property must be positive" }
    }

    private fun Int.validatedNonNegative(property: String): Int = also {
        require(it >= 0) { "$property must not be negative" }
    }
}

internal class EcsRamRoleOssCredentialsProvider(
    private val credentialSupplier: () -> com.aliyun.credentials.models.CredentialModel,
) : CredentialsProvider {
    constructor(roleName: String) : this(
        AlibabaCredentialsClient(
            AlibabaCredentialsConfig()
                .setType("ecs_ram_role")
                .setRoleName(roleName),
        )::getCredential,
    )

    override fun getCredentials(): Credentials {
        val credential = credentialSupplier()
        return DefaultCredentials(
            credential.accessKeyId,
            credential.accessKeySecret,
            credential.securityToken,
        )
    }

    override fun setCredentials(credentials: Credentials) = Unit
}
