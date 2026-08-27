package com.joysong.server.common.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun `CNY amount converts to minor units without floating point loss`() {
        assertEquals(12345L, Money.toMinor(BigDecimal("123.45"), "cny"))
        assertEquals(BigDecimal("123.45"), Money.fromMinor(12345L, "CNY"))
    }

    @Test
    fun `zero-decimal currencies use whole minor units`() {
        assertEquals(123L, Money.toMinor(BigDecimal("123"), "JPY"))
    }

    @Test
    fun `unsupported precision is rejected instead of rounded silently`() {
        assertThrows(IllegalArgumentException::class.java) {
            Money.toMinor(BigDecimal("1.001"), "USD")
        }
    }
}
