package com.lightningkite.services.email.ses

import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.javasmtp.awsSesDomainConfiguration
import com.lightningkite.services.test.assertPlannableAwsDomain
import kotlin.test.Test

class TfTest {
    init {
        SesEmailInboundService
    }

    @Test
    fun testSesInboundWithDomain() {
        assertPlannableAwsDomain<EmailInboundService.Settings>("aws-ses-inbound") {
            // First create shared domain resources
            val config = awsSesDomainConfiguration("ses_domain", "joseph@lightningkite.com".toEmailAddress())
            // Then create inbound-specific resources
            it.awsSesInbound(
                sesDomainConfiguration = config,
                webhookUrl = "https://api.example.com/webhooks/email"
            )
        }
    }

    @Test
    fun testSesInboundWithS3() {
        assertPlannableAwsDomain<EmailInboundService.Settings>("aws-ses-inbound-s3") {
            // First create shared domain resources
            val config = awsSesDomainConfiguration("ses_domain", "joseph@lightningkite.com".toEmailAddress())
            // Then create inbound-specific resources with S3 storage
            it.awsSesInbound(
                sesDomainConfiguration = config,
                webhookUrl = "https://api.example.com/webhooks/email",
                storeInS3 = true
            )
        }
    }
}
