package com.lightningkite.services.email.ses

import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.javasmtp.awsSesDomain
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.assertPlannableAwsDomain
import com.lightningkite.toEmailAddress
import kotlin.test.Test

class TfTest {
    init {
        SesEmailInboundService
    }

    @Test
    fun testSesInboundWithDomain() {
        assertPlannableAwsDomain<EmailInboundService.Settings>("aws-ses-inbound") {
            // First create shared domain resources
            TerraformNeed<Unit>("ses_domain").awsSesDomain("joseph@lightningkite.com".toEmailAddress())
            // Then create inbound-specific resources
            it.awsSesInbound(
                sesDomainName = "ses_domain",
                webhookUrl = "https://api.example.com/webhooks/email"
            )
        }
    }

    @Test
    fun testSesInboundWithS3() {
        assertPlannableAwsDomain<EmailInboundService.Settings>("aws-ses-inbound-s3") {
            // First create shared domain resources
            TerraformNeed<Unit>("ses_domain").awsSesDomain("joseph@lightningkite.com".toEmailAddress())
            // Then create inbound-specific resources with S3 storage
            it.awsSesInbound(
                sesDomainName = "ses_domain",
                webhookUrl = "https://api.example.com/webhooks/email",
                storeInS3 = true
            )
        }
    }
}
