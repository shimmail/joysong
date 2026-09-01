package com.joysong.server.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.aliyun.oss.OSSClient
import com.aliyun.oss.ClientBuilderConfiguration
import com.aliyun.oss.common.comm.Protocol
import com.aliyun.oss.common.comm.SignVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.test.util.ReflectionTestUtils
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date

class OssConfigTest {

    @Test
    fun `client uses HTTPS signature v4 and configured region without a cloud request`() {
        val config = configuredOss()
        val client = assertInstanceOf(OSSClient::class.java, config.ossClient())

        try {
            assertEquals("https", client.endpoint.scheme)
            assertInstanceOf(ClientBuilderConfiguration::class.java, client.clientConfiguration)
            assertEquals(Protocol.HTTPS, client.clientConfiguration.protocol)
            assertEquals(SignVersion.V4, client.clientConfiguration.signatureVersion)

            val url = client.generatePresignedUrl(
                "demo-bucket",
                "probe.txt",
                Date.from(Instant.now().plusSeconds(300)),
            )
            val decodedQuery = URLDecoder.decode(url.query, StandardCharsets.UTF_8)

            assertEquals("https", url.protocol)
            assertTrue(decodedQuery.contains("/cn-hangzhou/oss/aliyun_v4_request"), decodedQuery)
        } finally {
            config.shutdown()
        }
    }

    @Test
    fun `initialization log includes only non-secret OSS deployment coordinates`() {
        val config = configuredOss()
        val logger = LoggerFactory.getLogger(OssConfig::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            config.ossClient()
            val messages = appender.list.map { it.formattedMessage }

            assertEquals(
                listOf(
                    "Initializing OSS client for endpoint " +
                        "https://oss-cn-hangzhou-internal.aliyuncs.com, region cn-hangzhou, bucket demo-bucket",
                ),
                messages,
            )
            assertFalse(messages.any { it.contains("test-access-key-id") })
            assertFalse(messages.any { it.contains("test-access-key-secret") })
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            config.shutdown()
        }
    }

    private fun configuredOss() = OssConfig().also { config ->
        ReflectionTestUtils.setField(config, "endpoint", "https://oss-cn-hangzhou-internal.aliyuncs.com")
        ReflectionTestUtils.setField(config, "region", "cn-hangzhou")
        ReflectionTestUtils.setField(config, "bucketName", "demo-bucket")
        ReflectionTestUtils.setField(config, "accessKeyId", "test-access-key-id")
        ReflectionTestUtils.setField(config, "accessKeySecret", "test-access-key-secret")
    }
}
