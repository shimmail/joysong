package com.joysong.server.payment.entity

import jakarta.persistence.Column
import jakarta.persistence.Lob
import org.hibernate.annotations.JdbcTypeCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PaymentEventEntityMappingTest {
    @Test
    fun `raw webhook payload maps as long text rather than JSON`() {
        val payload = PaymentEventEntity::class.java.getDeclaredField("payload")

        assertNotNull(payload.getAnnotation(Lob::class.java))
        assertEquals(
            "LONGTEXT",
            payload.getAnnotation(Column::class.java).columnDefinition.uppercase()
        )
        assertNull(payload.getAnnotation(JdbcTypeCode::class.java))
    }
}
