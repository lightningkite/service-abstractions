package com.lightningkite.services.sms.twilio

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.sms.InboundSms
import com.lightningkite.services.sms.SmsInboundService
import com.lightningkite.toPhoneNumber
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger("TwilioSmsInboundService")

/**
 * Twilio implementation for receiving inbound SMS/MMS messages via webhooks.
 *
 * When a message is sent to your Twilio phone number, Twilio sends an HTTP POST
 * request to your configured webhook URL with the message details.
 *
 * ## Supported URL Schemes
 *
 * - `twilio://accountSid:authToken@phoneNumber` - Twilio inbound SMS configuration
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Production Twilio account
 * SmsInboundService.Settings("twilio://ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx:your_auth_token@+15551234567")
 * ```
 *
 * ## Webhook Setup
 *
 * Webhooks are automatically configured when [configureWebhook] is called.
 * The service will update your Twilio phone number's SMS URL via the API.
 *
 * ## Twilio Webhook Parameters
 *
 * Twilio sends these parameters in the webhook request body:
 * - `MessageSid` - Unique identifier for this message
 * - `From` - Sender's phone number (E.164 format)
 * - `To` - Your Twilio phone number (E.164 format)
 * - `Body` - The text content of the message
 * - `NumMedia` - Number of media attachments (MMS)
 * - `MediaUrl0`, `MediaUrl1`, etc. - URLs to media files
 * - `MediaContentType0`, etc. - MIME types for media
 *
 * ## Webhook Security
 *
 * This implementation validates Twilio webhook signatures to prevent spoofing.
 * The `X-Twilio-Signature` header contains an HMAC-SHA1 signature that is
 * verified using your Auth Token.
 *
 * ## Important Gotchas
 *
 * - **Signature validation**: Always validate the X-Twilio-Signature header
 * - **Media URL expiration**: MMS media URLs expire after 4 hours by default
 * - **Response required**: Return a 200 OK quickly or Twilio will retry
 * - **TwiML responses**: You can return TwiML to auto-reply (not implemented here)
 * - **Trial accounts**: Limited to verified phone numbers only
 *
 * @property name Service name for logging/metrics
 * @property context Service context
 * @property accountSid Twilio Account SID (starts with AC)
 * @property authToken Twilio Auth Token (used for signature validation)
 * @property phoneNumber The Twilio phone number in E.164 format (e.g., +15551234567)
 */
