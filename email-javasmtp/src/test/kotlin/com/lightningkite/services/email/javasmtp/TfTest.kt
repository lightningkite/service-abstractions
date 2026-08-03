package com.lightningkite.services.email.javasmtp

import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.test.assertPlannableAwsDomain
import kotlin.test.Test

class TfTest {
    init {
        // Referencing JavaSmtpEmailService triggers its companion init block, which registers
        // the smtp URL scheme. The reference itself is the point of this line.
        @Suppress("UNUSED_EXPRESSION")
        JavaSmtpEmailService
    }

    @Suppress("DEPRECATION")
    @Test
    fun testSesSmtpWithDomain() {
        assertPlannableAwsDomain<EmailService.Settings>("aws-ses") {
            // First create shared domain resources
            awsSesDomain("ses_domain", "joseph@lightningkite.com".toEmailAddress())
            // Then create SMTP-specific resources (this fulfills the "test" setting)
            it.awsSesSmtp(sesDomainName = "ses_domain")
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun testLegacySesSmtp() {
        assertPlannableAwsDomain<EmailService.Settings>("aws-ses-legacy") {
            it.awsSesSmtpLegacy("joseph@lightningkite.com".toEmailAddress())
        }
    }
}
