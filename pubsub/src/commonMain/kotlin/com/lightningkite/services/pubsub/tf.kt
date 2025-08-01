package com.lightningkite.services.pubsub

import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformServiceResult
import com.lightningkite.services.terraform.terraformJsonObject

/**
 * Creates a local PubSub implementation for testing.
 * This is not suitable for production use.
 */
public fun TerraformNeed<PubSub>.local(): TerraformServiceResult<PubSub> = TerraformServiceResult(
    need = this,
    terraformExpression = "local://",
    out = terraformJsonObject { }
)

/**
 * Creates a debug PubSub implementation that logs operations.
 * This is not suitable for production use.
 */
public fun TerraformNeed<PubSub>.debug(): TerraformServiceResult<PubSub> = TerraformServiceResult(
    need = this,
    terraformExpression = "debug://",
    out = terraformJsonObject { }
)