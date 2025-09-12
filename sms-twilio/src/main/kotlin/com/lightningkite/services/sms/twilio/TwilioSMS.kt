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
 * An SMS implementation that sends messages using the Twilio API.
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