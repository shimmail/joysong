package com.joysong.server.auth.service

import com.aliyun.dysmsapi20170525.Client
import com.aliyun.dysmsapi20170525.models.SendSmsRequest
import com.aliyun.teaopenapi.models.Config
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct

/**
 * 阿里云短信服务
 *
 * 使用前需要在阿里云控制台完成以下配置：
 * 1. 开通短信服务：https://dysms.console.aliyun.com
 * 2. 申请短信签名（如"娇颜颂"）
 * 3. 申请短信模板（如验证码模板：您的验证码为${code}，请勿泄露给他人）
 * 4. 获取 AccessKey ID 和 AccessKey Secret
 */
@Service
class AliyunSmsService(
    @Value("\${aliyun.sms.access-key-id:}") private val accessKeyId: String,
    @Value("\${aliyun.sms.access-key-secret:}") private val accessKeySecret: String,
    @Value("\${aliyun.sms.sign-name:}") private val signName: String,
    @Value("\${aliyun.sms.template-code:}") private val templateCode: String,
    @Value("\${aliyun.sms.enabled:false}") private val smsEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var client: Client? = null

    @PostConstruct
    fun init() {
        if (smsEnabled && accessKeyId.isNotBlank() && accessKeySecret.isNotBlank()) {
            try {
                val config = Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret)
                    .setEndpoint("dysmsapi.aliyuncs.com")
                client = Client(config)
                logger.info("阿里云短信服务初始化成功")
            } catch (e: Exception) {
                logger.error("阿里云短信服务初始化失败: {}", e.message, e)
            }
        } else {
            logger.warn("阿里云短信服务未启用，验证码将仅打印到日志。设置 aliyun.sms.enabled=true 以启用")
        }
    }

    /**
     * 短信服务是否已启用
     */
    fun isSmsEnabled(): Boolean = smsEnabled && client != null

    /**
     * 发送验证码短信
     * @param phone 手机号（如 13800138000）
     * @param code 6位验证码
     * @return true=发送成功, false=发送失败
     */
    fun sendVerificationCode(phone: String, code: String): Boolean {
        if (!smsEnabled || client == null) {
            logger.info("[SMS-Mock] 验证码已生成，手机号: {}", maskPhone(phone))
            return false
        }

        return try {
            val request = SendSmsRequest()
                .setPhoneNumbers(phone)
                .setSignName(signName)
                .setTemplateCode(templateCode)
                .setTemplateParam("{\"code\":\"$code\"}")

            val response = client!!.sendSms(request)
            val body = response.body

            if ("OK" == body.code) {
                logger.info("[SMS] 验证码发送成功: 手机号={}", maskPhone(phone))
                true
            } else {
                logger.error("[SMS] 验证码发送失败: 手机号={}, code={}, message={}", maskPhone(phone), body.code, body.message)
                false
            }
        } catch (e: Exception) {
            logger.error("[SMS] 验证码发送异常: 手机号={}, error={}", maskPhone(phone), e.message, e)
            false
        }
    }

    private fun maskPhone(phone: String): String = phone.take(5) + "******" + phone.takeLast(2)
}
