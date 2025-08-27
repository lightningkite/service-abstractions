package com.lightningkite.services.terraform

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

public data class TerraformProvider(
    public val import: TerraformProviderImport,
    public val alias: String? = null,
    public val out: JsonObject,
) {
}

public fun TerraformJsonObject.include(providers: Collection<TerraformProvider>) {
    "provider" {
        providers.groupBy { it.import.name }.forEach {
            it.key - it.value.map { value ->
                if(value.alias != null) JsonObject(value.out + ("alias" to JsonPrimitive(value.alias)))
                else value.out
            }
        }
    }
}

public val TerraformProvider.awsRegion: String? get() = out["region"]?.jsonPrimitive?.contentOrNull