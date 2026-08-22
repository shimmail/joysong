package com.joysong.server.payment.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Real Stripe Checkout adapter.
 *
 * The mobile clients only receive Stripe's HTTPS Checkout URL. A local payment
 * becomes successful exclusively after a signed webhook or a server-side
 * Checkout Session query reports `payment_status=paid`.
 */
@Component
@ConditionalOnProperty(
    prefix = "payment.stripe",
    name = ["legacy-enabled"],
    havingValue = "true"
)
class StripePaymentGateway(
    private val objectMapper: ObjectMapper,
    @Value("\${payment.stripe.secret-key:}") private val secretKey: String,
    @Value("\${payment.stripe.webhook-secret:}") private val webhookSecret: String,
    @Value("\${payment.stripe.success-url:}") private val successUrl: String,
    @Value("\${payment.stripe.cancel-url:}") private val cancelUrl: String,
    @Value("\${payment.stripe.api-base:https://api.stripe.com}") private val apiBase: String,
    @Value("\${payment.stripe.api-version:}") private val apiVersion: String,
    @Value("\${payment.stripe.webhook-tolerance-seconds:300}") private val webhookToleranceSeconds: Long,
    @Value("\${payment.stripe.product-name:Joysong medical service}") private val productName: String,
    private val clock: Clock = Clock.systemUTC()
) : PaymentGateway {
    override val provider: PaymentProvider = PaymentProvider.STRIPE

    private val client: RestClient = RestClient.builder()
        .baseUrl(apiBase.trimEnd('/'))
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${secretKey.trim()}")
        .build()

    init {
        require(secretKey.isNotBlank()) { "STRIPE_SECRET_KEY_MISSING" }
        require(webhookSecret.isNotBlank()) { "STRIPE_WEBHOOK_SECRET_MISSING" }
        requireHttpsUrl(successUrl, "STRIPE_SUCCESS_URL_INVALID")
        requireHttpsUrl(cancelUrl, "STRIPE_CANCEL_URL_INVALID")
        require(webhookToleranceSeconds in 60..900) { "STRIPE_WEBHOOK_TOLERANCE_INVALID" }
    }

    override fun createPayment(request: ProviderCreatePaymentRequest): ProviderPaymentResult {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("mode", "payment")
            add("client_reference_id", request.paymentId)
            add("success_url", successUrl)
            add("cancel_url", cancelUrl)
            add("line_items[0][quantity]", "1")
            add("line_items[0][price_data][currency]", request.currency.lowercase())
            add("line_items[0][price_data][unit_amount]", request.amountMinor.toString())
            add("line_items[0][price_data][product_data][name]", productName)
            add("metadata[payment_id]", request.paymentId)
            add("metadata[order_id]", request.orderId)
            add("payment_intent_data[metadata][payment_id]", request.paymentId)
            add("payment_intent_data[metadata][order_id]", request.orderId)
        }
        val json = postForm(
            path = "/v1/checkout/sessions",
            form = form,
            idempotencyKey = request.idempotencyKey,
            outcomeUnknownOnTransport = true
        )
        return sessionResult(json)
    }

    /** Legacy Stripe retries remain idempotent through the unchanged request key. */
    override fun recoverPayment(request: ProviderCreatePaymentRequest): ProviderPaymentResult =
        createPayment(request)

    override fun queryPayment(providerPaymentId: String): ProviderPaymentResult =
        sessionResult(getJson("/v1/checkout/sessions/${safeStripeId(providerPaymentId)}"))

    override fun confirmPayment(request: ProviderConfirmPaymentRequest): ProviderPaymentResult =
        queryPayment(request.providerPaymentId)

    override fun refund(request: ProviderRefundRequest): ProviderRefundResult {
        val session = getJson("/v1/checkout/sessions/${safeStripeId(request.providerPaymentId)}")
        val paymentIntent = session.textOrNull("payment_intent")
            ?: throw providerError("STRIPE_PAYMENT_INTENT_MISSING", retryable = true)
        val form = LinkedMultiValueMap<String, String>().apply {
            add("payment_intent", paymentIntent)
            add("amount", request.amountMinor.toString())
            add("metadata[refund_item_id]", request.refundItemId)
        }
        return refundResult(
            postForm(
                path = "/v1/refunds",
                form = form,
                idempotencyKey = request.idempotencyKey,
                outcomeUnknownOnTransport = true
            )
        )
    }

    override fun queryRefund(providerRefundId: String): ProviderRefundResult =
        refundResult(getJson("/v1/refunds/${safeStripeId(providerRefundId)}"))

    override fun verifyWebhook(payload: String, headers: Map<String, String>): VerifiedProviderEvent {
        val signature = headers.entries.firstOrNull {
            it.key.equals("Stripe-Signature", ignoreCase = true)
        }?.value ?: throw providerError("STRIPE_SIGNATURE_MISSING")
        verifySignature(payload, signature)

        val root = parseJson(payload, outcomeUnknown = false)
        val eventId = root.requiredText("id", "STRIPE_EVENT_ID_MISSING")
        val eventType = root.requiredText("type", "STRIPE_EVENT_TYPE_MISSING")
        require(eventType in SUPPORTED_WEBHOOK_EVENTS) { "STRIPE_EVENT_UNSUPPORTED" }
        val session = root.path("data").path("object")
        require(session.path("object").asText() == "checkout.session") { "STRIPE_EVENT_OBJECT_INVALID" }
        val result = sessionResult(session)
        return VerifiedProviderEvent(
            providerEventId = eventId,
            eventType = eventType,
            providerPaymentId = result.providerPaymentId,
            providerTransactionId = result.providerTransactionId,
            paymentStatus = when (eventType) {
                "checkout.session.async_payment_failed" -> PaymentStatus.FAILED
                "checkout.session.expired" -> PaymentStatus.EXPIRED
                else -> result.status
            },
            amountMinor = result.amountMinor,
            currency = result.currency,
            failureCode = if (eventType == "checkout.session.async_payment_failed") {
                "STRIPE_ASYNC_PAYMENT_FAILED"
            } else null,
            localPaymentId = session.path("metadata").textOrNull("payment_id")
        )
    }

    private fun sessionResult(json: JsonNode): ProviderPaymentResult {
        val id = json.requiredText("id", "STRIPE_SESSION_ID_MISSING")
        val checkoutStatus = json.path("status").asText("")
        val paymentStatus = json.path("payment_status").asText("")
        val status = when {
            paymentStatus == "paid" || paymentStatus == "no_payment_required" -> PaymentStatus.SUCCEEDED
            checkoutStatus == "expired" -> PaymentStatus.EXPIRED
            checkoutStatus == "complete" -> PaymentStatus.PROCESSING
            else -> PaymentStatus.REQUIRES_ACTION
        }
        val redirectUrl = json.textOrNull("url")
        if (status == PaymentStatus.REQUIRES_ACTION) {
            require(!redirectUrl.isNullOrBlank()) { "STRIPE_CHECKOUT_URL_MISSING" }
            requireHttpsUrl(redirectUrl, "STRIPE_CHECKOUT_URL_INVALID")
        }
        return ProviderPaymentResult(
            status = status,
            providerPaymentId = id,
            providerTransactionId = json.textOrNull("payment_intent"),
            amountMinor = json.longOrNull("amount_total"),
            currency = json.textOrNull("currency")?.uppercase(),
            nextAction = redirectUrl?.takeIf { status == PaymentStatus.REQUIRES_ACTION }
                ?.let(PaymentNextAction::Redirect),
            expiresAt = json.longOrNull("expires_at")?.let {
                LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneOffset.UTC)
            }
        )
    }

    private fun refundResult(json: JsonNode): ProviderRefundResult {
        val id = json.requiredText("id", "STRIPE_REFUND_ID_MISSING")
        val rawStatus = json.path("status").asText("")
        return ProviderRefundResult(
            status = when (rawStatus) {
                "succeeded" -> PaymentStatus.SUCCEEDED
                "pending", "requires_action" -> PaymentStatus.PROCESSING
                else -> PaymentStatus.FAILED
            },
            providerRefundId = id,
            failureCode = json.path("failure_reason").asText(null)
        )
    }

    private fun verifySignature(payload: String, header: String) {
        val parts = header.split(',').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }
        val timestamp = parts.firstOrNull { it.first == "t" }?.second?.toLongOrNull()
            ?: throw providerError("STRIPE_SIGNATURE_INVALID")
        if (abs(clock.instant().epochSecond - timestamp) > webhookToleranceSeconds) {
            throw providerError("STRIPE_SIGNATURE_EXPIRED")
        }
        val expected = hmacSha256Hex(webhookSecret.trim(), "$timestamp.$payload")
        val valid = parts.filter { it.first == "v1" }.any { (_, candidate) ->
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.US_ASCII),
                candidate.lowercase().toByteArray(StandardCharsets.US_ASCII)
            )
        }
        if (!valid) throw providerError("STRIPE_SIGNATURE_INVALID")
    }

    private fun getJson(path: String): JsonNode = providerCall(outcomeUnknown = false) {
        client.get().uri(path).headers(::addStripeVersion).retrieve().body(String::class.java)
    }

    private fun postForm(
        path: String,
        form: LinkedMultiValueMap<String, String>,
        idempotencyKey: String,
        outcomeUnknownOnTransport: Boolean
    ): JsonNode = providerCall(outcomeUnknownOnTransport) {
        client.post()
            .uri(path)
            .headers { headers ->
                addStripeVersion(headers)
                headers.set("Idempotency-Key", idempotencyKey)
            }
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(String::class.java)
    }

    private fun providerCall(outcomeUnknown: Boolean, call: () -> String?): JsonNode = try {
        parseJson(call() ?: throw IllegalStateException("STRIPE_EMPTY_RESPONSE"), outcomeUnknown)
    } catch (error: PaymentProviderException) {
        throw error
    } catch (error: RestClientResponseException) {
        throw PaymentProviderException(
            errorCode = "STRIPE_HTTP_${error.statusCode.value()}",
            retryable = error.statusCode.value() == 429 || error.statusCode.is5xxServerError,
            outcomeUnknown = false,
            message = stripeErrorMessage(error.responseBodyAsString)
                ?: error.message
                ?: "STRIPE_HTTP_${error.statusCode.value()}",
            cause = error
        )
    } catch (error: ResourceAccessException) {
        throw PaymentProviderException(
            errorCode = "STRIPE_NETWORK_ERROR",
            retryable = true,
            outcomeUnknown = outcomeUnknown,
            cause = error
        )
    } catch (error: Exception) {
        throw PaymentProviderException(
            errorCode = "STRIPE_RESPONSE_INVALID",
            retryable = true,
            outcomeUnknown = outcomeUnknown,
            cause = error
        )
    }

    private fun parseJson(payload: String, outcomeUnknown: Boolean): JsonNode = try {
        objectMapper.readTree(payload)
    } catch (error: Exception) {
        throw PaymentProviderException(
            "STRIPE_RESPONSE_INVALID",
            retryable = true,
            outcomeUnknown = outcomeUnknown,
            cause = error
        )
    }

    private fun stripeErrorMessage(payload: String): String? = runCatching {
        objectMapper.readTree(payload).path("error").path("message").asText(null)
    }.getOrNull()?.take(500)

    private fun addStripeVersion(headers: HttpHeaders) {
        apiVersion.trim().takeIf(String::isNotEmpty)?.let { headers.set("Stripe-Version", it) }
    }

    private fun safeStripeId(value: String): String = value.also {
        require(STRIPE_ID.matches(it)) { "STRIPE_ID_INVALID" }
    }

    private fun requireHttpsUrl(value: String, code: String) {
        // Stripe replaces this literal token after Checkout completes; replace
        // it only for URI syntax validation and send the original value.
        val syntaxSafe = value.replace("{CHECKOUT_SESSION_ID}", "session")
        val uri = runCatching { URI(syntaxSafe) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { code }
    }

    private fun JsonNode.requiredText(field: String, code: String): String =
        textOrNull(field)?.takeIf(String::isNotBlank) ?: throw providerError(code)

    private fun JsonNode.textOrNull(field: String): String? =
        path(field).takeUnless { it.isMissingNode || it.isNull }?.asText()?.takeIf(String::isNotBlank)

    private fun JsonNode.longOrNull(field: String): Long? =
        path(field).takeUnless { it.isMissingNode || it.isNull }?.takeIf(JsonNode::isNumber)?.asLong()

    private fun providerError(code: String, retryable: Boolean = false) =
        PaymentProviderException(code, retryable, outcomeUnknown = false)

    private fun hmacSha256Hex(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val STRIPE_ID = Regex("^[A-Za-z0-9_]+$")
        private val SUPPORTED_WEBHOOK_EVENTS = setOf(
            "checkout.session.completed",
            "checkout.session.async_payment_succeeded",
            "checkout.session.async_payment_failed",
            "checkout.session.expired"
        )
    }
}
