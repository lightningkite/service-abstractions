package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.toPhoneNumber
import com.lightningkite.services.sms.*
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates the SMS subsystem via `test://`, which - unlike `console://` - captures every
 * sent message so the demo can read back what would have gone out.
 */
fun main() = runBlocking {
    val context = TestSettingContext()
    val sms = SMS.Settings("test://")("sms", context) as TestSMS

    val recipient = "+15551234567".toPhoneNumber()
    sms.send(recipient, "Your verification code is 482913")
    sms.send(recipient, "Reminder: your appointment is tomorrow at 10am")

    println("Total sent: ${sms.messageHistory.size}")
    println("Last message: ${sms.lastMessageSent}")
}
