package com.lightningkite.services.email

import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformServiceResult
import kotlinx.serialization.json.buildJsonObject

/**
 * Creates a console-based email service for development.
 * This is not intended for production use.
 */
public fun TerraformNeed<EmailService.Settings>.console(): TerraformServiceResult<EmailService.Settings> = TerraformServiceResult(
    need = this,
    setting = "console://",
    requireProviders = setOf(),
    content = buildJsonObject {}
)

/**
 * Creates a test email service for testing.
 * This is not intended for production use.
 */
public fun TerraformNeed<EmailService.Settings>.test(): TerraformServiceResult<EmailService.Settings> = TerraformServiceResult(
    need = this,
    setting = "test://",
    requireProviders = setOf(),
    content = buildJsonObject {}
)