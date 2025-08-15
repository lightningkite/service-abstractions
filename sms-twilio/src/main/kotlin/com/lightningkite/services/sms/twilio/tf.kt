package com.lightningkite.services.sms.twilio

import com.lightningkite.services.sms.SMS
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformServiceResult
import kotlinx.serialization.json.buildJsonObject

/**
 * Creates a terraform configuration for a Twilio SMS service.
 * 
 * @param accountSid The Twilio account SID
 * @param authToken The Twilio auth token
 * @param fromNumber The phone number to send messages from
 * @return A TerraformServiceResult with the Twilio SMS configuration
 */
public fun TerraformNeed<SMS.Settings>.twilio(
    accountSid: String,
    authToken: String,
    fromNumber: String
): TerraformServiceResult<SMS> {
    val resourceName = name.replace("-", "_")
    
    return TerraformServiceResult(
        need = this,
        setting = "twilio://${accountSid}:${authToken}@${fromNumber}",
        requireProviders = setOf(),
        content = buildJsonObject {
        }
    )
}
