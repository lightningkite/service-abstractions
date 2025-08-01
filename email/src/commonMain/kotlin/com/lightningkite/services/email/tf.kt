package com.lightningkite.services.email

import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformServiceResult
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