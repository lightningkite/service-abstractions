package com.lightningkite.services.email.javasmtp

import com.lightningkite.EmailAddress
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.assertPlannableAwsDomain
import com.lightningkite.services.test.expensive
import com.lightningkite.toEmailAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class TfTest {
    init {
        JavaSmtpEmailService
    }
    @Test fun test() {
        assertPlannableAwsDomain<EmailService.Settings>("aws-ses") {
            it.awsSesSmtp("joseph@lightningkite.com".toEmailAddress())
        }
    }
//    @Test fun expensiveTest() {
//        expensive {
//            withAwsSpecific(
//                name = "aws-email",
//                domain = true,
//                vpc = false,
//                serializer = EmailService.Settings.serializer(),
//                fulfill = {
//                    it.aws("joseph@lightningkite.com")
//                },
//                test = {
//                    runBlocking {
//                        it.send(Email(
//                            subject = "Full Test Email",
//                            to = listOf(EmailAddressWithName("joseph@lightningkite.com", "Joseph")),
//                            plainText = "This is a test from a real, external domain.  Kinda nuts."
//                        ))
//                    }
//                }
//            )
//        }
//    }
}