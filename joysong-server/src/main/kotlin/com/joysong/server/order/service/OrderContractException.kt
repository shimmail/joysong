package com.joysong.server.order.service

import org.springframework.http.HttpStatus

enum class OrderContractErrorCode {
    INVALID_CONSULTANT_ORDER_STAGE,
    CONSULTANT_ROLE_REQUIRED,
    CONSULTANT_ORDER_NOT_FOUND,
    ORDER_SERVICE_ACCESS_DENIED,
    ORDER_SERVICE_NOT_ACTIVE,
    ORDER_SERVICE_READ_ONLY
}

class OrderContractException(
    val status: HttpStatus,
    val errorCode: OrderContractErrorCode,
    message: String
) : RuntimeException(message) {
    companion object {
        fun invalidQuery() = OrderContractException(
            HttpStatus.BAD_REQUEST,
            OrderContractErrorCode.INVALID_CONSULTANT_ORDER_STAGE,
            "顾问订单查询参数不正确"
        )

        fun roleRequired() = OrderContractException(
            HttpStatus.FORBIDDEN,
            OrderContractErrorCode.CONSULTANT_ROLE_REQUIRED,
            "顾问身份无效"
        )

        fun consultantOrderNotFound() = OrderContractException(
            HttpStatus.NOT_FOUND,
            OrderContractErrorCode.CONSULTANT_ORDER_NOT_FOUND,
            "订单不存在"
        )

        fun serviceAccessDenied() = OrderContractException(
            HttpStatus.NOT_FOUND,
            OrderContractErrorCode.ORDER_SERVICE_ACCESS_DENIED,
            "订单会话不存在"
        )

        fun serviceNotActive() = OrderContractException(
            HttpStatus.CONFLICT,
            OrderContractErrorCode.ORDER_SERVICE_NOT_ACTIVE,
            "订单服务尚未激活"
        )

        fun serviceReadOnly() = OrderContractException(
            HttpStatus.CONFLICT,
            OrderContractErrorCode.ORDER_SERVICE_READ_ONLY,
            "当前订单会话只允许读取"
        )
    }
}
