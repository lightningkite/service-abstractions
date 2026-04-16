package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.emailApproximatePlainText
import com.lightningkite.services.human.HumanEmailInboundService
import com.lightningkite.services.human.HumanEmailService
import com.lightningkite.services.human.HumanSmsInboundService
import com.lightningkite.services.human.HumanSmsService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

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

        // Build threading headers like a real mail server would
        val replyMessageId = "<${Uuid.random()}@human-services>"
        val references = (inbound.references + inbound.messageId).filter { it.isNotBlank() }

        // Build Gmail-style quoted body
        val originalText = inbound.plainText ?: inbound.html?.emailApproximatePlainText() ?: ""
        val quotedLines = originalText.lines().joinToString("\n") { "> $it" }
        val replyBody = "Thank you for your message.\n\n" +
                "On ${inbound.receivedAt}, ${inbound.from.let { if (it.label != null) "${it.label} <${it.value}>" else it.value.toString() }} wrote:\n" +
                quotedLines

        emailOutbound.send(
            Email(
                subject = if (inbound.subject.startsWith("Re:")) inbound.subject else "Re: ${inbound.subject}",
                from = EmailAddressWithName(emailInbound.defaultEmailAddress, "Support Bot"),
                to = listOf(inbound.from),
                plainText = replyBody,
                customHeaders = mapOf(
                    "Message-ID" to listOf(replyMessageId),
                    "In-Reply-To" to listOf(inbound.messageId),
                    "References" to listOf(references.joinToString(" ")),
                ),
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
        println("in the same tab with proper threading headers and quoted content.")
        println("Click 'Reply' on any message to continue the thread. Press Ctrl+C to stop.")
        println()

        while (true) { delay(1000) }
    }
}
