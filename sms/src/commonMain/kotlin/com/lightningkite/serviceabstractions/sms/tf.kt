package com.lightningkite.serviceabstractions.sms

import com.lightningkite.serviceabstractions.terraform.TerraformNeed
import com.lightningkite.serviceabstractions.terraform.TerraformServiceResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Creates a terraform configuration for a console SMS service.
 * This is useful for local development and testing.
 * 
 * @return A TerraformServiceResult with the console SMS configuration
 */
public fun TerraformNeed<SMS>.console(): TerraformServiceResult<SMS> = 
    TerraformServiceResult(
        need = this,
        terraformExpression = "console://",
        out = buildJsonObject {}
    )
