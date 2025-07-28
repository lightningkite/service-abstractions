package com.lightningkite.serviceabstractions.sms

import com.lightningkite.PhoneNumber
import com.lightningkite.serviceabstractions.HealthStatus
import com.lightningkite.serviceabstractions.Service
import com.lightningkite.serviceabstractions.Setting
import com.lightningkite.serviceabstractions.SettingContext
import kotlinx.serialization.Serializable

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
    public data class Settings(
        public val url: String = "console",
        public val from: String? = null
    ) : Setting<SMS> {
        override fun invoke(context: SettingContext): SMS {
            val protocol = url.substringBefore("://")
            return protocols[protocol]?.invoke(context, this)
                ?: throw IllegalArgumentException("Unknown SMS protocol: $protocol")
        }

        public companion object {
            private val protocols: MutableMap<String, (SettingContext, Settings) -> SMS> = mutableMapOf()

            init {
                registerProtocol("console") { context, _ -> 
                    ConsoleSMS(context)
                }
                registerProtocol("test") { context, _ ->
                    TestSMS(context)
                }
            }

            /**
             * Registers a protocol handler for SMS settings.
             *
             * @param protocol The protocol identifier (e.g., "console", "test", "twilio")
             * @param factory A function that creates an SMS instance from the context and settings
             */
            public fun registerProtocol(protocol: String, factory: (SettingContext, Settings) -> SMS) {
                protocols[protocol] = factory
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