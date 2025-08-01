package com.lightningkite.services.cache

import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformServiceResult
import kotlinx.serialization.json.JsonObject

public fun TerraformNeed<Cache>.ram(): TerraformServiceResult<Cache> = TerraformServiceResult<Cache>(
    need = this,
    terraformExpression = "ram://",
    out = JsonObject(mapOf())
)