package com.lightningkite.services.email.javasmtp

import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.terraform.*
import kotlinx.serialization.json.JsonPrimitive

public class AwsSesDomainConfiguration internal constructor(
    public val name: String,
    public val reportingEmail: EmailAddress,
    public val dmarcPolicy: String = "quarantine",
    public val dmarcPercent: Int = 75,
    public val emailDomain: String,
)

context(emitter: TerraformEmitterAwsDomain)
public fun awsSesDomainConfiguration(
    name: String,
    reportingEmail: EmailAddress,
    dmarcPolicy: String = "quarantine",
    dmarcPercent: Int = 75,
    /**
     * The domain used in outgoing email addresses (e.g. "mydomain.com" to send as noreply@mydomain.com).
     * Defaults to the application domain. Both domains must be in the same Route53 zone.
     */
    emailDomain: String = emitter.domain,
): AwsSesDomainConfiguration {
    if (emailDomain != emitter.domain) {
        require(emitter.domain.endsWith(".$emailDomain")) {
            "emailDomain '$emailDomain' must be a parent of the application domain '${emitter.domain}' " +
            "so both reside in the same Route53 zone. " +
            "To use an unrelated domain, create a separate TerraformEmitterAwsDomain for it."
        }
    }

    val config = AwsSesDomainConfiguration(
        name = name,
        reportingEmail = reportingEmail,
        dmarcPolicy = dmarcPolicy,
        dmarcPercent = dmarcPercent,
        emailDomain = emailDomain,
    )
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

        // If emails should come from a different domain than the app domain, verify it separately.
        // Both domains must reside in the same Route53 zone (domainZoneId).
        if (emailDomain != emitter.domain) {
            require(emitter.domain.endsWith(".$emailDomain")) {
                "emailDomain '$emailDomain' must be a parent of the application domain '${emitter.domain}' " +
                "so both reside in the same Route53 zone. " +
                "To use an unrelated domain, create a separate TerraformEmitterAwsDomain for it."
            }
            val emailName = "${name}_email"

            "resource.aws_ses_domain_identity.$emailName" {
                "domain" - emailDomain
            }

            "resource.aws_route53_record.${emailName}_verification" {
                "zone_id" - emitter.domainZoneId
                "name" - "_amazonses.$emailDomain"
                "type" - "TXT"
                "ttl" - "600"
                "records" - listOf(expression("aws_ses_domain_identity.$emailName.verification_token"))
            }

            "resource.aws_ses_domain_mail_from.$emailName" {
                "domain" - expression("aws_ses_domain_identity.$emailName.domain")
                "mail_from_domain" - "mail.$emailDomain"
            }

            "resource.aws_route53_record.${emailName}_mail_from_mx" {
                "zone_id" - emitter.domainZoneId
                "name" - expression("aws_ses_domain_mail_from.$emailName.mail_from_domain")
                "type" - "MX"
                "ttl" - "600"
                "records" - listOf("10 feedback-smtp.${emitter.applicationRegion}.amazonses.com")
            }

            "resource.aws_route53_record.${emailName}_mail_from_spf" {
                "zone_id" - emitter.domainZoneId
                "name" - expression("aws_ses_domain_mail_from.$emailName.mail_from_domain")
                "type" - "TXT"
                "ttl" - "300"
                "records" - listOf("v=spf1 include:amazonses.com -all")
            }

            "resource.aws_route53_record.${emailName}_spf" {
                "zone_id" - emitter.domainZoneId
                "name" - emailDomain
                "type" - "TXT"
                "ttl" - "300"
                "records" - listOf("v=spf1 include:amazonses.com -all")
            }

            "resource.aws_ses_domain_dkim.${emailName}_dkim" {
                "domain" - expression("aws_ses_domain_identity.$emailName.domain")
            }

            "resource.aws_route53_record.${emailName}_dkim" {
                "count" - 3
                "zone_id" - emitter.domainZoneId
                "name" - "${'$'}{element(aws_ses_domain_dkim.${emailName}_dkim.dkim_tokens, count.index)}._domainkey.$emailDomain"
                "type" - "CNAME"
                "ttl" - "600"
                "records" - listOf("${'$'}{element(aws_ses_domain_dkim.${emailName}_dkim.dkim_tokens, count.index)}.dkim.amazonses.com")
            }

            "resource.aws_route53_record.${emailName}_dmarc" {
                "zone_id" - emitter.domainZoneId
                "name" - "_dmarc.$emailDomain"
                "type" - "TXT"
                "ttl" - "300"
                "records" - listOf("v=DMARC1;p=$dmarcPolicy;pct=$dmarcPercent;rua=mailto:$reportingEmail")
            }
        }
    }
    return config
}


context(emitter: TerraformEmitterAwsDomain)
public fun TerraformNeed<EmailService.Settings>.awsSesSmtp(
    sesDomainConfiguration: AwsSesDomainConfiguration,
): Unit {
    if (!EmailService.Settings.supports("smtp")) {
        throw IllegalArgumentException("You need to reference JavaSmtpEmailService in your server definition to use this.")
    }

    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = $$"""
        smtp://${aws_iam_access_key.$${name}.id}:${aws_iam_access_key.$${name}.ses_smtp_password_v4}@email-smtp.$${emitter.applicationRegion}.amazonaws.com:587?fromEmail=noreply@$${sesDomainConfiguration.emailDomain}
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
        (emitter.applicationVpc as? AwsVpc.VpcInfo)?.also { vpcInfo ->
            "resource.aws_security_group.$name" {
                "name" - "${emitter.projectPrefix}-${name}-security-group"
                "vpc_id" - vpcInfo.id
            }
            "resource.aws_vpc_security_group_ingress_rule.${name}" {
                "security_group_id" - expression("aws_security_group.$name.id")
                "from_port" - 587
                "to_port" - 587
                "ip_protocol" - "tcp"
                // Only instances inside the VPC ever reach this endpoint; don't expose it to the world.
                "cidr_ipv4" - vpcInfo.cidr
            }
            "resource.aws_vpc_endpoint.$name" {
                "vpc_id" - vpcInfo.id
                "service_name" - "com.amazonaws.${emitter.applicationRegion}.email-smtp"
                "security_group_ids" - listOf(expression("aws_security_group.$name.id"))
                "vpc_endpoint_type" - "Interface"
                // Place ENIs in the private subnets and enable private DNS so the standard SMTP hostname
                // resolves to the endpoint instead of egressing via the NAT gateway.
                "subnet_ids" - vpcInfo.privateSubnets
                "private_dns_enabled" - true
            }
        }

        // Ensure SMTP setup depends on domain being ready
        "resource.null_resource.${name}_depends_on_domain" {
            val deps = mutableListOf(
                "aws_ses_domain_identity.${sesDomainConfiguration.name}",
                "aws_ses_domain_dkim.${sesDomainConfiguration.name}_dkim"
            )
            if (sesDomainConfiguration.emailDomain != emitter.domain) {
                deps += "aws_ses_domain_identity.${sesDomainConfiguration.name}_email"
                deps += "aws_ses_domain_dkim.${sesDomainConfiguration.name}_email_dkim"
            }
            "depends_on" - deps
        }
    }
}