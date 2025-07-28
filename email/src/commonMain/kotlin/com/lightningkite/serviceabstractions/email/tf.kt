package com.lightningkite.serviceabstractions.email

import com.lightningkite.serviceabstractions.terraform.TerraformNeed
import com.lightningkite.serviceabstractions.terraform.TerraformServiceResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Creates a console-based email service for development.
 * This is not intended for production use.
 */
public fun TerraformNeed<EmailService>.console(): TerraformServiceResult<EmailService> = TerraformServiceResult(
    need = this,
    terraformExpression = "console://",
    out = buildJsonObject {}
)

/**
 * Creates a test email service for testing.
 * This is not intended for production use.
 */
public fun TerraformNeed<EmailService>.test(): TerraformServiceResult<EmailService> = TerraformServiceResult(
    need = this,
    terraformExpression = "test://",
    out = buildJsonObject {}
)