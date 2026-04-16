// Registers the `anthropic://` URL scheme on [com.lightningkite.services.ai.LlmAccess.Settings]
// and exposes a convenience builder for typed callers.
//
// Scheme registration runs from [AnthropicLlmSettings]'s static init block. Users whose code
// touches [AnthropicLlmAccess] or calls the `anthropic(...)` extension pick it up for free.
// Callers that only reference the URL string (e.g. the settings come from config and the
// class is never named directly) should call [AnthropicLlmSettings.ensureRegistered] once at
// startup to guarantee the scheme is available before the first settings URL is resolved.
package com.lightningkite.services.ai.anthropic

import com.lightningkite.services.ai.LlmAccess

/**
 * Anchor object whose static init registers the `anthropic://` scheme. Callers who want to
 * force registration without loading any other symbol should call [ensureRegistered].
 *
 * URL format parsed at runtime:
 * ```
 * anthropic://<model-id>?apiKey=<key>&baseUrl=<url>&version=<header>&maxTokens=<int>
 * ```
 *
 * When `apiKey` is absent, the `ANTHROPIC_API_KEY` environment variable is used;
 * `${ENV_VAR}` syntax inside the value is expanded too.
 */
public object AnthropicLlmSettings {
    /** No-op call site; forces class init and therefore scheme registration. */
    public fun ensureRegistered() {}

    init {
        if (!LlmAccess.Settings.supports("anthropic")) {
            LlmAccess.Settings.register("anthropic") { name, url, context ->
                val params = parseUrlParams(url)
                val modelId = url.substringAfter("://", "").substringBefore("?")
                if (modelId.isEmpty()) {
                    throw IllegalArgumentException(
                        "Anthropic URL must include a model id: anthropic://<model-id>",
                    )
                }
                val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("ANTHROPIC_API_KEY")
                    ?: throw IllegalArgumentException(
                        "Anthropic API key not provided in URL or ANTHROPIC_API_KEY environment variable",
                    )
                val baseUrl = params["baseUrl"]?.let(::resolveEnvVars)
                    ?: AnthropicLlmAccess.DEFAULT_BASE_URL
                val version = params["version"]?.let(::resolveEnvVars)
                    ?: AnthropicLlmAccess.DEFAULT_VERSION
                val maxTokens = params["maxTokens"]?.toIntOrNull()
                    ?: AnthropicLlmAccess.DEFAULT_MAX_TOKENS
                AnthropicLlmAccess(
                    name = name,
                    context = context,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    anthropicVersion = version,
                    defaultMaxTokens = maxTokens,
                )
            }
        }
    }
}

/** Forces the `anthropic` scheme registration to happen on module init. */
@Suppress("unused")
private val registerOnClassLoad: Unit = AnthropicLlmSettings.ensureRegistered()

/**
 * Convenience builder for Anthropic-provider [LlmAccess.Settings].
 *
 * Accessing this extension forces the `anthropic://` URL scheme to be registered.
 *
 * @param modelId the Anthropic model id (e.g. `claude-haiku-4-5`)
 * @param apiKey when non-null, embedded as the `apiKey` query parameter. When null, the
 *   `ANTHROPIC_API_KEY` environment variable is consulted at instantiation time. Use
 *   `"\${VAR_NAME}"` to reference any env var by name.
 * @param baseUrl optional override for the API root (for proxies/gateways).
 */
public fun LlmAccess.Settings.Companion.anthropic(
    modelId: String,
    apiKey: String? = null,
    baseUrl: String? = null,
): LlmAccess.Settings {
    AnthropicLlmSettings.ensureRegistered()
    val parts = buildList {
        apiKey?.let { add("apiKey=$it") }
        baseUrl?.let { add("baseUrl=$it") }
    }
    val query = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    return LlmAccess.Settings("anthropic://$modelId$query")
}

/**
 * Parses URL query parameters into a map. Duplicate keys keep the last value,
 * mirroring the pattern used by the other `LlmAccess` providers.
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

/** Expands `${ENV_VAR}` placeholders using [System.getenv]. Leaves unresolved refs untouched. */
internal fun resolveEnvVars(value: String): String {
    val envVarPattern = """\$\{([^}]+)\}""".toRegex()
    return envVarPattern.replace(value) { matchResult ->
        val envVar = matchResult.groupValues[1]
        System.getenv(envVar) ?: matchResult.value
    }
}
