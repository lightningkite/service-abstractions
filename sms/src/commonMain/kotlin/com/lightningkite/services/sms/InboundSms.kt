package com.lightningkite.services.sms

import com.lightningkite.PhoneNumber
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Represents an incoming SMS or MMS message received via webhook.
 *
 * This data class captures the essential information from an inbound text message,
 * including support for MMS media attachments.
 *
 * ## Usage
 *
 * ```kotlin
 * // Simple SMS
 * val sms = InboundSms(
 *     from = "+15551234567".toPhoneNumber(),
 *     to = "+15559876543".toPhoneNumber(),
 *     body = "Hello!",
 *     receivedAt = Clock.System.now()
 * )
 *
 * // MMS with media
 * val mms = InboundSms(
 *     from = "+15551234567".toPhoneNumber(),
 *     to = "+15559876543".toPhoneNumber(),
 *     body = "Check out this photo!",
 *     receivedAt = Clock.System.now(),
 *     mediaUrls = listOf("https://api.twilio.com/2010-04-01/.../Media/..."),
 *     mediaContentTypes = listOf("image/jpeg")
 * )
 * ```
 *
 * ## Provider-Specific Fields
 *
 * Different providers may include additional metadata. Use [providerMessageId] to store
 * the provider's unique message identifier for tracking or debugging.
 *
 * @property from Sender's phone number in E.164 format
 * @property to Receiving phone number (your number) in E.164 format
 * @property body Text content of the message (may be empty for MMS-only messages)
 * @property receivedAt Timestamp when the message was received
 * @property mediaUrls URLs to media attachments (for MMS). These are typically temporary URLs provided by the carrier/provider.
 * @property mediaContentTypes MIME types corresponding to each media URL (e.g., "image/jpeg", "video/mp4")
 * @property providerMessageId Provider-specific unique message identifier (e.g., Twilio's MessageSid)
 */
@Serializable
public data class InboundSms(
    val from: PhoneNumber,
    val to: PhoneNumber,
    val body: String,
    val receivedAt: Instant,
    val mediaUrls: List<String> = emptyList(),
    val mediaContentTypes: List<String> = emptyList(),
    val providerMessageId: String? = null
)
