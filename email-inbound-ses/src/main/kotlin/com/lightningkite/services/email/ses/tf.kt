package com.lightningkite.services.email.ses

import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.javasmtp.AwsSesDomainConfiguration
import com.lightningkite.services.terraform.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Creates AWS SES inbound email infrastructure using Terraform.
 *
 * This function creates the inbound-specific resources for receiving emails via SES.
 * It requires a shared SES domain to be set up first via [awsSesDomain] from `email-javasmtp`.
 *
 * ## Usage
 *
 * ```kotlin
 * // First, set up shared domain (from email-javasmtp)
 * val sesDomain = awsSesDomainConfiguration("ses_domain", reportingEmail = "dmarc@example.com".toEmailAddress())
 *
 * // Then configure inbound
 * emailInbound.awsSesInbound(
 *     sesDomainConfiguration = sesDomain,
 *     webhookUrl = "https://api.example.com/webhooks/email",
 *     storeInS3 = true,
 * )
 * ```
 *
 * ## How It Works
 *
 * 1. MX record routes incoming email to SES
 * 2. SES receipt rule receives the email
 * 3. Without [storeInS3], the rule's SNS action publishes the full email to the SNS topic.
 *    With [storeInS3], the rule's S3 action writes the raw email to the bucket and publishes a
 *    notification without content (naming the bucket and object key) to the SNS topic.
 * 4. SNS delivers to your webhook endpoint, and [SesEmailInboundService] fetches the email from
 *    the bucket if needed.
 *
 * ## Created Resources
 *
 * - `aws_sns_topic.{name}` - SNS topic for email notifications
 * - `aws_sns_topic_policy.{name}` - Policy allowing SES to publish
 * - `aws_ses_receipt_rule_set.{name}` - Receipt rule set
 * - `aws_ses_active_receipt_rule_set.{name}` - Activates the rule set
 * - `aws_ses_receipt_rule.{name}` - Receipt rule for the domain
 * - `aws_route53_record.{name}_inbound_mx` - MX record for receiving email
 * - `aws_s3_bucket.{name}_emails` - S3 bucket for raw emails (if storeInS3 is true), with a
 *   lifecycle rule expiring them after [emailRetention]
 *
 * ## Important Notes
 *
 * - **150KB limit**: With an SNS action, SES bounces emails larger than 150KB.
 *   Set [storeInS3] to true to accept emails up to 40MB.
 * - **SNS subscription**: The webhook URL subscription must be confirmed manually or via
 *   handling the SubscriptionConfirmation message in your webhook handler.
 * - **Active receipt rule set**: Only one receipt rule set can be active at a time per account/region.
 * - **Shared domain**: Must call `awsSesDomain()` first to set up domain identity, DKIM, etc.
 *
 * @param sesDomainConfiguration The shared SES domain from `awsSesDomainConfiguration`.
 * @param webhookUrl The HTTPS URL where SNS will POST email notifications.
 *   Must be publicly accessible. For Lambda, this is typically the API Gateway URL.
 * @param storeInS3 If true, SES stores every email in S3 and the notification carries only its location.
 *   Required for emails larger than 150KB. Default is false.
 * @param s3BucketPrefix Prefix for the S3 bucket name when [storeInS3] is true.
 * @param emailRetention How long raw emails stay in the bucket when [storeInS3] is true.
 * @param tlsPolicy TLS policy for receiving email. "Require" enforces TLS, "Optional" allows unencrypted.
 *   Defaults to "Require"; senders that support STARTTLS (effectively all major providers) are unaffected.
 * @param scanEnabled Whether to enable spam and virus scanning. Default is true.
 */
