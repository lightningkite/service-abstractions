package com.lightningkite.services.email.javasmtp

import com.lightningkite.services.email.EmailService
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformServiceResult
import com.lightningkite.services.terraform.terraformJsonObject

/**
 * Creates a console-based email service for development.
 * This is not intended for production use.
 */
public fun TerraformNeed<EmailService>.aws(
    reportingEmail: String,
): TerraformServiceResult<EmailService> = TerraformServiceResult(
    need = this,
    terraformExpression = "console://",
    out = terraformJsonObject {
        "resource.aws_iam_user.$name" {
            "name" - "${cloudInfo.projectPrefix}-${name}-user"
        }
        "resource.aws_iam_access_key.$name" {
            "user" - tfExpression("aws_iam_user.$name.name")
        }
        "data.aws_iam_policy_document.$name" {
            "statement" {
                "actions" {
                    "add" - listOf("ses:SendRawEmail")
                }
                "resources" {
                    "add" - listOf("*")
                }
            }
        }
        "resource.aws_iam_policy.$name" {
            "name" - "${cloudInfo.projectPrefix}-${name}-policy"
            "description" - "Allows sending of e-mails via Simple Email Service"
            "policy" - tfExpression("data.aws_iam_policy_document.$name.json")
        }
        "resource.aws_iam_user_policy_attachment.$name" {
            "user" - tfExpression("aws_iam_user.$name.name")
            "policy_arn" - tfExpression("aws_iam_policy.$name.arn")
        }
        cloudInfo.applicationVpcIdExpression?.let { vpc ->
            "resource.aws_security_group.$name" {
                "name" - "${cloudInfo.projectPrefix}-${name}-security-group"
                "vpc_id" - vpc
                "ingress" {
                    "from_port" - 587
                    "to_port" - 587
                    "protocol" - "tcp"
                    "cidr_blocks" {
                        "add" - listOf("0.0.0.0/0")
                    }
                }
            }
            "resource.aws_vpc_endpoint.$name" {
                "vpc_id" - vpc
                "service_name" - "com.amazonaws.${cloudInfo.applicationProviderRegion}.email-smtp"
                "security_group_ids" {
                    "add" - listOf(tfExpression("aws_security_group.$name.id"))
                }
                "vpc_endpoint_type" - "Interface"
            }
        }
        cloudInfo.domain?.let { domain ->
            "resource.aws_ses_domain_identity.$name" {
                "domain" - domain
            }
            "resource.aws_ses_domain_mail_from.$name" {
                "domain" - domain
                "mail_from_domain" - "mail.${domain}"
            }
            "resource.aws_route53_record.${name}_mx" {
                "zone_id" - cloudInfo.domainZoneId!!
                "name" - tfExpression("aws_ses_domain_mail_from.$name.mail_from_domain")
                "type" - "MX"
                "ttl" - "600"
                "records" - listOf("10 feedback-smtp.${cloudInfo.applicationProviderRegion}.amazonses.com")
            }
            "resource.aws_route53_record.${name}" {
                "zone_id" - cloudInfo.domainZoneId!!
                "name" - "_amazonses.$domain"
                "type" - "TXT"
                "ttl" - "600"
                "records" - listOf(tfExpression("aws_ses_domain_identity.$name.verification_token"))
            }
            "resource.aws_ses_domain_dkim.$name" {
                "domain" - tfExpression("aws_ses_domain_identity.$name.domain")
            }
            "resource.aws_route53_record.${name}_dkim_records" {
                "count" - tfExpression("length(aws_ses_domain_dkim.$name.dkim_tokens)")
                "zone_id" - cloudInfo.domainZoneId!!
                "name" - "${'$'}{element(aws_ses_domain_dkim.${name}_dkim.dkim_tokens, count.index)}._domainkey.$domain"
                "type" - "CNAME"
                "ttl" - "600"
                "records" - listOf(tfExpression("aws_ses_domain_dkim.$name.dkim_tokens[count.index]"))
            }
            "resource.aws_route53_record.${name}_spf_mail_from" {
                "zone_id" - cloudInfo.domainZoneId!!
                "name" - tfExpression("aws_ses_domain_mail_from.$name.mail_from_domain")
                "type" - "TXT"
                "ttl" - "300"
                "records" - listOf(
                    "v=spf1 include:amazonses.com -all"
                )
            }
            "resource.aws_route53_record.${name}_spf_domain" {
                "zone_id" - cloudInfo.domainZoneId!!
                "name" - tfExpression("aws_ses_domain_identity.$name.domain")
                "type" - "TXT"
                "ttl" - "300"
                "records" - listOf("v=spf1 include:amazonses.com -all")
            }
            "resource.aws_route53_record.${name}_dmarc_txt" {
                "zone_id" - cloudInfo.domainZoneId!!
                "name" - "_dmarc.$domain"
                "type" - "TXT"
                "ttl" - "300"
                "records" - listOf(
                    "v=DMARC1;p=quarantine;pct=75;rua=mailto:$reportingEmail"
                )
            }
        }
    }
)
