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
 * An interface for sending SMS messages.
 */
public interface SMS : Service {
    /**
     * Sends an SMS message to the specified phone number.
     *
     * @param to The phone number to send the message to
     * @param message The content of the message
     */
    public suspend fun send(to: PhoneNumber, message: String)

    /**
     * Settings for configuring an SMS service.
     *
     * @param url A string containing everything needed to connect to send SMS. The format is defined by the implementation.
     *  For Twilio: twilio://[user]:[password]@[phoneNumber]
     *  For Console: console
     *  For Test: test
     * @param from The phone number to send messages from (optional, can be specified in the URL for some implementations)
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
         * Creates a health status for the SMS service.
         */
        public fun healthStatusOk(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
    }
}

/**
 * Exception thrown when there is an error sending an SMS message.
 */
public class SMSException(override val message: String?) : Exception()