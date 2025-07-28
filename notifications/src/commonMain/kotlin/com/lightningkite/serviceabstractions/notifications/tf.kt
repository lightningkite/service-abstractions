package com.lightningkite.serviceabstractions.notifications

import com.lightningkite.serviceabstractions.terraform.TerraformNeed
import com.lightningkite.serviceabstractions.terraform.TerraformServiceResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Creates a console-based notification service for development.
 * This implementation prints notifications to the console and is not intended for production use.
 */
public fun TerraformNeed<NotificationService>.console(): TerraformServiceResult<NotificationService> = TerraformServiceResult(
    need = this,
    terraformExpression = "console://",
    out = buildJsonObject {}
)

/**
 * Creates a test notification service for testing.
 * This implementation tracks sent notifications but doesn't actually send them.
 * It is not intended for production use.
 */
public fun TerraformNeed<NotificationService>.test(): TerraformServiceResult<NotificationService> = TerraformServiceResult(
    need = this,
    terraformExpression = "test://",
    out = buildJsonObject {}
)
