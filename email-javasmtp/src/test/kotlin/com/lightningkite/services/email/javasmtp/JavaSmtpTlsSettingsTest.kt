// by Claude
package com.lightningkite.services.email.javasmtp

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.email.EmailService
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TLS used to be inferred purely from port number (465 -> SSL, 587 -> STARTTLS), so any
 * non-standard port (e.g. the project's own Mailtrap doc example on port 2525) silently sent SMTP
 * credentials and message bodies in cleartext. Per the maintainer's decision, TLS is now required
 * by default regardless of port, and disabling it requires an explicit opt-out on the URL.
 */
class JavaSmtpTlsSettingsTest {

    private companion object {
        init {
            // The "smtp" URL scheme is registered by JavaSmtpEmailService's companion `init` block,
            // which only runs once the class is loaded. Nothing else in this test references the
            // class before going through EmailService.Settings' URL parser, so force it here.
            JavaSmtpEmailService.Companion
        }
    }

    private fun serviceFor(url: String): JavaSmtpEmailService =
        EmailService.Settings(url).invoke("email", TestSettingContext()) as JavaSmtpEmailService

    @Test
    fun nonStandardPortRequiresTlsByDefault() {
        val service = serviceFor("smtp://user:pass@smtp.mailtrap.io:2525?fromEmail=noreply@example.com")
        // Port 2525 matches neither 465 nor 587, but TLS must still be required by default.
        assertEquals("true", service.session.getProperty("mail.smtp.starttls.enable"))
        assertEquals("true", service.session.getProperty("mail.smtp.starttls.required"))
        assertEquals("false", service.session.getProperty("mail.smtp.ssl.enable"))
    }

    @Test
    fun port465UsesImplicitSslByDefault() {
        val service = serviceFor("smtp://user:pass@smtp.example.com:465?fromEmail=noreply@example.com")
        assertEquals("true", service.session.getProperty("mail.smtp.ssl.enable"))
        assertEquals("false", service.session.getProperty("mail.smtp.starttls.enable"))
    }

    @Test
    fun explicitOptOutDisablesTls() {
        val service = serviceFor("smtp://user:pass@smtp.mailtrap.io:2525?fromEmail=noreply@example.com&insecure=true")
        assertEquals("false", service.session.getProperty("mail.smtp.starttls.enable"))
        assertEquals("false", service.session.getProperty("mail.smtp.starttls.required"))
        assertEquals("false", service.session.getProperty("mail.smtp.ssl.enable"))
    }

    @Test
    fun optOutIsNotTheDefault() {
        // Omitting the param at all (no "?insecure=..." in the URL) must NOT silently downgrade.
        val service = serviceFor("smtp://user:pass@smtp.example.com:25?fromEmail=noreply@example.com")
        assertTrue(
            service.session.getProperty("mail.smtp.starttls.enable") == "true",
            "TLS must be required unless the caller explicitly opts out"
        )
    }
}
