package com.lightningkite.services.sms

import com.lightningkite.PhoneNumber
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext

/**
 * A concrete implementation of SMS that prints messages to the console.
 * This is useful for local development and testing.
 */
public class ConsoleSMS(
    override val name: String,
    override val context: SettingContext
) : SMS {

    /**
     * Prints the SMS message to the console.
     */
    override suspend fun send(to: PhoneNumber, message: String) {
        println("SMS to $to:")
        println(message)
        println()
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Console SMS Service - No real messages are sent.")
    }
}