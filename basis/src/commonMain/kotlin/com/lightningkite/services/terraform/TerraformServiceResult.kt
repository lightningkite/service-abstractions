package com.lightningkite.services.terraform

import kotlinx.serialization.json.JsonObject

public class TerraformServiceResult<T>(
    public val need: TerraformNeed<T>,
    public val terraformExpression: String,
//    public val requireProviders: Set<TerraformProvider>,  // TODO: Should we do this?
    public val out: JsonObject
)