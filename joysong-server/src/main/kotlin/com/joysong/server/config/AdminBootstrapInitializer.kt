package com.joysong.server.config

import com.joysong.server.user.service.AdminAccountCommandService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(0)
class AdminBootstrapInitializer(
    private val adminAccountCommandService: AdminAccountCommandService,
    @Value("\${admin.bootstrap.phone:}") private val adminPhone: String,
    @Value("\${admin.bootstrap.password:}") private val adminPassword: String
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(AdminBootstrapInitializer::class.java)

    override fun run(args: Array<String>) {
        adminAccountCommandService.initializeBootstrapAdministrator(adminPhone, adminPassword)
        logger.info("Bootstrap administrator account verified for the configured phone number")
    }
}
