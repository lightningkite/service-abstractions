package com.lightningkite.services.sms

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.toPhoneNumber
import kotlin.time.Clock

/**
 * A development implementation of [SmsInboundService] that prints received SMS to the console.
 *
 * This implementation is useful for local development and testing. It simulates webhook
 * parsing by extracting phone numbers and message body from a simple format.
 *
 * ## Simulated Webhook Format
 *
 * For testing, the body should be plain text in the format:
 * ```
 * From: +15551234567
 * To: +15559876543
 * Body: Hello, this is a test message
 * ```
 *
 * Or as JSON with fields: `from`, `to`, `body`
 */
public class ConsoleSmsInboundService(
    override val name: String,
    override val context: SettingContext
) : SmsInboundService {

    override val onReceived: WebhookSubservice<InboundSms> = object : WebhookSubservice<InboundSms> {
        override suspend fun configureWebhook(httpUrl: String) {
            println("[$name] SMS Inbound webhook configured: $httpUrl")
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): InboundSms {
            val bodyText = body.text()

            // Parse simple text format or just use defaults
            val fromMatch = Regex("From:\\s*([+\\d]+)").find(bodyText)
            val toMatch = Regex("To:\\s*([+\\d]+)").find(bodyText)
            val bodyMatch = Regex("Body:\\s*(.+)").find(bodyText)

            val inboundSms = InboundSms(
                from = fromMatch?.groupValues?.get(1)?.toPhoneNumber() ?: "+10000000000".toPhoneNumber(),
                to = toMatch?.groupValues?.get(1)?.toPhoneNumber() ?: "+10000000001".toPhoneNumber(),
                body = bodyMatch?.groupValues?.get(1)?.trim() ?: bodyText,
                receivedAt = Clock.System.now()
            )

            println("[$name] Received SMS:")
            println("  From: ${inboundSms.from}")
            println("  To: ${inboundSms.to}")
            println("  Body: ${inboundSms.body}")
            if (inboundSms.mediaUrls.isNotEmpty()) {
                println("  Media: ${inboundSms.mediaUrls}")
            }
            println()

            return inboundSms
        }

        override suspend fun onSchedule() {
            // No scheduled tasks for console implementation
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Console SMS Inbound Service - Development only")
    }
}
