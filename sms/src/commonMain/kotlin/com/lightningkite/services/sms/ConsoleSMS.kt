package com.lightningkite.services.sms

import com.lightningkite.PhoneNumber
import com.lightningkite.services.SettingContext

/**
 * A concrete implementation of SMS that prints messages to the console.
 * This is useful for local development and testing.
 */
public class ConsoleSMS(
    override val name: String,
    context: SettingContext
) : MetricTrackingSMS(context) {

    /**
     * Prints the SMS message to the console.
     */
    override suspend fun sendImplementation(to: PhoneNumber, message: String) {
        println("SMS to $to:")
        println(message)
        println()
    }
}