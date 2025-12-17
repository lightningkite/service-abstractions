package com.lightningkite.services.email.javasmtp

import com.lightningkite.services.email.EmailService
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.assertPlannableAwsDomain
import com.lightningkite.toEmailAddress
import kotlin.test.Test

class TfTest {
    init {
        JavaSmtpEmailService
    }

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
