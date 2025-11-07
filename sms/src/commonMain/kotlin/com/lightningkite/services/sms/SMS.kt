package com.lightningkite.services.sms

import com.lightningkite.PhoneNumber
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Service abstraction for sending SMS text messages.
 *
 * SMS provides a unified interface for sending text messages across different providers
 * (Twilio, AWS SNS, etc.). Applications can switch SMS providers via configuration
 * without code changes.
 *
 * ## Available Implementations
 *
 * - **ConsoleSMS** (`console`) - Prints SMS to console (development/testing)
 * - **TestSMS** (`test`) - Collects SMS in memory for testing
 * - **TwilioSMS** (`twilio://`) - Twilio API implementation (requires sms-twilio module)
 *
 * ## Configuration
 *
 * Configure via [Settings] using URL strings:
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val sms: SMS.Settings = SMS.Settings("twilio://accountSid:authToken@+15551234567")
 * )
 *
 * val context = SettingContext(...)
 * val smsService: SMS = settings.sms("sms", context)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // Send SMS
 * smsService.send(
 *     to = "+15559876543".toPhoneNumber(),
 *     message = "Your verification code is 123456"
 * )
 * ```
 *
 * ## Phone Number Format
 *
 * Phone numbers must be in E.164 format: `+[country code][number]`
 * - US/Canada: `+1555123456 7`
 * - UK: `+447700900123`
 * - Invalid: `555-123-4567`, `(555) 123-4567`
 *
 * Use `String.toPhoneNumber()` for validation.
 *
 * ## Important Gotchas
 *
 * - **E.164 format required**: Most providers reject non-E.164 phone numbers
 * - **From number**: Must be a valid number you own/rent from the provider
 * - **Message length**: Standard SMS is 160 characters. Longer messages are split (and charged per segment)
 * - **International rates**: Sending international SMS is expensive (check provider pricing)
 * - **Rate limits**: Providers enforce rate limits (e.g., Twilio: 1 msg/sec default)
 * - **Carrier filtering**: Carriers may block messages with suspicious content
 * - **Opt-out required**: Many countries require "Reply STOP to unsubscribe" text
 * - **Delivery**: SMS delivery is not guaranteed, no read receipts
 * - **Cost**: Each SMS has a per-message cost (unlike email which is mostly free)
 * - **No health check**: Default [healthCheck] returns OK without sending (override to test for real)
 *
 * @see PhoneNumber
 */
public interface SMS : Service {
    /**
     * Sends an SMS text message to a phone number.
     *
     * Messages longer than 160 characters are split into multiple segments,
     * each charged separately by the provider.
     *
     * @param to Recipient phone number in E.164 format (e.g., "+15551234567")
     * @param message SMS text content (maximum ~1600 chars, but split into 160-char segments)
     * @throws SMSException if sending fails
     */
    public suspend fun send(to: PhoneNumber, message: String)

    /**
     * Configuration for instantiating an SMS service.
     *
     * The URL scheme determines the SMS provider:
     * - `console` - Print SMS to console (default)
     * - `test` - Collect SMS in memory for testing
     * - `twilio://accountSid:authToken@fromNumber` - Twilio provider (requires sms-twilio module)
     *
     * The `fromNumber` in the URL is the sender phone number (must be owned/rented from provider).
     *
     * @property url Connection string defining the SMS provider and credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "console",
    ) : Setting<SMS> {
        override fun invoke(name: String, context: SettingContext): SMS {
            return parse(name, url, context)
        }

        public companion object : UrlSettingParser<SMS>() {
            init {
                register("console") { name, _, context ->
                    ConsoleSMS(name, context)
                }
                register("test") { name, _, context ->
                    TestSMS(name, context)
                }
            }
        }
    }

    public companion object {
        /**
         * Creates a health status indicating SMS service is operational.
         *
         * Used by implementations that don't want to send real test messages.
         */
        public fun healthStatusOk(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
    }
}

/**
 * Exception thrown when SMS sending fails.
 *
 * Common causes:
 * - Invalid phone number format (not E.164)
 * - Insufficient account balance
 * - Rate limit exceeded
 * - Invalid credentials
 * - Carrier rejected message
 *
 * @property message Error description from provider
 */
public class SMSException(override val message: String?) : Exception()