package com.lightningkite.services.terraform

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public class TerraformServiceResult<T> constructor(
    public val need: TerraformNeed<T>,
    public val setting: JsonElement,
    public val requireProviders: Set<TerraformProviderImport>,
    public val nonStandardProviders: Set<TerraformProvider> = setOf(),
    public val content: JsonObject,
) {
    public constructor(
        need: TerraformNeed<T>,
        setting: String,
        requireProviders: Set<TerraformProviderImport>,
        nonStandardProviders: Set<TerraformProvider> = setOf(),
        content: JsonObject,
    ):this(need, JsonPrimitive(setting), requireProviders, nonStandardProviders, content)
}

