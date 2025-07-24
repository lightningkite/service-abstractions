package com.lightningkite.serviceabstractions.cache

import com.lightningkite.serviceabstractions.terraform.TerraformJsonObject
import com.lightningkite.serviceabstractions.terraform.TerraformNeed
import com.lightningkite.serviceabstractions.terraform.TerraformServiceResult
import kotlinx.serialization.KSerializer

public fun TerraformNeed<Cache>.ram(): TerraformServiceResult<Cache> = TerraformServiceResult<Cache>(
    need = this,
    terraformExpression = "ram://",
    out = TerraformJsonObject()
)