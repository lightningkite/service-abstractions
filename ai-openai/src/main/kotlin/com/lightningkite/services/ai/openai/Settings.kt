package com.lightningkite.services.ai.openai

import com.lightningkite.services.SettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.data.MediaType
import java.net.URLDecoder

/**
 * Registers the `openai://` URL scheme on [LlmAccess.Settings].
 *
 * URL format:
 * ```
 * openai://?apiKey=<key>&baseUrl=<url>&organization=<org>
 * ```
 *
 * This scheme configures *access* only (credentials, endpoint) — it is not tied to any one
 * model. Which model to use for a given call is a [com.lightningkite.services.ai.LlmModelId]
 * passed to [com.lightningkite.services.ai.LlmAccess.stream], not part of this URL. A legacy
 * `openai://<model-id>?...` URL (with a model id in the authority) is still accepted for
 * backward compatibility — the authority is simply ignored.
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
 * openai://?apiKey=${OPENAI_API_KEY}
 * openai://?apiKey=not-needed&baseUrl=http://localhost:1234/v1   # LM Studio
 * openai://?apiKey=xxx&baseUrl=http://vllm:8000/v1
 * ```
 *
 * ### LM Studio compatibility
 *
 * LM Studio exposes an OpenAI-compatible API. Point this scheme at your LM Studio server:
 * ```
 * openai://?apiKey=lm-studio&baseUrl=http://localhost:1234/v1
 * ```
 * `apiKey` can be any non-empty string — LM Studio does not validate it. The model you pass
 * to `stream`/`inference` must be **LOADED** in LM Studio (not merely installed); use the UI
 * to load before sending requests. The same advice applies to vLLM, Ollama's OpenAI-compat
 * shim at port 11434/v1, and any other OpenAI-compat server.
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
 * Prices are USD per **million** tokens: input, cached input, output. OpenAI has no cache-write charge.
 * These are inherently stale — refresh when models change.
 * Sources: https://openai.com/api/pricing/ and https://developers.openai.com/api/docs/models as of 2026-10.
 * Responses-only models (`-pro`, `-deep-research`, `gpt-5.6-cyber`) are left out: this client speaks Chat Completions.
 *
 * Lookup is **longest-prefix** against the OpenAI model id: `gpt-4o-2024-11-20` matches `gpt-4o`,
 * and `gpt-4o-mini-2024-07-18` matches `gpt-4o-mini` rather than `gpt-4o`.
 */
internal object OpenAiModelCatalog {
    internal data class Entry(
        val idPrefix: String,
        val displayName: String,
        val description: String?,
        val inputPrice: Double,
        val cacheReadPrice: Double,
        val outputPrice: Double,
        val ranking: Double,
        val supportsToolCalling: Boolean = true,
        val supportedMediaTypes: List<MediaType>,
        val supportsImageOutput: Boolean = false,
        val supportsAudioOutput: Boolean = false,
        val supportsReasoning: Boolean = false,
        val maxContextTokens: Int? = null,
        val maxOutputTokens: Int? = null,
    )

    // Chat Completions reads PNG, JPEG, WEBP and non-animated GIF images, and PDFs on vision models.
    private val imagesAndPdf = listOf(
        MediaType.Image.PNG,
        MediaType.Image.JPEG,
        MediaType.Image.WebP,
        MediaType.Image.GIF,
        MediaType.Application.Pdf,
    )

