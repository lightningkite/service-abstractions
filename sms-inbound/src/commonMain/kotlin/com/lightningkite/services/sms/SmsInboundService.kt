package com.lightningkite.services.sms

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
 * Service abstraction for receiving inbound SMS/MMS messages via webhooks.
 *
 * SmsInboundService provides a unified interface for receiving text messages across different
 * providers (Twilio, AWS SNS, etc.). Applications can switch providers via configuration
 * without code changes.
 *
 * ## Available Implementations
 *
 * - **ConsoleSmsInboundService** (`console`) - Prints received SMS to console (development/testing)
 * - **TestSmsInboundService** (`test`) - Collects received SMS in memory for testing
 * - Provider-specific implementations available in separate modules (e.g., sms-inbound-twilio)
 *
 * ## Configuration
 *
 * Configure via [Settings] using URL strings:
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val smsInbound: SmsInboundService.Settings = SmsInboundService.Settings("twilio://accountSid:authToken")
 * )
 *
 * val context = SettingContext(...)
 * val smsInboundService: SmsInboundService = settings.smsInbound("sms-inbound", context)
 * ```
 *
 * ## Webhook Setup
 *
 * Most providers require you to configure a webhook URL where they will POST incoming messages.
 * Use [onReceived.configureWebhook] to set this up programmatically if the provider supports it.
 *
 * ```kotlin
 * // Configure webhook URL with provider
 * smsInboundService.onReceived.configureWebhook("https://yourserver.com/webhooks/sms")
 *
 * // In your HTTP handler, parse incoming webhooks
 * val inboundSms = smsInboundService.onReceived.parseWebhook(
 *     queryParameters = request.queryParameters,
 *     headers = request.headers,
 *     body = TypedData(request.body, request.contentType)
 * )
 * ```
 *
 * ## Important Gotchas
 *
 * - **Webhook security**: Validate webhook signatures to prevent spoofing (provider-specific)
 * - **Media URLs expire**: MMS media URLs from providers are typically temporary (24-72 hours)
 * - **Response required**: Most providers expect a quick HTTP 200 response or they'll retry
 * - **Idempotency**: Providers may retry webhooks; use [InboundSms.providerMessageId] for deduplication
 * - **Rate limits**: High-volume inbound traffic may require queue-based processing
 *
 * @see InboundSms
 * @see WebhookSubservice
 */
public interface SmsInboundService : Service {
    /**
     * Configuration for instantiating an SmsInboundService.
     *
     * The URL scheme determines the SMS provider:
     * - `console` - Print received SMS to console (default)
     * - `test` - Collect received SMS in memory for testing
     *
     * @property url Connection string defining the SMS provider and credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "console"
    ) : Setting<SmsInboundService> {
        public companion object : UrlSettingParser<SmsInboundService>() {
            init {
                register("console") { name, _, context -> ConsoleSmsInboundService(name, context) }
                register("test") { name, _, context -> TestSmsInboundService(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): SmsInboundService {
            return parse(name, url, context)
        }
    }

    /**
     * Webhook subservice for receiving inbound SMS messages.
     *
     * Use this to:
     * - Configure the webhook URL with the provider (if supported)
     * - Parse incoming webhook requests into [InboundSms] objects
     * - Handle scheduled maintenance tasks
     */
    public val onReceived: WebhookSubservice<InboundSms>

    /**
     * The frequency at which health checks should be performed.
     */
    public override val healthCheckFrequency: Duration
        get() = 6.hours

    /**
     * Checks the health of the SMS inbound service.
     *
     * Default implementation returns OK. Override to perform actual connectivity checks.
     */
    public override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK)
    }
}
