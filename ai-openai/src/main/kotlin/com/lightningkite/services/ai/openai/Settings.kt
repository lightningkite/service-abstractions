package com.lightningkite.services.ai.openai

import com.lightningkite.services.SettingContext
import com.lightningkite.services.ai.LlmAccess
import java.net.URLDecoder

/**
 * Registers the `openai://` URL scheme on [LlmAccess.Settings].
 *
 * URL format:
 * ```
 * openai://<model-id>?apiKey=<key>&baseUrl=<url>&organization=<org>
 * ```
 *
 * Parameters:
 * - `apiKey` — API key. Supports `${ENV_VAR}` substitution. If omitted, falls back to the
 *   `OPENAI_API_KEY` environment variable.
 * - `baseUrl` — Optional override for the API root. Defaults to `https://api.openai.com/v1`.
 *   Set this to target any OpenAI-compatible server — **LM Studio** (`http://localhost:1234/v1`),
 *   **vLLM**, **Groq**, **Together**, **Fireworks**, **OpenRouter**, etc. Whatever speaks the
 *   `/chat/completions` API shape works unchanged.
 * - `organization` — Optional `OpenAI-Organization` header value for routing under a specific
 *   OpenAI org.
 *
 * Examples:
 * ```
 * openai://gpt-5?apiKey=${OPENAI_API_KEY}
 * openai://gpt-4o-mini?apiKey=${OPENAI_API_KEY}
 * openai://qwen2.5-coder?apiKey=not-needed&baseUrl=http://localhost:1234/v1   # LM Studio
 * openai://meta-llama/Llama-3-8B-Instruct?apiKey=xxx&baseUrl=http://vllm:8000/v1
 * ```
 *
 * ### LM Studio compatibility
 *
 * LM Studio exposes an OpenAI-compatible API. Point this scheme at your LM Studio server:
 * ```
 * openai://<model-id>?apiKey=lm-studio&baseUrl=http://localhost:1234/v1
 * ```
 * `apiKey` can be any non-empty string — LM Studio does not validate it. The model must be
 * **LOADED** in LM Studio (not merely installed); use the UI to load before sending requests.
 * The same advice applies to vLLM, Ollama's OpenAI-compat shim at port 11434/v1, and any
 * other OpenAI-compat server.
 *
 * ### Registration
 *
 * Scheme registration fires when this class is first loaded. Code that instantiates
 * `LlmAccess.Settings("openai://...")` without otherwise referencing this module (e.g.
 * deserialization from config, lookup by string) must call [OpenAiLlmSettings.ensureRegistered]
 * once during startup — typically alongside its other provider-module registrations — or
 * the scheme won't be found.
 *
 * [LlmAccess.Settings.Companion] is shared with other provider modules (anthropic, bedrock,
 * ollama, …) — this block only registers the `openai` scheme and never touches the others.
 */
public object OpenAiLlmSettings {
    /** Call once at startup to guarantee the `openai` scheme is registered. Idempotent. */
    public fun ensureRegistered() {}

    init {
        LlmAccess.Settings.register("openai") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = url.substringAfter("://", "").substringBefore("?")
            require(modelName.isNotBlank()) {
                "openai:// URL must include a model id: openai://<model>?apiKey=..."
            }
            val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                ?: System.getenv("OPENAI_API_KEY")
                ?: throw IllegalArgumentException(
                    "OpenAI API key not provided in URL 'apiKey' param or OPENAI_API_KEY env var"
                )
            val baseUrl = params["baseUrl"]?.let(::resolveEnvVars)?.trimEnd('/')
                ?: "https://api.openai.com/v1"
            val organization = params["organization"]?.let(::resolveEnvVars)
            OpenAiLlmAccess(
                name = name,
                context = context,
                baseUrl = baseUrl,
                apiKey = apiKey,
                organization = organization,
            )
        }
    }
}

/** Forces the `openai` scheme registration to happen on module init. */
@Suppress("unused")
private val registerOnClassLoad: Unit = OpenAiLlmSettings.ensureRegistered()

/**
 * Split a URL's query string into a map, URL-decoding both keys and values.
 *
 * Without decoding, query values containing `%`, `+`, `&`, or `=` (which can legally appear
 * in API keys and base URLs) would be corrupted silently.
 */
