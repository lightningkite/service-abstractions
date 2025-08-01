package com.lightningkite.services.sms

import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformServiceResult
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
