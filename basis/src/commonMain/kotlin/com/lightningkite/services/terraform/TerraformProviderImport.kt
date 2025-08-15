package com.lightningkite.services.terraform

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

public data class TerraformProviderImport(
    val name: String,
    val source: String,
    val version: String
) {
    public companion object {
        public val aws: TerraformProviderImport = TerraformProviderImport(
            name = "aws",
            source  = "hashicorp/aws",
            version = "~> 5.89.0",
        )
        public val random: TerraformProviderImport = TerraformProviderImport(
            name = "random",
            source  = "hashicorp/random",
            version = "~> 3.7.1",
        )
        public val archive: TerraformProviderImport = TerraformProviderImport(
            name = "archive",
            source  = "hashicorp/archive",
            version = "~> 2.7.0",
        )
    }
    public fun toTerraformJson(): JsonObject = terraformJsonObject {
        name {
            "source" - source
            "version" - version
        }
    }
}