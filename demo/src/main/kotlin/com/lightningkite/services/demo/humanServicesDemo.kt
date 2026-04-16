package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.human.HumanEmailInboundService
import com.lightningkite.services.human.HumanEmailService
import com.lightningkite.services.human.HumanSmsInboundService
import com.lightningkite.services.human.HumanSmsService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

fun main() {
    val context = TestSettingContext()

    val smsInbound = HumanSmsInboundService("sms-inbound", context, 8800)
    val smsOutbound = HumanSmsService("sms-outbound", context, 8800)
    val emailInbound = HumanEmailInboundService("email-inbound", context, 8800)
    val emailOutbound = HumanEmailService("email-outbound", context, 8800)

    // Demo back-and-forth: system auto-replies to every inbound message.
    smsInbound.onMessage { inbound ->
        println("Received SMS from ${inbound.from}: ${inbound.body}")
        smsOutbound.send(inbound.from, "Auto-reply: got \"${inbound.body}\"")
    }
    emailInbound.onMessage { inbound ->
        println("Received email from ${inbound.from.value}: ${inbound.subject}")
        emailOutbound.send(
            Email(
                subject = "Re: ${inbound.subject}",
                from = EmailAddressWithName(emailInbound.defaultEmailAddress),
                to = listOf(inbound.from),
                plainText = "Auto-reply. You wrote: ${inbound.plainText ?: inbound.html ?: ""}",
            )
        )
    }

    runBlocking {
        smsInbound.connect()
        smsOutbound.connect()
        emailInbound.connect()
        emailOutbound.connect()

        println()
        println("Dashboard is running. Open http://localhost:8800 in your browser.")
        println("Submit an inbound message; the auto-reply appears as an outbound message")
        println("in the same tab. Press Ctrl+C to stop.")
        println()

        while (true) { delay(1000) }
    }
}
