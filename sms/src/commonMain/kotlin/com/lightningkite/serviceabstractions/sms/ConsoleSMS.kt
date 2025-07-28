package com.lightningkite.serviceabstractions.sms

import com.lightningkite.PhoneNumber
import com.lightningkite.serviceabstractions.SettingContext

/**
 * A concrete implementation of SMS that prints messages to the console.
 * This is useful for local development and testing.
 */
public class ConsoleSMS(context: SettingContext) : MetricTrackingSMS(context) {
    
    /**
     * Prints the SMS message to the console.
     */
    override suspend fun sendImplementation(to: PhoneNumber, message: String) {
        println("SMS to $to:")
        println(message)
        println()
    }
}