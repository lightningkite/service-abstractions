package com.lightningkite.serviceabstractions.sms.twilio

import com.lightningkite.serviceabstractions.sms.SMS
import com.lightningkite.serviceabstractions.terraform.TerraformNeed
import com.lightningkite.serviceabstractions.terraform.TerraformServiceResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Creates a terraform configuration for a Twilio SMS service.
 * 
 * @param accountSid The Twilio account SID
 * @param authToken The Twilio auth token
 * @param fromNumber The phone number to send messages from
 * @return A TerraformServiceResult with the Twilio SMS configuration
 */
public fun TerraformNeed<SMS>.twilio(
    accountSid: String,
    authToken: String,
    fromNumber: String
): TerraformServiceResult<SMS> {
    val resourceName = name.replace("-", "_")
    
    return TerraformServiceResult(
        need = this,
        terraformExpression = "twilio://${accountSid}:${authToken}@${fromNumber}",
        out = buildJsonObject {
            // AWS Secrets Manager resource for storing Twilio credentials
            put("resource", buildJsonObject {
                put("aws_secretsmanager_secret", buildJsonObject {
                    put(resourceName, buildJsonObject {
                        put("name", "${name}-twilio-credentials")
                        put("description", "Twilio credentials for ${name}")
                        put("recovery_window_in_days", 7)
                        put("tags", buildJsonObject {
                            put("Name", name)
                            put("Service", "Twilio SMS")
                        })
                    })
                })
                
                put("aws_secretsmanager_secret_version", buildJsonObject {
                    put(resourceName, buildJsonObject {
                        put("secret_id", "\${aws_secretsmanager_secret.${resourceName}.id}")
                        put("secret_string", buildJsonObject {
                            put("accountSid", accountSid)
                            put("authToken", authToken)
                            put("fromNumber", fromNumber)
                        }.toString())
                    })
                })
            })
        }
    )
}

/**
 * Creates a terraform configuration for a Twilio SMS service using AWS SSM parameters.
 * 
 * @param accountSidParam The SSM parameter name for the Twilio account SID
 * @param authTokenParam The SSM parameter name for the Twilio auth token
 * @param fromNumberParam The SSM parameter name for the phone number to send messages from
 * @return A TerraformServiceResult with the Twilio SMS configuration
 */
public fun TerraformNeed<SMS>.twilioFromSsm(
    accountSidParam: String,
    authTokenParam: String,
    fromNumberParam: String
): TerraformServiceResult<SMS> {
    return TerraformServiceResult(
        need = this,
        terraformExpression = "twilio://\${data.aws_ssm_parameter.${accountSidParam}.value}:\${data.aws_ssm_parameter.${authTokenParam}.value}@\${data.aws_ssm_parameter.${fromNumberParam}.value}",
        out = buildJsonObject {
            put("data", buildJsonObject {
                put("aws_ssm_parameter", buildJsonObject {
                    put(accountSidParam, buildJsonObject {
                        put("name", accountSidParam)
                    })
                    put(authTokenParam, buildJsonObject {
                        put("name", authTokenParam)
                    })
                    put(fromNumberParam, buildJsonObject {
                        put("name", fromNumberParam)
                    })
                })
            })
        }
    )
}