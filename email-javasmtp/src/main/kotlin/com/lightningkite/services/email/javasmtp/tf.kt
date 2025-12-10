package com.lightningkite.services.email.javasmtp

import com.lightningkite.EmailAddress
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.terraform.TerraformEmitterAwsDomain
import com.lightningkite.services.terraform.TerraformEmitterAwsVpc
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared AWS SES domain configuration for both sending and receiving emails.
 *
 * This function creates the foundational SES infrastructure that is shared between
 * outbound (SMTP) and inbound (receipt rules) email services:
 *
 * - **Domain identity**: Verifies domain ownership with SES
 * - **DKIM signing**: Enables email authentication via DKIM
 * - **SPF records**: Configures SPF for email deliverability
 * - **DMARC policy**: Sets up DMARC reporting and policy
 * - **MAIL FROM domain**: Configures custom MAIL FROM for better deliverability
 *
 * ## Usage
 *
 * Call this function once per domain, then reference it from both outbound and inbound:
 *
 * ```kotlin
 * // First, set up the shared domain (call once)
 * sesDomain.awsSesDomain(reportingEmail = "dmarc@example.com".toEmailAddress())
 *
 * // Then configure outbound SMTP (references shared domain)
 * emailOutbound.awsSesSmtp(sesDomainName = "ses_domain")
 *
 * // And/or configure inbound (references shared domain)
 * emailInbound.awsSesInbound(sesDomainName = "ses_domain", webhookUrl = "https://...")
 * ```
 *
 * ## Created Resources
 *
 * - `aws_ses_domain_identity.{name}` - Domain verification
 * - `aws_ses_domain_mail_from.{name}` - Custom MAIL FROM domain
 * - `aws_ses_domain_dkim.{name}_dkim` - DKIM configuration
 * - `aws_route53_record.{name}` - Domain verification TXT record
 * - `aws_route53_record.{name}_mx` - MX record for MAIL FROM domain
 * - `aws_route53_record.{name}_dkim_records` - DKIM CNAME records
 * - `aws_route53_record.{name}_spf_mail_from` - SPF for MAIL FROM
 * - `aws_route53_record.{name}_spf_domain` - SPF for main domain
 * - `aws_route53_record.{name}_dmarc` - DMARC policy
 *
 * @param reportingEmail Email address to receive DMARC aggregate reports
 * @param dmarcPolicy DMARC policy: "none", "quarantine", or "reject". Default is "quarantine".
 * @param dmarcPercent Percentage of messages to apply DMARC policy to (0-100). Default is 75.
 */
