@file:OptIn(Untested::class)

package com.lightningkite.services.email.mailgun

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.Untested
import com.lightningkite.services.data.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `ReceivedEmail.replyTo` is genuinely nullable — it must be null when the inbound email has no
 * Reply-To header, not a fabricated "unknown@example.com" address. The other three providers
 * (SES, IMAP, SendGrid) already return null for an absent Reply-To; Mailgun was the outlier
 * because it ran the header through the same blank-fallback helper used for the non-nullable
 * `from` field.
 */
class MailgunReplyToTest {

    private val service = MailgunEmailInboundService(
        name = "test",
        context = TestSettingContext(),
        apiKey = "key-test",
        domain = "example.com",
    )

    private fun baseFormData(replyTo: String? = null): Map<String, List<String>> = buildMap {
        put("from", listOf("sender@example.com"))
        put("To", listOf("recipient@example.com"))
        put("subject", listOf("Test"))
        put("body-plain", listOf("Hello"))
        replyTo?.let { put("Reply-To", listOf(it)) }
    }

    @Test
    fun missingReplyToHeaderYieldsNullNotFabricatedAddress() {
        val email = service.parseMailgunEmail(baseFormData(replyTo = null), MediaType.Application.FormUrlEncoded)
        assertNull(email.replyTo)
    }

    @Test
    fun presentReplyToHeaderIsParsedNormally() {
        val email = service.parseMailgunEmail(
            baseFormData(replyTo = "Reply Person <reply@example.com>"),
            MediaType.Application.FormUrlEncoded,
        )
        assertEquals("reply@example.com", email.replyTo?.value?.raw)
        assertEquals("Reply Person", email.replyTo?.label)
    }
}
