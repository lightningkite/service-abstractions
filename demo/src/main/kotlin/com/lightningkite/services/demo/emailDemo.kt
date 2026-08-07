package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.email.*
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates the email subsystem via `test://`, which - unlike `console://` - captures
 * every sent message so the demo can read back what would have gone out.
 */
fun main() = runBlocking {
    val context = TestSettingContext()
    val email = EmailService.Settings("test://")("email", context) as TestEmailService

    email.send(
        Email(
            subject = "Welcome!",
            from = EmailAddressWithName("no-reply@example.com", "Example App"),
            to = listOf(EmailAddressWithName("new-user@example.com")),
            plainText = "Thanks for signing up.",
        )
    )
    email.send(
        Email(
            subject = "Your receipt",
            from = EmailAddressWithName("no-reply@example.com", "Example App"),
            to = listOf(EmailAddressWithName("new-user@example.com")),
            plainText = "Here's your receipt for last month.",
        )
    )

    println("Total sent: ${email.sentEmails.size}")
    println("Last email: ${email.lastEmail()}")
    println("Emails to new-user@example.com: ${email.emailsTo("new-user@example.com").map { it.subject }}")
}
