package com.lightningkite.services.email

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger("ConsoleEmailInboundService")

/**
 * Development/debugging implementation for local testing with tools like Postman.
 *
 * This implementation is useful for:
 * - Local development without a real email provider
 * - Testing webhook endpoints with Postman or similar tools
 * - Debugging webhook payloads
 *
 * ## Usage
 *
 * 1. Configure the webhook URL to point to your local server:
 * ```kotlin
 * val service = ConsoleEmailInboundService("console", context)
 * service.onReceived.configureWebhook("http://localhost:8080/webhooks/email")
 * ```
 *
 * 2. Send test emails using [simulateReceive]:
 * ```kotlin
 * service.simulateReceive(ReceivedEmail(
 *     messageId = "test-123",
 *     from = EmailAddressWithName("sender@example.com"),
 *     to = listOf(EmailAddressWithName("recipient@example.com")),
 *     subject = "Test Email",
 *     plainText = "Hello from console!",
 *     receivedAt = Clock.System.now()
 * ))
 * ```
 *
 * 3. Your local server will receive a POST with the email as JSON.
 *
 * @property httpClient HTTP client for posting to configured webhooks
 */
public class ConsoleEmailInboundService(
    override val name: String,
    override val context: SettingContext,
    public val httpClient: HttpClient = HttpClient(),
) : EmailInboundService {

    private var webhookUrl: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Simulates receiving an email and posts it to the configured webhook URL.
     *
     * Use this method to test your webhook endpoint with sample emails.
     * The email will be serialized to JSON and POSTed to the URL configured
     * via [WebhookSubservice.configureWebhook].
     *
     * @param email The email to simulate receiving
     * @throws IllegalStateException if no webhook URL has been configured
     */
    public suspend fun simulateReceive(email: ReceivedEmail) {
        val targetUrl = webhookUrl
        if (targetUrl == null) {
            logger.warn { "[$name] No webhook URL configured. Call configureWebhook() first." }
            logger.info { "[$name] Would have sent email: ${email.messageId} - ${email.subject}" }
            logger.info { "[$name] Email JSON:\n${json.encodeToString(email)}" }
            return
        }

        logger.info { "[$name] Posting email to webhook: ${email.messageId} - ${email.subject}" }
        httpClient.post(targetUrl) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(email))
        }
        logger.info { "[$name] Successfully posted to $targetUrl" }
    }

    override val onReceived: WebhookSubservice<ReceivedEmail> = object : WebhookSubservice<ReceivedEmail> {
        override suspend fun configureWebhook(httpUrl: String) {
            logger.info { "[$name] Webhook URL configured: $httpUrl" }
            webhookUrl = httpUrl
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): ReceivedEmail {
            logger.info { "[$name] Received webhook request:" }
            logger.info { "  Query parameters: $queryParameters" }
            logger.info { "  Headers: $headers" }
            logger.info { "  Body type: ${body.mediaType}" }

            // Parse the JSON body as ReceivedEmail
            val bodyText = body.data.text()
            return json.decodeFromString<ReceivedEmail>(bodyText)
        }

        override suspend fun onSchedule() {
            logger.info { "[$name] onSchedule called (no-op for console implementation)" }
        }
    }
}