internal fun parseUrlParams(url: String): Map<String, String> {
    val queryString = url.substringAfter("?", "")
    if (queryString.isEmpty()) return emptyMap()
    return queryString.split("&")
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            } else {
                null
            }
        }
        .toMap()
}

/** Replaces `${VAR}` tokens with `System.getenv("VAR")`; throws if the env var is unset. */
internal fun resolveEnvVars(value: String): String {
    val envVarPattern = """\$\{([^}]+)\}""".toRegex()
    return envVarPattern.replace(value) { m ->
        val name = m.groupValues[1]
        System.getenv(name)
            ?: throw IllegalArgumentException("Environment variable \$$name not set")
    }
}

/**
 * Known OpenAI models with local metadata (display name, pricing, rough ranking). Used to augment
 * the bare `/v1/models` list with information the API doesn't expose.
 *
 * Prices are USD per **million** tokens. These are inherently stale — refresh when models change.
 * Sources: https://openai.com/api/pricing/ as of 2025-10.
 *
 * Lookup is **longest-prefix** against the OpenAI model id: `gpt-4o-2024-11-20` matches `gpt-4o`,
 * and `gpt-4o-audio-preview-2024-10-01` matches `gpt-4o-audio-preview` rather than `gpt-4o`.
 */
internal object OpenAiModelCatalog {
    internal data class Entry(
        val idPrefix: String,
        val displayName: String,
        val description: String?,
        val inputPrice: Double,
        val outputPrice: Double,
        val ranking: Double,
    )

    private val entries: List<Entry> = listOf(
        Entry("gpt-5-nano", "GPT-5 Nano", "Smallest GPT-5; ultra-cheap.", 0.05, 0.40, 0.6),
        Entry("gpt-5-mini", "GPT-5 Mini", "Mid-tier GPT-5.", 0.25, 2.00, 0.8),
        Entry("gpt-5", "GPT-5", "Flagship GPT-5.", 1.25, 10.00, 0.95),
        Entry("gpt-4.1-nano", "GPT-4.1 Nano", "Smallest GPT-4.1.", 0.10, 0.40, 0.55),
        Entry("gpt-4.1-mini", "GPT-4.1 Mini", "Mid-tier GPT-4.1.", 0.40, 1.60, 0.75),
        Entry("gpt-4.1", "GPT-4.1", "Full GPT-4.1 capabilities.", 2.00, 8.00, 0.85),
        Entry("gpt-4o-mini", "GPT-4o Mini", "Cheap fast multimodal.", 0.15, 0.60, 0.65),
        // Audio-in/audio-out variant of GPT-4o; priced substantially above the plain gpt-4o
        // tier because audio tokens cost more. Listed as a longer prefix so longest-match
        // routes `gpt-4o-audio-preview-*` here rather than to the base `gpt-4o` entry.
        // TODO: verify pricing against https://openai.com/api/pricing/
        Entry("gpt-4o-audio-preview", "GPT-4o Audio", "Multimodal GPT-4o with audio I/O.", 2.50, 10.00, 0.85),
        Entry("gpt-4o", "GPT-4o", "Full multimodal GPT-4o.", 2.50, 10.00, 0.85),
        Entry("o4-mini", "o4-mini", "Reasoning, mid-tier.", 1.10, 4.40, 0.85),
        Entry("o3-mini", "o3-mini", "Reasoning, cheaper.", 1.10, 4.40, 0.80),
        Entry("o3", "o3", "Full reasoning model.", 2.00, 8.00, 0.90),
        Entry("o1-mini", "o1-mini", "Legacy reasoning.", 3.00, 12.00, 0.75),
        Entry("o1", "o1", "Legacy reasoning flagship.", 15.00, 60.00, 0.80),
        Entry("gpt-3.5-turbo", "GPT-3.5 Turbo", "Legacy.", 0.50, 1.50, 0.35),
    )

    fun lookup(modelId: String): Entry? =
        entries.filter { modelId.startsWith(it.idPrefix) }.maxByOrNull { it.idPrefix.length }
}
