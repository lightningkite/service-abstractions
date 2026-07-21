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
 * This builds an access-only [LlmAccess.Settings] — no model is bound to the URL. Select a
 * model per-call via [com.lightningkite.services.ai.LlmModelId].
 *
 * Supported URL formats (the first is most common):
 *
 * - `bedrock://?region=us-west-2` — credentials come from the default chain
 *   (env vars `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`).
 * - `bedrock://<accessKeyId>:<secretKey>@?region=us-west-2` — static credentials
 *   baked into the URL. Both fields support `${ENV_VAR}` expansion.
 * - `bedrock://<profileName>@?region=us-west-2` — reads `~/.aws/credentials` for
 *   the named profile (JVM only; other targets throw).
 *
 * If `region` is not given, `AWS_REGION` is consulted, then `us-east-1` as a last resort.
 *
 * @param modelId ignored; kept only so existing call sites that pass a model id (e.g. for
 *   IAM-scoping in terraform) keep compiling. Pass the model as an
 *   [com.lightningkite.services.ai.LlmModelId] to `stream`/`inference` instead.
 * @param region AWS region to send requests to; must be where your model access is granted.
 * @param accessKeyId when non-null, embedded in the URL as static credentials. Use the
 *   `${VAR_NAME}` syntax inside to pull from the environment.
 * @param secretAccessKey paired with [accessKeyId] — mandatory when [accessKeyId] is set.
 * @param profile alternative to static creds: reference a profile from `~/.aws/credentials`
 *   (JVM only).
 */
public fun LlmAccess.Settings.Companion.bedrock(
    modelId: String? = null,
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
        accessKeyId != null -> "$accessKeyId:$secretAccessKey@"
        profile != null -> "$profile@"
        else -> ""
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
 *
 * The URL configures access only (credentials, region) — no model is bound to it. A legacy
 * `bedrock://<model-id>?...` URL (with a model id in the authority, optionally prefixed with
 * credentials) is still accepted for backward compatibility; the model id portion is ignored.
 */
internal fun registerBedrockUrlScheme() {
    LlmAccess.Settings.register("bedrock") { name, url, context ->
        val params = parseUrlParams(url)
        val region = params["region"]?.let(::resolveEnvVars)
            ?: getEnv("AWS_REGION")
            ?: "us-east-1"

        val authority = url.substringAfter("://", "").substringBefore("?")
        val credentialsProvider = parseAuthority(authority)

        BedrockLlmAccess(
            name = name,
            context = context,
            region = region,
            credentialsProvider = credentialsProvider,
        )
    }
}

/**
 * Split the URL authority ("userinfo@model-id", "userinfo@" or just "model-id"/"") into a
 * credentials provider. The model id portion, if present, is ignored — it exists only for
 * backward compatibility with URLs minted before access settings stopped binding to a model.
 *
 * Credentials for the static/env/profile shapes are resolved eagerly here (so a bad URL fails
 * fast at settings-instantiation) and wrapped as a non-refreshing static provider. Refreshable
 * providers are the domain of the JVM-only `ai-bedrock-aws-sdk` module.
 */
private fun parseAuthority(authority: String): AwsCredentialsProvider {
    val credentials = if (authority.contains("@")) {
        val userInfo = authority.substringBefore("@")
        if (userInfo.contains(":")) {
            val accessKey = resolveEnvVars(userInfo.substringBefore(":"))
            val secretKey = resolveEnvVars(userInfo.substringAfter(":"))
            AwsCredentials(accessKey, secretKey)
        } else {
            loadProfileCredentials(userInfo)
        }
    } else {
        resolveDefaultChain()
    }
    return AwsCredentialsProvider.static(credentials)
}
