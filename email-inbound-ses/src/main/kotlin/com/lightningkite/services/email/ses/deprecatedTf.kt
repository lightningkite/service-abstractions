package com.lightningkite.services.email.ses

import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.terraform.*
import kotlin.time.Duration.Companion.days

/**
 * Same as the [awsSesInbound] overload taking an `AwsSesDomainConfiguration`, but referencing the
 * shared SES domain by name and defaulting [tlsPolicy] to "Optional".
 */
@Deprecated("Use the version with the sesDomainConfiguration parameter instead")
context(emitter: TerraformEmitterAwsDomain)
public fun TerraformNeed<EmailInboundService.Settings>.awsSesInbound(
    sesDomainName: String,
    webhookUrl: String? = null,
    storeInS3: Boolean = false,
    s3BucketPrefix: String = "ses-inbound",
    tlsPolicy: String = "Optional",
    scanEnabled: Boolean = true,
): Unit = emitSesInbound(
    sesDomainName = sesDomainName,
    webhookUrl = webhookUrl,
    storeInS3 = storeInS3,
    s3BucketPrefix = s3BucketPrefix,
    emailRetention = 30.days,
    tlsPolicy = tlsPolicy,
    scanEnabled = scanEnabled,
)
