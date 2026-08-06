package com.joysong.server.order.dto

/**
 * 订单状态枚举
 * 定义订单生命周期中所有合法状态及状态转换规则
 *
 * @author joysong
 * @since 2026-07-30
 */
enum class OrderStatusEnum(val value: String) {
    /** 等待支付面诊金 */
    PENDING_PAYMENT("PENDING_PAYMENT"),
    /** 面诊金已付，等待到店 */
    CONSULTATION_PAID("CONSULTATION_PAID"),
    /** 已到店核验 */
    VERIFIED("VERIFIED"),
    /** 尾款已付，全款已付 */
    BALANCE_PAID("BALANCE_PAID"),
    /** 机构申请完成，等待用户确认 */
    PENDING_COMPLETION("PENDING_COMPLETION"),
    /** 项目已完成 */
    COMPLETED("COMPLETED"),
    /** 待结算，30天倒计时 */
    PENDING_SETTLEMENT("PENDING_SETTLEMENT"),
    /** 已结算分账 */
    SETTLED("SETTLED"),
    /** 纠纷调解中 */
    DISPUTE_MEDIATION("DISPUTE_MEDIATION"),
    /** 已取消 */
    CANCELLED("CANCELLED"),
    /** 已退款 */
    REFUNDED("REFUNDED");

    companion object {
        /**
         * 状态转换规则表
         * key：当前状态，value：允许转换的目标状态集合
         */
        val TRANSITION_MAP: Map<OrderStatusEnum, Set<OrderStatusEnum>> = mapOf(
            PENDING_PAYMENT to setOf(CONSULTATION_PAID, CANCELLED),
            CONSULTATION_PAID to setOf(VERIFIED, CANCELLED, REFUNDED),
            VERIFIED to setOf(BALANCE_PAID, CANCELLED, DISPUTE_MEDIATION, REFUNDED),
            BALANCE_PAID to setOf(PENDING_COMPLETION, DISPUTE_MEDIATION, REFUNDED),
            PENDING_COMPLETION to setOf(COMPLETED, DISPUTE_MEDIATION),
            COMPLETED to setOf(PENDING_SETTLEMENT, DISPUTE_MEDIATION),
            PENDING_SETTLEMENT to setOf(SETTLED, DISPUTE_MEDIATION),
            SETTLED to emptySet(),
            DISPUTE_MEDIATION to setOf(VERIFIED, BALANCE_PAID, PENDING_COMPLETION, COMPLETED, PENDING_SETTLEMENT, CANCELLED, REFUNDED, SETTLED),
            CANCELLED to emptySet(),
            REFUNDED to emptySet()
        )

        /**
         * 根据字符串值获取枚举实例
         *
         * @param value 状态字符串值
         * @return 对应的枚举实例，未匹配返回null
         */
        fun fromValue(value: String): OrderStatusEnum? {
            return entries.find { it.value == value }
        }
    }

    /**
     * 校验是否可以从当前状态转换到目标状态
     *
     * @param target 目标状态
     * @return 是否允许转换
     */
    fun canTransitionTo(target: OrderStatusEnum): Boolean {
        val allowedTransitions = TRANSITION_MAP[this] ?: emptySet()
        return target in allowedTransitions
    }
}
