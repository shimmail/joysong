package com.joysong.server.payment.provider

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.payment.domain.PaymentStatus
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class StripePaymentGatewayTest {
    private var server: HttpServer? = null
    private val now = Instant.parse("2026-08-07T08:00:00Z")

    @AfterEach
    fun stopServer() = server?.stop(0) ?: Unit

    @Test
    fun `create checkout session returns redirect without marking payment successful`() {
        var requestBody = ""
        var authorization = ""
        var idempotencyKey = ""
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server = it }
        http.createContext("/v1/checkout/sessions") { exchange ->
            requestBody = exchange.requestBody.bufferedReader().readText()
            authorization = exchange.requestHeaders.getFirst("Authorization")
            idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key")
            val response = """
                {"id":"cs_test_real_1","object":"checkout.session","status":"open",
                 "payment_status":"unpaid","amount_total":1999,"currency":"usd",
                 "expires_at":1786093200,
                 "url":"https://checkout.stripe.com/c/pay/cs_test_real_1"}
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        http.start()

        val result = gateway("http://127.0.0.1:${http.address.port}").createPayment(
            ProviderCreatePaymentRequest(
                paymentId = "payment-1",
                orderId = "order-1",
                amountMinor = 1999,
                currency = "USD",
                paymentMethod = "CARD",
                idempotencyKey = "payment-create-payment-1"
            )
        )

        assertEquals(PaymentStatus.REQUIRES_ACTION, result.status)
        assertEquals("cs_test_real_1", result.providerPaymentId)
        assertEquals(
            "https://checkout.stripe.com/c/pay/cs_test_real_1",
            (result.nextAction as PaymentNextAction.Redirect).url
        )
        assertEquals("Bearer sk_test_real", authorization)
        assertEquals("payment-create-payment-1", idempotencyKey)
        val form = parseForm(requestBody)
        assertEquals("payment", form["mode"])
        assertEquals("1999", form["line_items[0][price_data][unit_amount]"])
        assertEquals("payment-1", form["metadata[payment_id]"])
        assertEquals("order-1", form["metadata[order_id]"])
    }

    @Test
    fun `signed paid checkout webhook becomes succeeded and preserves local payment id`() {
        val timestamp = now.epochSecond
        val payload = """
            {"id":"evt_1","type":"checkout.session.completed","data":{"object":{
              "id":"cs_test_real_1","object":"checkout.session","status":"complete",
              "payment_status":"paid","amount_total":1999,"currency":"usd",
              "payment_intent":"pi_1","metadata":{"payment_id":"payment-1"}}}}
        """.trimIndent()
        val signature = hmac("whsec_test", "$timestamp.$payload")

        val event = gateway().verifyWebhook(
            payload,
            mapOf("stripe-signature" to "t=$timestamp,v1=$signature")
        )

        assertEquals(PaymentStatus.SUCCEEDED, event.paymentStatus)
        assertEquals("cs_test_real_1", event.providerPaymentId)
        assertEquals("pi_1", event.providerTransactionId)
        assertEquals("payment-1", event.localPaymentId)
        assertEquals(1999, event.amountMinor)
        assertEquals("USD", event.currency)
    }

    @Test
    fun `invalid webhook signature is rejected before state transition`() {
        val payload = """{"id":"evt_1","type":"checkout.session.completed","data":{"object":{}}}"""

        val error = assertThrows(PaymentProviderException::class.java) {
            gateway().verifyWebhook(
                payload,
                mapOf("Stripe-Signature" to "t=${now.epochSecond},v1=invalid")
            )
        }

        assertEquals("STRIPE_SIGNATURE_INVALID", error.errorCode)
        assertTrue(!error.outcomeUnknown)
    }

    private fun gateway(apiBase: String = "https://api.stripe.com") = StripePaymentGateway(
        objectMapper = jacksonObjectMapper(),
        secretKey = "sk_test_real",
        webhookSecret = "whsec_test",
        successUrl = "https://pay.joysong.example/success?session_id={CHECKOUT_SESSION_ID}",
        cancelUrl = "https://pay.joysong.example/cancel",
        apiBase = apiBase,
        apiVersion = "",
        webhookToleranceSeconds = 300,
        productName = "Joysong test service",
        clock = Clock.fixed(now, ZoneOffset.UTC)
    )

    private fun parseForm(value: String): Map<String, String> = value.split('&').associate { item ->
        val parts = item.split('=', limit = 2)
        URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
            URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
    }

    private fun hmac(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
