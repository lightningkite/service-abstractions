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
            version = "~> 6.28.0",
        )
        public val random: TerraformProviderImport = TerraformProviderImport(
            name = "random",
            source  = "hashicorp/random",
            version = "~> 3.7.0",
        )
        public val archive: TerraformProviderImport = TerraformProviderImport(
            name = "archive",
            source  = "hashicorp/archive",
            version = "~> 2.7.0",
        )
        public val google: TerraformProviderImport = TerraformProviderImport(
            name = "google",
            source  = "hashicorp/google",
            version = "~> 7.15.0",
        )
        public val mongodbAtlas: TerraformProviderImport = TerraformProviderImport(
            name = "mongodbatlas",
            source  = "mongodb/mongodbatlas",
            version = "~> 2.4.0",
        )
        public val local: TerraformProviderImport = TerraformProviderImport(
            name = "local",
            source = "hashicorp/local",
            version = "~> 2.6.0",
        )
        public val nullProvider: TerraformProviderImport = TerraformProviderImport(
            name = "null",
            source = "hashicorp/null",
            version = "~> 3.2.3",
        )
        public val tls: TerraformProviderImport = TerraformProviderImport(
            name = "tls",
            source = "hashicorp/tls",
            version = "~> 4.1.0",
        )
        public val ssh: TerraformProviderImport = TerraformProviderImport(
            name = "ssh",
            source = "loafoe/ssh",
            version = "~> 2.7.0",
        )
        public val stripe: TerraformProviderImport = TerraformProviderImport(
            name = "stripe",
            source = "lukasaron/stripe",
            version = "~> 3.4.0",
        )
    }
    public fun toTerraformJson(): JsonObject = terraformJsonObject {
        name {
            "source" - source
            "version" - version
        }
    }
}