    private val entries: List<Entry> = listOf(
        Entry("gpt-6-astra", "GPT-6 Astra", "Top GPT-6 tier.", 10.00, 1.00, 50.00, 0.99, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        Entry("gpt-6.1-sol", "GPT-6.1 Sol", "Flagship GPT-6.1.", 2.00, 0.10, 10.00, 0.97, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        Entry("gpt-6.1-terra", "GPT-6.1 Terra", "Mid-tier GPT-6.1.", 1.00, 0.05, 5.00, 0.9, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        Entry("gpt-6-sol", "GPT-6 Sol", "Flagship GPT-6.", 2.00, 0.20, 10.00, 0.96, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        Entry("gpt-6-luna", "GPT-6 Luna", "Smallest GPT-6; ultra-cheap.", 0.10, 0.01, 0.50, 0.75, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        // `gpt-5.6` is an alias of `gpt-5.6-sol`; without this entry it would fall through to `gpt-5`.
        Entry("gpt-5.6", "GPT-5.6 Sol", "Flagship GPT-5.6.", 4.00, 0.40, 20.00, 0.96, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        Entry("gpt-5.6-sol", "GPT-5.6 Sol", "Flagship GPT-5.6.", 4.00, 0.40, 20.00, 0.96, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        Entry("gpt-5.6-terra", "GPT-5.6 Terra", "Mid-tier GPT-5.6.", 2.00, 0.20, 12.00, 0.88, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        Entry("gpt-5.6-luna", "GPT-5.6 Luna", "Smallest GPT-5.6.", 0.20, 0.02, 1.20, 0.72, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        Entry("gpt-5.5", "GPT-5.5", "GPT-5.5 flagship.", 5.00, 0.50, 30.00, 0.96, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        Entry("gpt-5.4", "GPT-5.4", "GPT-5.4 flagship.", 2.50, 0.25, 15.00, 0.95, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 1_050_000, maxOutputTokens = 128_000),
        Entry("gpt-5.4-mini", "GPT-5.4 Mini", "Mid-tier GPT-5.4.", 0.75, 0.075, 4.50, 0.8, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 400_000, maxOutputTokens = 128_000),
        Entry("gpt-5.4-nano", "GPT-5.4 Nano", "Smallest GPT-5.4. Deprecated; shuts down 2027-04-01.", 0.20, 0.02, 1.25, 0.6, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 400_000, maxOutputTokens = 128_000),
        Entry("gpt-5.2", "GPT-5.2", "Previous flagship.", 1.75, 0.175, 14.00, 0.93, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 400_000, maxOutputTokens = 128_000),
        Entry("gpt-5.1", "GPT-5.1", "Deprecated; shuts down 2027-04-01.", 1.25, 0.125, 10.00, 0.92, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 400_000, maxOutputTokens = 128_000),
        Entry("gpt-5", "GPT-5", "Deprecated; shuts down 2026-12-11.", 1.25, 0.125, 10.00, 0.9, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 400_000, maxOutputTokens = 128_000),
        Entry("gpt-5-mini", "GPT-5 Mini", "Deprecated; shuts down 2026-12-11.", 0.25, 0.025, 2.00, 0.8, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 400_000, maxOutputTokens = 128_000),
        Entry("gpt-5-nano", "GPT-5 Nano", "Deprecated; shuts down 2026-12-11.", 0.05, 0.005, 0.40, 0.6, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 400_000, maxOutputTokens = 128_000),
        Entry("gpt-4.1", "GPT-4.1", "Deprecated; shuts down 2027-01-14.", 2.00, 0.50, 8.00, 0.85, supportedMediaTypes = imagesAndPdf, maxContextTokens = 1_047_576, maxOutputTokens = 32_768),
        Entry("gpt-4.1-mini", "GPT-4.1 Mini", "Deprecated; shuts down 2027-01-14.", 0.40, 0.10, 1.60, 0.75, supportedMediaTypes = imagesAndPdf, maxContextTokens = 1_047_576, maxOutputTokens = 32_768),
        Entry("gpt-4o", "GPT-4o", "Deprecated; shuts down 2027-01-14.", 2.50, 1.25, 10.00, 0.85, supportedMediaTypes = imagesAndPdf, maxContextTokens = 128_000, maxOutputTokens = 16_384),
        Entry("gpt-4o-mini", "GPT-4o Mini", "Deprecated; shuts down 2027-01-14.", 0.15, 0.075, 0.60, 0.65, supportedMediaTypes = imagesAndPdf, maxContextTokens = 128_000, maxOutputTokens = 16_384),
        Entry("o3", "o3", "Reasoning model. Deprecated; shuts down 2026-12-11.", 2.00, 0.50, 8.00, 0.9, supportedMediaTypes = imagesAndPdf, supportsReasoning = true, maxContextTokens = 200_000, maxOutputTokens = 100_000),
    )

    fun lookup(modelId: String): Entry? =
        entries.filter { modelId.startsWith(it.idPrefix) }.maxByOrNull { it.idPrefix.length }

    /** Models outside the catalog (LM Studio, vLLM, OpenRouter, …) are assumed to read images only. */
    fun supportedMediaTypes(modelId: String): List<MediaType> =
        lookup(modelId)?.supportedMediaTypes ?: listOf(MediaType.Image.Any)
}

