package com.lightningkite.services.sms.twilio

import com.lightningkite.PhoneNumber
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.SMSException
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Twilio SMS implementation for sending text messages via Twilio API.
 *
 * Provides reliable SMS delivery with:
 * - **Global coverage**: Twilio supports 180+ countries
 * - **HTTP API**: REST-based API using Ktor HTTP client
 * - **Basic authentication**: Simple account SID + auth token authentication
 * - **Delivery tracking**: Can track message status via Twilio dashboard
 * - **Two-way messaging**: Supports receiving SMS via webhooks (not implemented here)
 *
 * ## Supported URL Schemes
 *
 * - `twilio://accountSid:authToken@fromPhoneNumber` - Complete Twilio configuration
 *
 * Format: `twilio://[accountSid]:[authToken]@[fromPhoneNumber]`
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Production Twilio account
 * SMS.Settings("twilio://ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx:your_auth_token@+15551234567")
 *
 * // Using helper function
 * SMS.Settings.Companion.twilio(
 *     account = "ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
 *     key = "your_auth_token",
 *     from = "+15551234567"
 * )
 * ```
 *
 * ## Implementation Notes
 *
 * - **HTTP client**: Uses Ktor with Java engine for HTTP requests
 * - **Authentication**: HTTP Basic Auth with account SID as username, auth token as password
 * - **API version**: Uses Twilio API 2010-04-01 (current stable version)
 * - **Error handling**: Throws SMSException on non-201 responses with error details
 * - **JSON parsing**: Configured to ignore unknown keys for API resilience
 *
 * ## Important Gotchas
 *
 * - **Phone number format**: Must use E.164 format (e.g., +15551234567)
 * - **From number must be verified**: Twilio requires you to own/verify the from number
 * - **Cost per message**: Twilio charges per SMS sent (varies by destination country)
 * - **Rate limits**: Twilio has API rate limits (default: 1000 req/sec)
 * - **Message length**: SMS limited to 160 characters (longer messages split into segments)
 * - **Trial accounts**: Limited to verified phone numbers only
 * - **No health check**: Health check returns OK but doesn't validate Twilio connectivity
 * - **Character encoding**: Non-GSM-7 characters reduce message length (70 chars per segment)
 *
 * ## Twilio Setup
 *
 * 1. Create a Twilio account at https://www.twilio.com
 * 2. Get your Account SID and Auth Token from the console
 * 3. Purchase a phone number or use a Twilio number
 * 4. Configure messaging settings (optional: webhooks for replies)
 * 5. Use the credentials and phone number in your configuration
 *
 * ## Message Pricing
 *
 * Twilio charges vary by country:
 * - **US/Canada**: ~$0.0079 per SMS
 * - **UK**: ~$0.04 per SMS
 * - **International**: Varies widely (check Twilio pricing)
 *
 * @property name Service name for logging/metrics
 * @property context Service context
 * @property account Twilio Account SID (starts with AC)
 * @property key Twilio Auth Token
 * @property from Sender phone number in E.164 format (e.g., +15551234567)
 */
public class TwilioSMS(
    override val name: String,
    override val context: SettingContext,
    private val account: String,
    private val key: String,
    private val from: String
) : SMS {

    private val client = HttpClient(Java) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Auth) {
            basic {
                credentials {
                    BasicAuthCredentials(username = account, password = key)
                }
                realm = "Twilio API"
            }
        }
    }

    /**
     * Sends an SMS message using the Twilio API.
     */
    override suspend fun send(to: PhoneNumber, message: String) {
        val response = client.submitForm(
            url = "https://api.twilio.com/2010-04-01/Accounts/${account}/Messages.json",
            formParameters = Parameters.build {
                append("From", from)
                append("To", to.toString())
                append("Body", message)
            }
        )

        if (response.status != HttpStatusCode.Created) {
            val errorMessage = response.bodyAsText()
            throw SMSException("Failed to send SMS: $errorMessage")
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        // TODO
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Twilio SMS Service - No direct health checks available yet.")
    }

    public companion object {
        public fun SMS.Settings.Companion.twilio(account: String, key: String, from: String): SMS.Settings = SMS.Settings("twilio://$account:$key@$from")
        init {
            SMS.Settings.register("twilio") { name, url, context ->
                val regex = Regex("""twilio://(?<user>[^:]+):(?<password>[^@]+)(?:@(?<phoneNumber>.+))?""")
                val match = regex.matchEntire(url)
                    ?: throw IllegalArgumentException("Invalid Twilio URL. The URL should match the pattern: twilio://[user]:[password]@[phoneNumber]")

                val account = match.groups["user"]?.value
                    ?: throw IllegalArgumentException("Twilio account not provided in URL")
                val key = match.groups["password"]?.value
                    ?: throw IllegalArgumentException("Twilio key not provided in URL")
                val from = match.groups["phoneNumber"]?.value
                ?: throw IllegalArgumentException("Twilio phone number not provided in URL or settings")

                TwilioSMS(name, context, account, key, from)
            }
        }
    }
}