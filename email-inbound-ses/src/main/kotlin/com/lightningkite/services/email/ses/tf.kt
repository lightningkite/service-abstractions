package com.lightningkite.services.email.ses

import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformEmitterAwsDomain
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonPrimitive

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
 * sesDomain.awsSesDomain(reportingEmail = "dmarc@example.com".toEmailAddress())
 *
 * // Then configure inbound
 * emailInbound.awsSesInbound(
 *     sesDomainName = "ses_domain",
 *     webhookUrl = "https://api.example.com/webhooks/email"
 * )
 * ```
 *
 * ## How It Works
 *
 * 1. MX record routes incoming email to SES
 * 2. SES receipt rule receives the email
 * 3. Receipt rule publishes to SNS topic with full email content
 * 4. SNS delivers to your webhook endpoint
 *
 * ## Created Resources
 *
 * - `aws_sns_topic.{name}` - SNS topic for email notifications
 * - `aws_sns_topic_policy.{name}` - Policy allowing SES to publish
 * - `aws_ses_receipt_rule_set.{name}` - Receipt rule set
 * - `aws_ses_active_receipt_rule_set.{name}` - Activates the rule set
 * - `aws_ses_receipt_rule.{name}` - Receipt rule for the domain
 * - `aws_route53_record.{name}_inbound_mx` - MX record for receiving email
 * - `aws_s3_bucket.{name}_emails` - S3 bucket for large emails (if storeInS3 is true)
 *
 * ## Important Notes
 *
 * - **150KB limit**: Emails larger than ~150KB will have content stored in S3 instead of inline.
 *   Set [storeInS3] to true if you expect large emails.
 * - **SNS subscription**: The webhook URL subscription must be confirmed manually or via
 *   handling the SubscriptionConfirmation message in your webhook handler.
 * - **Active receipt rule set**: Only one receipt rule set can be active at a time per account/region.
 * - **Shared domain**: Must call `awsSesDomain()` first to set up domain identity, DKIM, etc.
 *
 * @param sesDomainName The name used when calling [awsSesDomain]. Resources will reference this.
 * @param webhookUrl The HTTPS URL where SNS will POST email notifications.
 *   Must be publicly accessible. For Lambda, this is typically the API Gateway URL.
 * @param storeInS3 If true, stores email content in S3 instead of inline in SNS notification.
 *   Required for emails larger than 150KB. Default is false.
 * @param s3BucketPrefix Prefix for the S3 bucket name when [storeInS3] is true.
 * @param tlsPolicy TLS policy for receiving email. "Require" enforces TLS, "Optional" allows unencrypted.
 * @param scanEnabled Whether to enable spam and virus scanning. Default is true.
 */
context(emitter: TerraformEmitterAwsDomain)
public fun TerraformNeed<EmailInboundService.Settings>.awsSesInbound(
    sesDomainName: String,
    webhookUrl: String? = null,
    storeInS3: Boolean = false,
    s3BucketPrefix: String = "ses-inbound",
    tlsPolicy: String = "Optional",
    scanEnabled: Boolean = true,
): Unit {
    if (!EmailInboundService.Settings.supports("ses")) {
        throw IllegalArgumentException("You need to reference SesEmailInboundService in your server definition to use this.")
    }

    emitter.fulfillSetting(name, JsonPrimitive("ses://"))
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    emitter.emit(name) {
        // SNS topic for email notifications
        "resource.aws_sns_topic.$name" {
            "name" - "${emitter.projectPrefix}-${name}-email-inbound"
        }

        // Data source for current AWS account
        "data.aws_caller_identity.current" {}

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
                                "AWS:SourceAccount": "${data.aws_caller_identity.current.account_id}"
                            }
                        }
                    }
                ]
            }
            """
        }

        // S3 bucket for large emails (if enabled)
        if (storeInS3) {
            "resource.aws_s3_bucket.${name}_emails" {
                "bucket_prefix" - "${emitter.projectPrefix.lowercase().replace("_", "")}-${s3BucketPrefix}"
                "force_destroy" - true
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
                                    "AWS:SourceAccount": "${data.aws_caller_identity.current.account_id}"
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

                // Store in S3 first, then notify via SNS
                "s3_action" {
                    "bucket_name" - expression("aws_s3_bucket.${name}_emails.id")
                    "position" - 1
                }
                "sns_action" {
                    "topic_arn" - expression("aws_sns_topic.$name.arn")
                    "position" - 2
                    "encoding" - "UTF-8"
                }

                "depends_on" - listOf(
                    "aws_ses_active_receipt_rule_set.$name",
                    "aws_s3_bucket_policy.${name}_emails"
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

    // Add policy statements for the application to read from S3 if enabled
    if (storeInS3) {
        emitter.policyStatements += AwsPolicyStatement(
            action = listOf("s3:GetObject", "s3:DeleteObject"),
            resource = listOf(
                $$"${aws_s3_bucket.$${name}_emails.arn}/*"
            )
        )
    }
}
