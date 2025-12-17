package com.lightningkite.services.email

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.data.WebhookSubservice
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Service abstraction for receiving inbound emails via webhooks or polling.
 *
 * EmailInboundService provides a unified interface for processing incoming emails
 * across different providers (SendGrid, Mailgun, AWS SES, etc.). Applications can
 * switch email providers via configuration without code changes.
 *
 * ## Available Implementations
 *
 * - **ConsoleEmailInboundService** (`console`) - Prints received emails to console (development/testing)
 * - **TestEmailInboundService** (`test`) - Collects emails in memory for testing
 *
 * ## Configuration
 *
 * Configure via [Settings] using URL strings:
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val emailInbound: EmailInboundService.Settings = EmailInboundService.Settings("sendgrid://api-key")
 * )
 *
 * val context = SettingContext(...)
 * val inboundService: EmailInboundService = settings.emailInbound("inbound-mail", context)
 * ```
 *
 * ## Webhook-based Usage
 *
 * Most providers deliver emails via HTTP webhooks:
 *
 * ```kotlin
 * // In your HTTP handler (Ktor, Spring, etc.)
 * post("/webhooks/email") {
 *     val email = inboundService.onReceived.parseWebhook(
 *         queryParameters = call.request.queryParameters.entries(),
 *         headers = call.request.headers.entries().associate { it.key to it.value },
 *         body = TypedData.source(call.receiveChannel().toSource(), contentType)
 *     )
 *     processEmail(email)
 *     call.respond(HttpStatusCode.OK)
 * }
 * ```
 *
 * ## Poll-based Usage
 *
 * Some providers (IMAP, certain APIs) require polling:
 *
 * ```kotlin
 * // Call periodically (e.g., via scheduled task)
 * inboundService.onReceived.onSchedule()
 * ```
 *
 * ## Important Gotchas
 *
 * - **Webhook security**: Verify webhook signatures to prevent spoofing (provider-specific)
 * - **Idempotency**: Providers may retry webhooks; handle duplicate Message-IDs
 * - **Attachment limits**: Large attachments may be provided as URLs rather than inline
 * - **Spam filtering**: Check [ReceivedEmail.spamScore] if provided by the service
 * - **Threading**: Use [ReceivedEmail.inReplyTo] and [ReceivedEmail.references] for conversation threading
 *
 * @see ReceivedEmail
 * @see WebhookSubservice
 */
public interface EmailInboundService : Service {
    /**
     * Configuration for instantiating an EmailInboundService.
     *
     * The URL scheme determines the email provider:
     * - `console` - Print emails to console (default)
     * - `test` - Collect emails in memory for testing
     * - Provider-specific schemes (e.g., `sendgrid://`, `mailgun://`, `ses://`)
     *
     * @property url Connection string defining the email provider and credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "console"
    ) : Setting<EmailInboundService> {
        public companion object : UrlSettingParser<EmailInboundService>() {
            init {
                register("console") { name, _, context -> ConsoleEmailInboundService(name, context) }
                register("test") { name, _, context -> TestEmailInboundService(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): EmailInboundService {
            return parse(name, url, context)
        }
    }

    /**
     * Webhook subservice for receiving inbound emails.
     *
     * Use [WebhookSubservice.parseWebhook] to parse incoming webhook requests from your
     * email provider. Use [WebhookSubservice.onSchedule] for poll-based implementations.
     *
     * Optionally use [WebhookSubservice.configureWebhook] to programmatically register
     * webhook URLs with providers that support it.
     */
    public val onReceived: WebhookSubservice<ReceivedEmail>

    /**
     * The frequency at which health checks should be performed.
     */
    public override val healthCheckFrequency: Duration
        get() = 6.hours

    /**
     * Checks the health of the inbound email service.
     *
     * For webhook-based services, this typically just verifies configuration is valid.
     * For poll-based services, this may verify connectivity to the mail server.
     */
    public override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK)
    }
}