context(emitter: TerraformEmitterAwsDomain)
public fun awsSesDomain(
    name: String,
    reportingEmail: EmailAddress,
    dmarcPolicy: String = "quarantine",
    dmarcPercent: Int = 75,
): Unit {
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    emitter.emit(name) {
        // Domain identity - verifies domain ownership
        "resource.aws_ses_domain_identity.$name" {
            "domain" - emitter.domain
        }

        // Domain verification TXT record
        "resource.aws_route53_record.${name}_verification" {
            "zone_id" - emitter.domainZoneId
            "name" - "_amazonses.${emitter.domain}"
            "type" - "TXT"
            "ttl" - "600"
            "records" - listOf(expression("aws_ses_domain_identity.$name.verification_token"))
        }

        // MAIL FROM domain configuration
        "resource.aws_ses_domain_mail_from.$name" {
            "domain" - expression("aws_ses_domain_identity.$name.domain")
            "mail_from_domain" - "mail.${emitter.domain}"
        }

        // MX record for MAIL FROM domain (for bounce handling)
        "resource.aws_route53_record.${name}_mail_from_mx" {
            "zone_id" - emitter.domainZoneId
            "name" - expression("aws_ses_domain_mail_from.$name.mail_from_domain")
            "type" - "MX"
            "ttl" - "600"
            "records" - listOf("10 feedback-smtp.${emitter.applicationRegion}.amazonses.com")
        }

        // SPF record for MAIL FROM domain
        "resource.aws_route53_record.${name}_mail_from_spf" {
            "zone_id" - emitter.domainZoneId
            "name" - expression("aws_ses_domain_mail_from.$name.mail_from_domain")
            "type" - "TXT"
            "ttl" - "300"
            "records" - listOf("v=spf1 include:amazonses.com -all")
        }

        // SPF record for main domain
        "resource.aws_route53_record.${name}_spf" {
            "zone_id" - emitter.domainZoneId
            "name" - emitter.domain
            "type" - "TXT"
            "ttl" - "300"
            "records" - listOf("v=spf1 include:amazonses.com -all")
        }

        // DKIM configuration
        "resource.aws_ses_domain_dkim.${name}_dkim" {
            "domain" - expression("aws_ses_domain_identity.$name.domain")
        }

        // DKIM CNAME records (3 records)
        "resource.aws_route53_record.${name}_dkim" {
            "count" - 3
            "zone_id" - emitter.domainZoneId
            "name" - "${'$'}{element(aws_ses_domain_dkim.${name}_dkim.dkim_tokens, count.index)}._domainkey.${emitter.domain}"
            "type" - "CNAME"
            "ttl" - "600"
            "records" - listOf("${'$'}{element(aws_ses_domain_dkim.${name}_dkim.dkim_tokens, count.index)}.dkim.amazonses.com")
        }

        // DMARC policy record
        "resource.aws_route53_record.${name}_dmarc" {
            "zone_id" - emitter.domainZoneId
            "name" - "_dmarc.${emitter.domain}"
            "type" - "TXT"
            "ttl" - "300"
            "records" - listOf("v=DMARC1;p=$dmarcPolicy;pct=$dmarcPercent;rua=mailto:$reportingEmail")
        }
    }
}

/**
 * Resource name helper for referencing shared SES domain resources.
 *
 * Use this to get the correct resource names when referencing shared domain infrastructure.
 */
public object SesSharedResources {
    /** Returns the domain identity resource reference */
    public fun domainIdentity(sesDomainName: String): String = "aws_ses_domain_identity.$sesDomainName"

    /** Returns the domain identity domain expression */
    public fun domainIdentityDomain(sesDomainName: String): String = "aws_ses_domain_identity.$sesDomainName.domain"
}

/**
 * Creates AWS SES SMTP configuration for sending emails.
 *
 * This function creates the SMTP-specific resources for sending emails via SES.
 * It requires a shared SES domain to be set up first via [awsSesDomain].
 *
 * ## Usage
 *
 * ```kotlin
 * // First, set up shared domain
 * sesDomain.awsSesDomain(reportingEmail = "dmarc@example.com".toEmailAddress())
 *
 * // Then configure SMTP
 * emailService.awsSesSmtp(sesDomainName = "ses_domain")
 * ```
 *
 * ## Created Resources
 *
 * - `aws_iam_user.{name}` - IAM user for SMTP credentials
 * - `aws_iam_access_key.{name}` - Access key with SMTP password
 * - `aws_iam_policy.{name}` - Policy allowing ses:SendRawEmail
 * - VPC endpoint (if in VPC context)
 *
 * @param sesDomainName The name used when calling [awsSesDomain]. Resources will reference this.
 */
