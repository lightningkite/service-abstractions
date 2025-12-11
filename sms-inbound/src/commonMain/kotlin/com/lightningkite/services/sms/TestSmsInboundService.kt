package com.lightningkite.services.sms

import com.lightningkite.PhoneNumber
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.toPhoneNumber
import kotlin.time.Clock

/**
 * A test implementation of [SmsInboundService] that stores received SMS in memory.
 *
 * This implementation is useful for unit testing. It allows you to:
 * - Simulate receiving SMS messages via [simulateInbound]
 * - Verify received messages via [receivedMessages]
 * - Set up callbacks via [onMessageReceived]
 *
 * ## Usage
 *
 * ```kotlin
 * val service = TestSmsInboundService("test", context)
 *
 * // Simulate receiving an SMS
 * val sms = service.simulateInbound(
 *     from = "+15551234567".toPhoneNumber(),
 *     to = "+15559876543".toPhoneNumber(),
 *     body = "Hello!"
 * )
 *
 * // Verify
 * assertEquals(1, service.receivedMessages.size)
 * assertEquals("Hello!", service.lastReceived?.body)
 * ```
 */
public class TestSmsInboundService(
    override val name: String,
    override val context: SettingContext
) : SmsInboundService {

    /**
     * All SMS messages received (either via webhook or [simulateInbound]).
     */
    public val receivedMessages: MutableList<InboundSms> = mutableListOf()

    /**
     * The last SMS message received, or null if none.
     */
    public val lastReceived: InboundSms?
        get() = receivedMessages.lastOrNull()

    /**
     * Callback invoked when a message is received.
     */
    public var onMessageReceived: ((InboundSms) -> Unit)? = null

    /**
     * Whether to also print received messages to console.
     */
    public var printToConsole: Boolean = false

    /**
     * The webhook URL that was configured, if any.
     */
    public var configuredWebhookUrl: String? = null
        private set

    /**
     * Clears all received messages and resets state.
     */
    public fun reset() {
        receivedMessages.clear()
        configuredWebhookUrl = null
    }

    /**
     * Simulates receiving an inbound SMS message.
     *
     * This is useful for testing without going through the webhook parsing flow.
     *
     * @param from Sender phone number
     * @param to Receiving phone number
     * @param body Message text
     * @param mediaUrls Optional MMS media URLs
     * @param mediaContentTypes Optional MIME types for media
     * @return The created [InboundSms] that was added to [receivedMessages]
     */
    public fun simulateInbound(
        from: PhoneNumber,
        to: PhoneNumber,
        body: String,
        mediaUrls: List<String> = emptyList(),
        mediaContentTypes: List<String> = emptyList(),
        providerMessageId: String? = null
    ): InboundSms {
        val sms = InboundSms(
            from = from,
            to = to,
            body = body,
            receivedAt = Clock.System.now(),
            mediaUrls = mediaUrls,
            mediaContentTypes = mediaContentTypes,
            providerMessageId = providerMessageId
        )
        receivedMessages.add(sms)
        onMessageReceived?.invoke(sms)

        if (printToConsole) {
            println("[$name] Test received SMS from ${sms.from}: ${sms.body}")
        }

        return sms
    }

    /**
     * Finds the last message received from a specific phone number.
     */
    public fun lastMessageFrom(phoneNumber: PhoneNumber): InboundSms? {
        return receivedMessages.lastOrNull { it.from == phoneNumber }
    }

    /**
     * Finds the last message received to a specific phone number.
     */
    public fun lastMessageTo(phoneNumber: PhoneNumber): InboundSms? {
        return receivedMessages.lastOrNull { it.to == phoneNumber }
    }

    override val onReceived: WebhookSubservice<InboundSms> = object : WebhookSubservice<InboundSms> {
        override suspend fun configureWebhook(httpUrl: String) {
            configuredWebhookUrl = httpUrl
            if (printToConsole) {
                println("[$name] Test SMS Inbound webhook configured: $httpUrl")
            }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): InboundSms {
            val bodyText = body.text()

            // Parse simple text format
            val fromMatch = Regex("From:\\s*([+\\d]+)").find(bodyText)
            val toMatch = Regex("To:\\s*([+\\d]+)").find(bodyText)
            val bodyMatch = Regex("Body:\\s*(.+)").find(bodyText)

            val inboundSms = InboundSms(
                from = fromMatch?.groupValues?.get(1)?.toPhoneNumber() ?: "+10000000000".toPhoneNumber(),
                to = toMatch?.groupValues?.get(1)?.toPhoneNumber() ?: "+10000000001".toPhoneNumber(),
                body = bodyMatch?.groupValues?.get(1)?.trim() ?: bodyText,
                receivedAt = Clock.System.now()
            )

            receivedMessages.add(inboundSms)
            onMessageReceived?.invoke(inboundSms)

            if (printToConsole) {
                println("[$name] Test parsed SMS from ${inboundSms.from}: ${inboundSms.body}")
            }

            return inboundSms
        }

        override suspend fun onSchedule() {
            // No scheduled tasks for test implementation
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Test SMS Inbound Service - No real webhooks")
    }
}
