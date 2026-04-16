package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.ai.LlmAccess

/**
 * Anchor object whose static init guarantees the `bedrock://` scheme is registered on
 * [LlmAccess.Settings]. The scheme is actually registered from [BedrockLlmAccess]'s companion
 * init; touching this object forces that class to load so the scheme becomes available even
 * when caller code never names [BedrockLlmAccess] directly (e.g. config-driven setups).
 */
public object BedrockLlmSettings {
    /** No-op call site; forces class init and therefore scheme registration. */
    public fun ensureRegistered() {
        // Referencing BedrockLlmAccess triggers its companion init block which registers the
        // bedrock:// scheme on LlmAccess.Settings.
        BedrockLlmAccess
    }
}

/**
 * Convenience builder for Bedrock-provider [LlmAccess.Settings]. The bedrock:// URL scheme
 * is registered on the [LlmAccess.Settings] companion as soon as [BedrockLlmAccess] is
 * loaded, so touching this function implicitly wires it up.
 *
 * Supported URL formats (the first is most common):
 *
 * - `bedrock://<model-id>?region=us-west-2` — credentials come from the default chain
 *   (env vars `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`).
 * - `bedrock://<accessKeyId>:<secretKey>@<model-id>?region=us-west-2` — static credentials
 *   baked into the URL. Both fields support `${ENV_VAR}` expansion.
 * - `bedrock://<profileName>@<model-id>?region=us-west-2` — reads `~/.aws/credentials` for
 *   the named profile (JVM only; other targets throw).
 *
 * If `region` is not given, `AWS_REGION` is consulted, then `us-east-1` as a last resort.
 *
 * @param modelId Bedrock model id (e.g. `anthropic.claude-sonnet-4-5-20250929-v1:0`).
 * @param region AWS region to send requests to; must be where your model access is granted.
 * @param accessKeyId when non-null, embedded in the URL as static credentials. Use the
 *   `${VAR_NAME}` syntax inside to pull from the environment.
 * @param secretAccessKey paired with [accessKeyId] — mandatory when [accessKeyId] is set.
 * @param profile alternative to static creds: reference a profile from `~/.aws/credentials`
 *   (JVM only).
 */
public fun LlmAccess.Settings.Companion.bedrock(
    modelId: String,
    region: String? = null,
    accessKeyId: String? = null,
    secretAccessKey: String? = null,
    profile: String? = null,
): LlmAccess.Settings {
    // Touch BedrockLlmAccess to guarantee the URL scheme is registered.
    BedrockLlmAccess
    require(accessKeyId == null || profile == null) {
        "Specify either (accessKeyId, secretAccessKey) or profile — not both."
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
    return LlmAccess.Settings("bedrock://$authority$query")
}

/**
 * Parse URL query parameters into a map. Later duplicates win, matching the other LLM
 * provider adapters.
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
 * Register the `bedrock://` URL scheme on [LlmAccess.Settings.Companion]. Called lazily from
 * [BedrockLlmAccess]'s class-init block, which runs the first time any Bedrock type is
 * referenced from caller code (including the [bedrock] builder above).
 */
internal fun registerBedrockUrlScheme() {
    LlmAccess.Settings.register("bedrock") { name, url, context ->
        val params = parseUrlParams(url)
        val region = params["region"]?.let(::resolveEnvVars)
            ?: getEnv("AWS_REGION")
            ?: "us-east-1"

        val authority = url.substringAfter("://", "").substringBefore("?")
        val (credentials, modelId) = parseAuthority(authority)

        if (modelId.isEmpty()) {
            throw IllegalArgumentException(
                "Bedrock URL must include a model id: bedrock://<model-id>[?region=...] " +
                        "or bedrock://<key>:<secret>@<model-id> or bedrock://<profile>@<model-id>",
            )
        }

        BedrockLlmAccess(
            name = name,
            context = context,
            region = region,
            credentials = credentials,
        )
    }
}

/**
 * Split the URL authority ("userinfo@model-id" or just "model-id") into credentials and the
 * model id part. Unknown shapes resolve through the default credential chain.
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
