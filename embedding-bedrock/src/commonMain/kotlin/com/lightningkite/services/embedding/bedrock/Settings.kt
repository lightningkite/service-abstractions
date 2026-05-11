package com.lightningkite.services.embedding.bedrock

import com.lightningkite.services.embedding.EmbeddingService

/**
 * Anchor object whose static init guarantees the `bedrock://` scheme is registered
 * on [EmbeddingService.Settings].
 */
public object BedrockEmbeddingSettings {
    /** No-op call site; forces class init and therefore scheme registration. */
    public fun ensureRegistered() {
        BedrockEmbeddingService
    }
}

/**
 * Convenience builder for Bedrock-provider [EmbeddingService.Settings].
 *
 * Supported URL formats:
 * - `bedrock://<model-id>?region=us-west-2` -- credentials from default chain
 * - `bedrock://<accessKeyId>:<secretKey>@<model-id>?region=us-west-2` -- static credentials
 * - `bedrock://<profileName>@<model-id>?region=us-west-2` -- named profile (JVM only)
 *
 * If `region` is not given, `AWS_REGION` env var is consulted, then `us-east-1` as fallback.
 */
public fun EmbeddingService.Settings.Companion.bedrock(
    modelId: String,
    region: String? = null,
    accessKeyId: String? = null,
    secretAccessKey: String? = null,
    profile: String? = null,
): EmbeddingService.Settings {
    BedrockEmbeddingService  // ensure scheme registration
    require(accessKeyId == null || profile == null) {
        "Specify either (accessKeyId, secretAccessKey) or profile -- not both."
    }
    require((accessKeyId == null) == (secretAccessKey == null)) {
        "accessKeyId and secretAccessKey must be provided together."
    }
    val authority = when {
        accessKeyId != null -> "$accessKeyId:$secretAccessKey@$modelId"
        profile != null -> "$profile@$modelId"
        else -> modelId
    }
    val query = region?.let { "?region=$it" } ?: ""
    return EmbeddingService.Settings("bedrock://$authority$query")
}

/**
 * Parse URL query parameters into a map.
 */
internal fun parseUrlParams(url: String): Map<String, String> {
    val queryString = url.substringAfter("?", "")
    if (queryString.isEmpty()) return emptyMap()
    return queryString.split("&")
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        .toMap()
}

/**
 * Register the `bedrock://` URL scheme on [EmbeddingService.Settings].
 */
internal fun registerBedrockEmbeddingUrlScheme() {
    EmbeddingService.Settings.register("bedrock") { name, url, context ->
        val params = parseUrlParams(url)
        val region = params["region"]?.let(::resolveEnvVars)
            ?: getEnv("AWS_REGION")
            ?: "us-east-1"

        val authority = url.substringAfter("://", "").substringBefore("?")
        val (credentials, modelId) = parseAuthority(authority)

        if (modelId.isEmpty()) {
            throw IllegalArgumentException(
                "Bedrock embedding URL must include a model id: " +
                        "bedrock://<model-id>[?region=...] or " +
                        "bedrock://<key>:<secret>@<model-id> or " +
                        "bedrock://<profile>@<model-id>",
            )
        }

        BedrockEmbeddingService(
            name = name,
            context = context,
            region = region,
            credentials = credentials,
        )
    }
}

/**
 * Split the URL authority into credentials and the model id part.
 */
private fun parseAuthority(authority: String): Pair<AwsCredentials, String> {
    return if (authority.contains("@")) {
        val userInfo = authority.substringBefore("@")
        val modelId = authority.substringAfter("@")
        if (userInfo.contains(":")) {
            val accessKey = resolveEnvVars(userInfo.substringBefore(":"))
            val secretKey = resolveEnvVars(userInfo.substringAfter(":"))
            AwsCredentials(accessKey, secretKey) to modelId
        } else {
            loadProfileCredentials(userInfo) to modelId
        }
    } else {
        resolveDefaultChain() to authority
    }
}
