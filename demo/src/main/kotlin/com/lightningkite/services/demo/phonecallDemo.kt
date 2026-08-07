package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.toPhoneNumber
import com.lightningkite.services.phonecall.*
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates the phone call subsystem via `test://`: start an outbound call, speak to it,
 * then hang up, reading back what the fake provider recorded along the way.
 */
fun main() = runBlocking {
    val context = TestSettingContext()
    val phone = PhoneCallService.Settings("test://")("phone", context) as TestPhoneCallService

    val callId = phone.startCall("+15559876543".toPhoneNumber())
    println("Started call $callId, status: ${phone.getCallStatus(callId)}")

    phone.speak(callId, "Hello, this is a demo of the phone call service.")
    println("Spoken messages: ${phone.spokenMessages}")

    phone.hangup(callId)
    println("Status after hangup: ${phone.getCallStatus(callId)}")
}
