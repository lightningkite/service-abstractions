package com.lightningkite.services.email.ses

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Regression test for the unbounded MIME multipart recursion in [MimeParser].
 *
 * Inbound MIME is fully attacker-controlled — anyone can send an email — and SES's payload size
 * cap does nothing to bound nesting depth: a few hundred KB of empty multipart wrappers is enough
 * to blow the JVM stack via unbounded recursion (`extractTextPart`/`extractAttachmentsFromPart`
 * had no depth limit). A `StackOverflowError` isn't even catchable by a caller's
 * `catch (e: Exception)`, so this was a real stack-overflow DoS.
 */
class MimeParserDepthTest {

    @Test
    fun extractPlainText_deeplyNestedMultipart_throwsInsteadOfOverflowing() {
        val message = MimeParser.parseRawMime(buildDeeplyNestedRawMime(depth = 100))

        assertFailsWith<IllegalArgumentException> {
            MimeParser.extractPlainText(message)
        }
    }

    @Test
    fun extractAttachments_deeplyNestedMultipart_throwsInsteadOfOverflowing() {
        val message = MimeParser.parseRawMime(buildDeeplyNestedRawMime(depth = 100))

        assertFailsWith<IllegalArgumentException> {
            MimeParser.extractAttachments(message)
        }
    }

    /**
     * Builds a raw MIME message with [depth] levels of `multipart/mixed` nesting around a single
     * leaf `text/plain` part — the shape a stack-overflow attack would use.
     */
    private fun buildDeeplyNestedRawMime(depth: Int): String {
        val boundaries = (0 until depth).map { "level$it" }
        val sb = StringBuilder()
        sb.append("From: sender@example.com\r\n")
        sb.append("To: recipient@example.com\r\n")
        sb.append("Subject: Deep Nesting Test\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/mixed; boundary=\"${boundaries[0]}\"\r\n")
        sb.append("\r\n")
        for (i in 0 until depth) {
            sb.append("--${boundaries[i]}\r\n")
            if (i < depth - 1) {
                sb.append("Content-Type: multipart/mixed; boundary=\"${boundaries[i + 1]}\"\r\n\r\n")
            } else {
                sb.append("Content-Type: text/plain\r\n\r\nleaf content\r\n")
            }
        }
        for (i in depth - 1 downTo 0) {
            sb.append("--${boundaries[i]}--\r\n")
        }
        return sb.toString()
    }
}