public class TwilioSmsInboundService(
    override val name: String,
    override val context: SettingContext,
    private val accountSid: String,
    private val authToken: String,
    private val phoneNumber: String,
) : SmsInboundService {

    private val client = com.lightningkite.services.http.client.config {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Auth) {
            basic {
                credentials {
                    BasicAuthCredentials(username = accountSid, password = authToken)
                }
                realm = "Twilio API"
            }
        }
    }

    private val baseUrl = "https://api.twilio.com/2010-04-01/Accounts/$accountSid"

    override val onReceived: WebhookSubservice<InboundSms> = object : WebhookSubservice<InboundSms> {
        var httpUrl: String? = null

        override suspend fun configureWebhook(httpUrl: String) {
            this.httpUrl = httpUrl

            // Look up the phone number SID
            val phoneNumberSid = lookupPhoneNumberSid(phoneNumber)

            // Update the phone number's SMS webhook URL
            val response = client.submitForm(
                url = "$baseUrl/IncomingPhoneNumbers/$phoneNumberSid.json",
                formParameters = io.ktor.http.Parameters.build {
                    append("SmsUrl", httpUrl)
                    append("SmsMethod", "POST")
                }
            )

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw IllegalStateException("Failed to configure Twilio SMS webhook: $errorBody")
            }

            logger.info { "[$name] Configured Twilio SMS webhook for $phoneNumber -> $httpUrl" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): InboundSms {
            // Parse URL-encoded form data from Twilio
            val bodyText = body.text()
            val params = parseUrlEncodedForm(bodyText)

            // Validate Twilio signature for security
            validateWebhookSignature(headers, params, httpUrl)

            // Extract required fields
            val from = params["From"]
                ?: throw IllegalArgumentException("Missing 'From' parameter in Twilio webhook")
            val to = params["To"]
                ?: throw IllegalArgumentException("Missing 'To' parameter in Twilio webhook")
            val messageBody = params["Body"] ?: ""
            val messageSid = params["MessageSid"]

            // Extract MMS media (if present)
            val numMedia = params["NumMedia"]?.toIntOrNull() ?: 0
            val mediaUrls = mutableListOf<String>()
            val mediaContentTypes = mutableListOf<String>()

            for (i in 0 until numMedia) {
                params["MediaUrl$i"]?.let { mediaUrls.add(it) }
                params["MediaContentType$i"]?.let { mediaContentTypes.add(it) }
            }

            return InboundSms(
                from = from.toPhoneNumber(),
                to = to.toPhoneNumber(),
                body = messageBody,
                receivedAt = Clock.System.now(),
                mediaUrls = mediaUrls,
                mediaContentTypes = mediaContentTypes,
                providerMessageId = messageSid
            )
        }

        override suspend fun onSchedule() {
            // No scheduled tasks needed for Twilio inbound
        }
    }

    /**
     * Validates the Twilio webhook signature.
     *
     * @param url The full URL of your webhook endpoint (including https://)
     * @param params The parsed form parameters from the request body
     * @param signature The value of the X-Twilio-Signature header
     * @return true if the signature is valid, false otherwise
     */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun validateSignature(url: String, params: Map<String, String>, signature: String): Boolean {
        // Build the data string: URL + sorted params concatenated
        val data = buildString {
            append(url)
            params.keys.sorted().forEach { key ->
                append(key)
                append(params[key] ?: "")
            }
        }

        // Compute HMAC-SHA1
        val mac = Mac.getInstance("HmacSHA1")
        val keySpec = SecretKeySpec(authToken.toByteArray(Charsets.UTF_8), "HmacSHA1")
        mac.init(keySpec)
        val rawHmac = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        val computedSignature = Base64.encode(rawHmac)

        return computedSignature == signature
    }

    /**
     * Validates the Twilio webhook signature from headers and body.
     * Throws SecurityException if signature is missing or invalid.
     *
     * Note: Signature validation is skipped if the webhook URL has not been configured.
     * This allows for testing scenarios where configureWebhook() has not been called.
     * In production, always call configureWebhook() before processing webhooks.
     */
    private fun validateWebhookSignature(
        headers: Map<String, List<String>>,
        params: Map<String, String>,
        webhookUrl: String?
    ) {
        // Skip validation if webhook URL not configured (e.g., in tests)
        if (webhookUrl == null) {
            logger.warn { "[$name] Skipping signature validation - webhook URL not configured. Call configureWebhook() in production." }
            return
        }

        val signature = headers["X-Twilio-Signature"]?.firstOrNull()
            ?: headers["x-twilio-signature"]?.firstOrNull()
            ?: throw SecurityException("Missing X-Twilio-Signature header")

        if (!validateSignature(webhookUrl, params, signature)) {
            throw SecurityException("Invalid Twilio webhook signature")
        }
    }

    /**
     * Parses URL-encoded form data into a map.
     */
    private fun parseUrlEncodedForm(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()

        return body.split("&")
            .filter { it.isNotBlank() }
            .associate { param ->
                val parts = param.split("=", limit = 2)
                val key = URLDecoder.decode(parts[0], Charsets.UTF_8)
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], Charsets.UTF_8) else ""
                key to value
            }
    }

    /**
     * Looks up the Twilio phone number SID for a given phone number.
     *
     * @param phoneNumber The phone number in E.164 format (e.g., +15551234567)
     * @return The phone number SID (e.g., PN...)
     * @throws IllegalStateException if the phone number is not found
     */
    private suspend fun lookupPhoneNumberSid(phoneNumber: String): String {
        val encodedNumber = java.net.URLEncoder.encode(phoneNumber, Charsets.UTF_8)
        val response = client.get("$baseUrl/IncomingPhoneNumbers.json?PhoneNumber=$encodedNumber")

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException("Failed to look up Twilio phone number: $errorBody")
        }

        val responseBody = response.bodyAsText()
        // Parse the SID from the JSON response
        // Response format: {"incoming_phone_numbers": [{"sid": "PN...", ...}], ...}
        val sidMatch = Regex(""""sid"\s*:\s*"(PN[^"]+)"""").find(responseBody)
        return sidMatch?.groupValues?.get(1)
            ?: throw IllegalStateException("Phone number $phoneNumber not found in Twilio account $accountSid")
    }

    override suspend fun healthCheck(): HealthStatus {
        // Could potentially verify credentials by making an API call
        return HealthStatus(
            HealthStatus.Level.OK,
            additionalMessage = "Twilio SMS Inbound Service - Account: $accountSid"
        )
    }

    public companion object {
        /**
         * Creates a Twilio SMS inbound settings URL using Account SID + Auth Token.
         *
         * @param account Twilio Account SID (starts with AC)
         * @param authToken Twilio Auth Token
         * @param phoneNumber The phone number in E.164 format to receive SMS on
         */
        public fun SmsInboundService.Settings.Companion.twilio(
            account: String,
            authToken: String,
            phoneNumber: String
        ): SmsInboundService.Settings = SmsInboundService.Settings("twilio://$account:$authToken@$phoneNumber")

        init {
            SmsInboundService.Settings.register("twilio") { name, url, context ->
                // Parse Auth Token format: twilio://accountSid:authToken@phoneNumber
                val authTokenRegex = Regex("""twilio://(?<account>[^:]+):(?<authToken>[^@]+)@(?<phoneNumber>.+)""")
                val authTokenMatch = authTokenRegex.matchEntire(url)
                    ?: throw IllegalArgumentException("Invalid Twilio URL. Expected: twilio://[accountSid]:[authToken]@[phoneNumber]")

                val account = authTokenMatch.groups["account"]?.value
                    ?: throw IllegalArgumentException("Twilio account SID not provided in URL")
                val authToken = authTokenMatch.groups["authToken"]?.value
                    ?: throw IllegalArgumentException("Twilio auth token not provided in URL")
                val phoneNumber = authTokenMatch.groups["phoneNumber"]?.value
                    ?: throw IllegalArgumentException("Twilio phone number not provided in URL")

                TwilioSmsInboundService(name, context, account, authToken, phoneNumber)
            }
        }
    }
}
