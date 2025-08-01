package com.lightningkite.services.files

import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformServiceResult
import kotlinx.serialization.json.buildJsonObject

/**
 * Creates a local file system for development purposes.
 * This is not meant for production use.
 *
 * @param directory The directory to store files in
 * @param serveDirectory The URL path for serving files
 * @param baseUrl The base URL for serving files
 * @param secret An optional secret for signing URLs
 */
public fun TerraformNeed<PublicFileSystem>.local(
    directory: String,
    serveDirectory: String = "files",
    baseUrl: String = "http://localhost:8080",
    secret: String? = null
): TerraformServiceResult<PublicFileSystem> {
    val secretPart = secret?.let { "?secret=$it&baseUrl=$baseUrl" } ?: "?baseUrl=$baseUrl"
    return TerraformServiceResult<PublicFileSystem>(
        need = this,
        terraformExpression = "file://$directory$secretPart#$serveDirectory",
        out = buildJsonObject {  }
    )
}