context(emitter: TerraformEmitterAwsDomain)
public fun TerraformNeed<EmailService.Settings>.awsSesSmtp(
    sesDomainName: String,
): Unit {
    if (!EmailService.Settings.supports("smtp")) {
        throw IllegalArgumentException("You need to reference JavaSmtpEmailService in your server definition to use this.")
    }

    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = $$"""
        smtp://${aws_iam_access_key.$${name}.id}:${aws_iam_access_key.$${name}.ses_smtp_password_v4}@email-smtp.$${emitter.applicationRegion}.amazonaws.com:587?fromEmail=noreply@$${emitter.domain}
    """.trimIndent()
        )
    )

    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    emitter.emit(name) {
        // IAM user for SMTP credentials
        "resource.aws_iam_user.$name" {
            "name" - "${emitter.projectPrefix}-${name}-user"
        }

        // Access key (generates SMTP password)
        "resource.aws_iam_access_key.$name" {
            "user" - expression("aws_iam_user.$name.name")
        }

        // IAM policy document for sending email
        "data.aws_iam_policy_document.$name" {
            "statement" {
                "actions" - listOf("ses:SendRawEmail")
                "resources" - listOf("*")
            }
        }

        // IAM policy
        "resource.aws_iam_policy.$name" {
            "name" - "${emitter.projectPrefix}-${name}-policy"
            "description" - "Allows sending of e-mails via Simple Email Service"
            "policy" - expression("data.aws_iam_policy_document.$name.json")
        }

        // Attach policy to user
        "resource.aws_iam_user_policy_attachment.$name" {
            "user" - expression("aws_iam_user.$name.name")
            "policy_arn" - expression("aws_iam_policy.$name.arn")
        }

        // VPC endpoint for SMTP (if in VPC context)
        if (emitter is TerraformEmitterAwsVpc) {
            "resource.aws_security_group.$name" {
                "name" - "${emitter.projectPrefix}-${name}-security-group"
                "vpc_id" - expression(emitter.applicationVpc.id)
            }
            "resource.aws_vpc_security_group_ingress_rule.${name}" {
                "security_group_id" - expression("aws_security_group.$name.id")
                "from_port" - 587
                "to_port" - 587
                "ip_protocol" - "tcp"
                "cidr_ipv4" - "0.0.0.0/0"
            }
            "resource.aws_vpc_endpoint.$name" {
                "vpc_id" - expression(emitter.applicationVpc.id)
                "service_name" - "com.amazonaws.${emitter.applicationRegion}.email-smtp"
                "security_group_ids" - listOf(expression("aws_security_group.$name.id"))
                "vpc_endpoint_type" - "Interface"
            }
        }

        // Ensure SMTP setup depends on domain being ready
        "resource.null_resource.${name}_depends_on_domain" {
            "depends_on" - listOf(
                "aws_ses_domain_identity.$sesDomainName",
                "aws_ses_domain_dkim.${sesDomainName}_dkim"
            )
        }
    }
}

/**
 * Legacy function for backwards compatibility.
 *
 * @deprecated Use [awsSesDomain] + [awsSesSmtp] for new code. This creates both domain and SMTP in one call.
 */