context(emitter: TerraformEmitterAwsDomain)
public fun TerraformNeed<EmailInboundService.Settings>.awsSesInbound(
    sesDomainConfiguration: AwsSesDomainConfiguration,
    webhookUrl: String? = null,
    storeInS3: Boolean = false,
    s3BucketPrefix: String = "ses-inbound",
    emailRetention: Duration = 30.days,
    tlsPolicy: String = "Require",
    scanEnabled: Boolean = true,
): Unit = emitSesInbound(
    sesDomainName = sesDomainConfiguration.name,
    webhookUrl = webhookUrl,
    storeInS3 = storeInS3,
    s3BucketPrefix = s3BucketPrefix,
    emailRetention = emailRetention,
    tlsPolicy = tlsPolicy,
    scanEnabled = scanEnabled,
)

context(emitter: TerraformEmitterAwsDomain)
internal fun TerraformNeed<EmailInboundService.Settings>.emitSesInbound(
    sesDomainName: String,
    webhookUrl: String?,
    storeInS3: Boolean,
    s3BucketPrefix: String,
    emailRetention: Duration,
    tlsPolicy: String,
    scanEnabled: Boolean,
) {
    if (!EmailInboundService.Settings.supports("ses")) {
        throw IllegalArgumentException("You need to reference SesEmailInboundService in your server definition to use this.")
    }

    emitter.fulfillSetting(
        name, JsonPrimitive(
            if (storeInS3) $$"ses://?topicArn=${aws_sns_topic.$${name}.arn}&bucket=${aws_s3_bucket.$${name}_emails.bucket}&region=${aws_s3_bucket.$${name}_emails.region}"
            else $$"ses://?topicArn=${aws_sns_topic.$${name}.arn}"
        )
    )
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    emitter.emit(name) {
        // SNS topic for email notifications
        "resource.aws_sns_topic.$name" {
            "name" - "${emitter.projectPrefix}-${name}-email-inbound"
        }

        // Data source for current AWS account
        "data.aws_caller_identity.$name" {}

        // SNS topic policy to allow SES to publish
        "resource.aws_sns_topic_policy.$name" {
            "arn" - expression("aws_sns_topic.$name.arn")
            "policy" - $$"""
            {
                "Version": "2012-10-17",
                "Statement": [
                    {
                        "Sid": "AllowSESPublish",
                        "Effect": "Allow",
                        "Principal": {
                            "Service": "ses.amazonaws.com"
                        },
                        "Action": "sns:Publish",
                        "Resource": "${aws_sns_topic.$${name}.arn}",
                        "Condition": {
                            "StringEquals": {
                                "AWS:SourceAccount": "${data.aws_caller_identity.$$name.account_id}"
                            }
                        }
                    }
                ]
            }
            """
        }

        if (storeInS3) {
            "resource.aws_s3_bucket.${name}_emails" {
                "bucket_prefix" - "${emitter.projectPrefix.lowercase().replace("_", "")}-${s3BucketPrefix}"
                "force_destroy" - true
            }

            "resource.aws_s3_bucket_lifecycle_configuration.${name}_emails" {
                "bucket" - expression("aws_s3_bucket.${name}_emails.id")
                "rule" - listOf(
                    terraformJsonObject {
                        "id" - "expire-emails"
                        "status" - "Enabled"
                        "filter" {
                            "prefix" - ""
                        }
                        "expiration" {
                            "days" - emailRetention.inWholeDays
                        }
                    }
                )
            }

            "resource.aws_s3_bucket_policy.${name}_emails" {
                "bucket" - expression("aws_s3_bucket.${name}_emails.id")
                "policy" - $$"""
                {
                    "Version": "2012-10-17",
                    "Statement": [
                        {
                            "Sid": "AllowSESPuts",
                            "Effect": "Allow",
                            "Principal": {
                                "Service": "ses.amazonaws.com"
                            },
                            "Action": "s3:PutObject",
                            "Resource": "arn:aws:s3:::${aws_s3_bucket.$${name}_emails.id}/*",
                            "Condition": {
                                "StringEquals": {
                                    "AWS:SourceAccount": "${data.aws_caller_identity.$$name.account_id}"
                                }
                            }
                        }
                    ]
                }
                """
            }
        }

        // SES receipt rule set
        "resource.aws_ses_receipt_rule_set.$name" {
            "rule_set_name" - "${emitter.projectPrefix}-${name}-rules"
        }

        // Activate the receipt rule set
        "resource.aws_ses_active_receipt_rule_set.$name" {
            "rule_set_name" - expression("aws_ses_receipt_rule_set.$name.rule_set_name")
        }

        // SES receipt rule
        if (storeInS3) {
            "resource.aws_ses_receipt_rule.$name" {
                "name" - "${emitter.projectPrefix}-${name}-receive"
                "rule_set_name" - expression("aws_ses_receipt_rule_set.$name.rule_set_name")
                "recipients" - listOf(emitter.domain)
                "enabled" - true
                "scan_enabled" - scanEnabled
                "tls_policy" - tlsPolicy

                // No sns_action here: SES bounces emails over 150KB when a rule has one, which
                // defeats the bucket. The S3 action's own topic gets a notification without content.
                "s3_action" {
                    "bucket_name" - expression("aws_s3_bucket.${name}_emails.id")
                    "topic_arn" - expression("aws_sns_topic.$name.arn")
                    "position" - 1
                }

                "depends_on" - listOf(
                    "aws_ses_active_receipt_rule_set.$name",
                    "aws_s3_bucket_policy.${name}_emails",
                    "aws_sns_topic_policy.$name",
                )
            }
        } else {
            "resource.aws_ses_receipt_rule.$name" {
                "name" - "${emitter.projectPrefix}-${name}-receive"
                "rule_set_name" - expression("aws_ses_receipt_rule_set.$name.rule_set_name")
                "recipients" - listOf(emitter.domain)
                "enabled" - true
                "scan_enabled" - scanEnabled
                "tls_policy" - tlsPolicy

                // Direct SNS notification with inline content
                "sns_action" {
                    "topic_arn" - expression("aws_sns_topic.$name.arn")
                    "position" - 1
                    "encoding" - "UTF-8"
                }

                "depends_on" - listOf("aws_ses_active_receipt_rule_set.$name")
            }
        }

        // MX record for receiving email on the domain
        // This is separate from the MAIL FROM MX record created by awsSesDomain
        "resource.aws_route53_record.${name}_inbound_mx" {
            "zone_id" - emitter.domainZoneId
            "name" - emitter.domain
            "type" - "MX"
            "ttl" - "600"
            "records" - listOf("10 inbound-smtp.${emitter.applicationRegion}.amazonaws.com")
        }

        // HTTPS endpoint subscription (if webhook URL provided)
        if (webhookUrl != null) {
            "resource.aws_sns_topic_subscription.${name}_webhook" {
                "topic_arn" - expression("aws_sns_topic.$name.arn")
                "protocol" - "https"
                "endpoint" - webhookUrl
                "endpoint_auto_confirms" - false
                "raw_message_delivery" - false
            }
        }

        // Ensure inbound setup depends on shared domain being ready
        "resource.null_resource.${name}_depends_on_domain" {
            "depends_on" - listOf(
                "aws_ses_domain_identity.$sesDomainName",
                "aws_ses_domain_dkim.${sesDomainName}_dkim"
            )
        }

        // Output the SNS topic ARN for manual subscription if needed
        "output.${name}_sns_topic_arn" {
            "value" - expression("aws_sns_topic.$name.arn")
            "description" - "SNS topic ARN for subscribing to inbound emails"
        }

        "output.${name}_sns_topic_name" {
            "value" - expression("aws_sns_topic.$name.name")
            "description" - "SNS topic name for inbound emails"
        }
    }

    if (storeInS3) {
        emitter.policyStatements += AwsPolicyStatement(
            action = listOf("s3:GetObject"),
            resource = listOf(
                $$"${aws_s3_bucket.$${name}_emails.arn}/*"
            )
        )
    }
}
