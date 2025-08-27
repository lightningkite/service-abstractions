package com.lightningkite.services.email.javasmtp

import com.lightningkite.services.email.EmailService
import com.lightningkite.services.terraform.TerraformEmitterAwsDomain
import com.lightningkite.services.terraform.TerraformEmitterAwsVpc
import com.lightningkite.services.terraform.TerraformJsonObject
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonPrimitive

/**
 * Creates a console-based email service for development.
 * This is not intended for production use.
 */
context(emitter: TerraformEmitterAwsDomain) public fun TerraformNeed<EmailService.Settings>.aws(
    reportingEmail: String,
): Unit {
    emitter.fulfillSetting(
        this@aws.name, JsonPrimitive(
            value = $$"""
        smtp://${aws_iam_access_key.$${name}.id}:${aws_iam_access_key.$${name}.ses_smtp_password_v4}@email-smtp.$${emitter.applicationRegion}.amazonaws.com:587?fromEmail=noreply@$${emitter.domain}
    """.trimIndent()
        )
    )
    emptyList<com.lightningkite.services.terraform.TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }
    emitter.emit(this.name) { //                "depends_on" - listOf("resource.aws_ses_domain_identity.$name")
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
//                "depends_on" - listOf("resource.aws_ses_domain_identity.$name")
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
        "resource.aws_ses_domain_dkim.$name" {
            "domain" - expression("aws_ses_domain_identity.$name.domain")
        }
        "resource.aws_route53_record.${name}_dkim_records" {
            "count" - 3
            "zone_id" - emitter.domainZoneId
            "name" - "${'$'}{element(aws_ses_domain_dkim.${name}.dkim_tokens, count.index)}._domainkey.${emitter.domain}"
            "type" - "CNAME"
            "ttl" - "600"
            "records" - listOf(expression("aws_ses_domain_dkim.$name.dkim_tokens[count.index]"))
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
        "resource.aws_route53_record.${name}_dmarc_txt" {
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