@Deprecated(
    message = "Use awsSesDomain() + awsSesSmtp() separately for better modularity",
    replaceWith = ReplaceWith("awsSesDomain(reportingEmail); awsSesSmtp(sesDomainName = name)")
)
context(emitter: TerraformEmitterAwsDomain)
public fun TerraformNeed<EmailService.Settings>.awsSesSmtpLegacy(
    reportingEmail: EmailAddress,
): Unit {
    if (!EmailService.Settings.supports("smtp")) {
        throw IllegalArgumentException("You need to reference JavaSmtpEmailService in your server definition to use this.")
    }
    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = $$"""
        smtp://${aws_iam_access_key.$${name}.id}:${aws_iam_access_key.$${name}.ses_smtp_password_v4}@email-smtp.$${emitter.applicationRegion}.amazonaws.com:587?fromEmail=noreply@$${emitter.domain}
    """.trimIndent()
        )
    )
    emptyList<com.lightningkite.services.terraform.TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }
    emitter.emit(this.name) {
        "resource.aws_iam_user.$name" {
            "name" - "${emitter.projectPrefix}-${name}-user"
        }
        "resource.aws_iam_access_key.$name" {
            "user" - expression("aws_iam_user.$name.name")
        }
        "data.aws_iam_policy_document.$name" {
            "statement" {
                "actions" - listOf("ses:SendRawEmail")
                "resources" - listOf("*")
            }
        }
        "resource.aws_iam_policy.$name" {
            "name" - "${emitter.projectPrefix}-${name}-policy"
            "description" - "Allows sending of e-mails via Simple Email Service"
            "policy" - expression("data.aws_iam_policy_document.$name.json")
        }
        "resource.aws_iam_user_policy_attachment.$name" {
            "user" - expression("aws_iam_user.$name.name")
            "policy_arn" - expression("aws_iam_policy.$name.arn")
        }
        if (emitter is TerraformEmitterAwsVpc) {
            "resource.aws_security_group.$name" {
                "name" - "${emitter.projectPrefix}-${name}-security-group"
                "vpc_id" - expression(emitter.applicationVpc.id)
            }
            "resource.aws_vpc_security_group_ingress_rule.${name}" {
                "security_group_id" - expression("aws_security_group.$name.id")
                "from_port" - 587
                "to_port" - 587
                "ip_protocol" - "tcp"
                "cidr_ipv4" - "0.0.0.0/0"
            }
            "resource.aws_vpc_endpoint.$name" {
                "vpc_id" - expression(emitter.applicationVpc.id)
                "service_name" - "com.amazonaws.${emitter.applicationRegion}.email-smtp"
                "security_group_ids" - listOf(expression("aws_security_group.$name.id"))
                "vpc_endpoint_type" - "Interface"
            }
        }
        "resource.aws_ses_domain_identity.$name" {
            "domain" - emitter.domain
        }
        "resource.aws_ses_domain_mail_from.$name" {
            "domain" - emitter.domain
            "mail_from_domain" - "mail.${emitter.domain}"
        }
        "resource.aws_route53_record.${name}_mx" {
            "zone_id" - emitter.domainZoneId
            "name" - expression("aws_ses_domain_mail_from.$name.mail_from_domain")
            "type" - "MX"
            "ttl" - "600"
            "records" - listOf("10 feedback-smtp.${emitter.applicationRegion}.amazonses.com")
        }
        "resource.aws_route53_record.${name}" {
            "zone_id" - emitter.domainZoneId
            "name" - "_amazonses.${emitter.domain}"
            "type" - "TXT"
            "ttl" - "600"
            "records" - listOf(expression("aws_ses_domain_identity.$name.verification_token"))
        }
        "resource.aws_ses_domain_dkim.${name}_dkim" {
            "domain" - expression("aws_ses_domain_identity.$name.domain")
        }
        "resource.aws_route53_record.${name}_dkim_records" {
            "count" - 3
            "zone_id" - emitter.domainZoneId
            "name" - "${'$'}{element(aws_ses_domain_dkim.${name}_dkim.dkim_tokens, count.index)}._domainkey.${emitter.domain}"
            "type" - "CNAME"
            "ttl" - "600"
            "records" - listOf(expression("aws_ses_domain_dkim.${name}_dkim.dkim_tokens[count.index]"))
        }
        "resource.aws_route53_record.${name}_spf_mail_from" {
            "zone_id" - emitter.domainZoneId
            "name" - expression("aws_ses_domain_mail_from.$name.mail_from_domain")
            "type" - "TXT"
            "ttl" - "300"
            "records" - listOf(
                "v=spf1 include:amazonses.com -all"
            )
        }
        "resource.aws_route53_record.${name}_spf_domain" {
            "zone_id" - emitter.domainZoneId
            "name" - expression("aws_ses_domain_identity.$name.domain")
            "type" - "TXT"
            "ttl" - "300"
            "records" - listOf("v=spf1 include:amazonses.com -all")
        }
        "resource.aws_route53_record.${name}_route_53_dmarc_txt" {
            "zone_id" - emitter.domainZoneId
            "name" - "_dmarc.${emitter.domain}"
            "type" - "TXT"
            "ttl" - "300"
            "records" - listOf(
                "v=DMARC1;p=quarantine;pct=75;rua=mailto:$reportingEmail"
            )
        }
    }
}
