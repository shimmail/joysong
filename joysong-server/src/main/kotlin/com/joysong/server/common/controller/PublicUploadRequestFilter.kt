package com.joysong.server.common.controller

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class PublicUploadRequestFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI != "/api/upload" && !request.requestURI.startsWith("/api/upload/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)
            ?.takeIf { it.matches(SAFE_REQUEST_ID) }
            ?: UUID.randomUUID().toString()
        val previousRequestId = MDC.get(MDC_REQUEST_ID)
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)
        request.setAttribute(REQUEST_STARTED_ATTRIBUTE, System.nanoTime())
        response.setHeader(REQUEST_ID_HEADER, requestId)
        MDC.put(MDC_REQUEST_ID, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            if (previousRequestId == null) MDC.remove(MDC_REQUEST_ID) else MDC.put(MDC_REQUEST_ID, previousRequestId)
        }
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-ID"
        const val REQUEST_ID_ATTRIBUTE = "joysong.public-upload.request-id"
        const val REQUEST_STARTED_ATTRIBUTE = "joysong.public-upload.request-started-nanos"
        private const val MDC_REQUEST_ID = "requestId"
        private val SAFE_REQUEST_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}
