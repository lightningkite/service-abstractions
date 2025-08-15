package com.lightningkite.services.notifications

import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformServiceResult
import kotlinx.serialization.json.buildJsonObject

/**
 * Creates a console-based notification service for development.
 * This implementation prints notifications to the console and is not intended for production use.
 */
public fun TerraformNeed<NotificationService.Settings>.console(): TerraformServiceResult<NotificationService> = TerraformServiceResult(
    need = this,
    setting = "console://",
    requireProviders = setOf(),
    content = buildJsonObject {}
)

/**
 * Creates a test notification service for testing.
 * This implementation tracks sent notifications but doesn't actually send them.
 * It is not intended for production use.
 */
public fun TerraformNeed<NotificationService.Settings>.test(): TerraformServiceResult<NotificationService> = TerraformServiceResult(
    need = this,
    setting = "test://",
    requireProviders = setOf(),
    content = buildJsonObject {}
)
