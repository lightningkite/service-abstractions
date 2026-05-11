package com.lightningkite.services.ai.openai

import com.lightningkite.services.ai.LlmAccess
import java.net.URLEncoder

/**
 * Convenience builders for [LlmAccess.Settings] targeting OpenAI and every major
 * OpenAI-compatible provider. Each builder sets the correct `baseUrl` and default
 * API-key env var for its provider so callers only need to specify the model id.
 *
 * All functions produce a standard `openai://` settings URL, so the OpenAI module
 * handles the HTTP calls. No extra dependencies needed.
 *
 * ## Caching notes
 *
 * OpenAI-compatible APIs generally **ignore** explicit `cacheBreak` flags.
 * OpenAI itself auto-caches prompts >1024 tokens; other providers vary.
 * For guaranteed prompt-caching control, use Anthropic or Bedrock directly.
 */

// ---------------------------------------------------------------------------
// Internal helper
// ---------------------------------------------------------------------------

private fun buildOpenAiUrl(
    modelId: String,
    apiKey: String?,
    baseUrl: String?,
    extraParams: Map<String, String> = emptyMap(),
): LlmAccess.Settings {
    OpenAiLlmSettings.ensureRegistered()
    val parts = buildList {
        apiKey?.let { add("apiKey=${encode(it)}") }
        baseUrl?.let { add("baseUrl=${encode(it)}") }
        extraParams.forEach { (k, v) -> add("${encode(k)}=${encode(v)}") }
    }
    val query = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    return LlmAccess.Settings("openai://$modelId$query")
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

// ---------------------------------------------------------------------------
// OpenAI (direct)
// ---------------------------------------------------------------------------

/**
 * Direct OpenAI API access.
 *
 * ```
 * LlmAccess.Settings.openai("gpt-4o")                          // uses OPENAI_API_KEY env var
 * LlmAccess.Settings.openai("o3", apiKey = "\${MY_KEY}")
 * ```
 *
 * @param modelId OpenAI model id (e.g. `gpt-4o`, `gpt-4.1-mini`, `o3`).
 * @param apiKey API key. Supports `${ENV_VAR}` syntax. Falls back to `OPENAI_API_KEY`.
 * @param organization Optional `OpenAI-Organization` header value.
 */
public fun LlmAccess.Settings.Companion.openai(
    modelId: String,
    apiKey: String? = null,
    organization: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey,
    baseUrl = null,
    extraParams = buildMap { organization?.let { put("organization", it) } },
)

// ---------------------------------------------------------------------------
// OpenRouter — https://openrouter.ai
// ---------------------------------------------------------------------------

/**
 * [OpenRouter](https://openrouter.ai) — unified API for 200+ models from every major provider.
 *
 * ```
 * LlmAccess.Settings.openRouter("anthropic/claude-sonnet-4")
 * LlmAccess.Settings.openRouter("openai/gpt-4o", apiKey = "\${OPENROUTER_API_KEY}")
 * ```
 *
 * @param modelId OpenRouter model id (e.g. `anthropic/claude-sonnet-4`, `google/gemini-2.5-pro`).
 * @param apiKey Falls back to `OPENROUTER_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.openRouter(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${OPENROUTER_API_KEY}",
    baseUrl = "https://openrouter.ai/api/v1",
)

// ---------------------------------------------------------------------------
// Groq — https://groq.com
// ---------------------------------------------------------------------------

/**
 * [Groq](https://groq.com) — ultra-fast LPU inference.
 *
 * ```
 * LlmAccess.Settings.groq("llama-3.3-70b-versatile")
 * LlmAccess.Settings.groq("mixtral-8x7b-32768")
 * ```
 *
 * @param modelId Groq model id (e.g. `llama-3.3-70b-versatile`, `gemma2-9b-it`).
 * @param apiKey Falls back to `GROQ_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.groq(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${GROQ_API_KEY}",
    baseUrl = "https://api.groq.com/openai/v1",
)

// ---------------------------------------------------------------------------
// Together AI — https://together.ai
// ---------------------------------------------------------------------------

/**
 * [Together AI](https://together.ai) — large catalogue of open-source models.
 *
 * ```
 * LlmAccess.Settings.togetherAi("meta-llama/Llama-3.3-70B-Instruct-Turbo")
 * LlmAccess.Settings.togetherAi("Qwen/Qwen2.5-Coder-32B-Instruct")
 * ```
 *
 * @param modelId Together model id.
 * @param apiKey Falls back to `TOGETHER_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.togetherAi(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${TOGETHER_API_KEY}",
    baseUrl = "https://api.together.xyz/v1",
)

// ---------------------------------------------------------------------------
// Fireworks AI — https://fireworks.ai
// ---------------------------------------------------------------------------

/**
 * [Fireworks AI](https://fireworks.ai) — fast inference for open models.
 *
 * ```
 * LlmAccess.Settings.fireworks("accounts/fireworks/models/llama-v3p3-70b-instruct")
 * ```
 *
 * @param modelId Fireworks model id (often `accounts/fireworks/models/<name>`).
 * @param apiKey Falls back to `FIREWORKS_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.fireworks(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${FIREWORKS_API_KEY}",
    baseUrl = "https://api.fireworks.ai/inference/v1",
)

// ---------------------------------------------------------------------------
// Perplexity — https://perplexity.ai
// ---------------------------------------------------------------------------

/**
 * [Perplexity](https://docs.perplexity.ai) — search-augmented generation.
 *
 * ```
 * LlmAccess.Settings.perplexity("sonar-pro")
 * LlmAccess.Settings.perplexity("sonar-reasoning-pro")
 * ```
 *
 * @param modelId Perplexity model id (e.g. `sonar`, `sonar-pro`, `sonar-reasoning-pro`).
 * @param apiKey Falls back to `PERPLEXITY_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.perplexity(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${PERPLEXITY_API_KEY}",
    baseUrl = "https://api.perplexity.ai",
)

// ---------------------------------------------------------------------------
// DeepSeek — https://deepseek.com
// ---------------------------------------------------------------------------

/**
 * [DeepSeek](https://platform.deepseek.com) — high-capability reasoning models.
 *
 * ```
 * LlmAccess.Settings.deepSeek("deepseek-chat")
 * LlmAccess.Settings.deepSeek("deepseek-reasoner")
 * ```
 *
 * @param modelId DeepSeek model id (e.g. `deepseek-chat`, `deepseek-reasoner`).
 * @param apiKey Falls back to `DEEPSEEK_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.deepSeek(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${DEEPSEEK_API_KEY}",
    baseUrl = "https://api.deepseek.com/v1",
)

// ---------------------------------------------------------------------------
// Mistral — https://mistral.ai
// ---------------------------------------------------------------------------

/**
 * [Mistral AI](https://docs.mistral.ai) — European frontier models.
 *
 * ```
 * LlmAccess.Settings.mistral("mistral-large-latest")
 * LlmAccess.Settings.mistral("codestral-latest")
 * ```
 *
 * @param modelId Mistral model id (e.g. `mistral-large-latest`, `mistral-small-latest`).
 * @param apiKey Falls back to `MISTRAL_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.mistral(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${MISTRAL_API_KEY}",
    baseUrl = "https://api.mistral.ai/v1",
)

// ---------------------------------------------------------------------------
// xAI (Grok) — https://x.ai
// ---------------------------------------------------------------------------

/**
 * [xAI](https://docs.x.ai) — Grok models.
 *
 * ```
 * LlmAccess.Settings.xai("grok-3")
 * LlmAccess.Settings.xai("grok-3-mini")
 * ```
 *
 * @param modelId xAI model id (e.g. `grok-3`, `grok-3-mini`).
 * @param apiKey Falls back to `XAI_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.xai(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${XAI_API_KEY}",
    baseUrl = "https://api.x.ai/v1",
)

// ---------------------------------------------------------------------------
// Cerebras — https://cerebras.ai
// ---------------------------------------------------------------------------

/**
 * [Cerebras](https://inference.cerebras.ai) — wafer-scale inference, extremely fast.
 *
 * ```
 * LlmAccess.Settings.cerebras("llama-3.3-70b")
 * ```
 *
 * @param modelId Cerebras model id.
 * @param apiKey Falls back to `CEREBRAS_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.cerebras(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${CEREBRAS_API_KEY}",
    baseUrl = "https://api.cerebras.ai/v1",
)

// ---------------------------------------------------------------------------
// NVIDIA NIM — https://build.nvidia.com
// ---------------------------------------------------------------------------

/**
 * [NVIDIA NIM](https://build.nvidia.com) — GPU-optimised inference for open models.
 *
 * ```
 * LlmAccess.Settings.nvidiaNim("meta/llama-3.3-70b-instruct")
 * LlmAccess.Settings.nvidiaNim("nvidia/llama-3.1-nemotron-70b-instruct")
 * ```
 *
 * @param modelId NIM model id (e.g. `meta/llama-3.3-70b-instruct`).
 * @param apiKey Falls back to `NVIDIA_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.nvidiaNim(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${NVIDIA_API_KEY}",
    baseUrl = "https://integrate.api.nvidia.com/v1",
)

// ---------------------------------------------------------------------------
// SambaNova — https://sambanova.ai
// ---------------------------------------------------------------------------

/**
 * [SambaNova](https://cloud.sambanova.ai) — RDU-accelerated inference.
 *
 * ```
 * LlmAccess.Settings.sambaNova("Meta-Llama-3.3-70B-Instruct")
 * ```
 *
 * @param modelId SambaNova model id.
 * @param apiKey Falls back to `SAMBANOVA_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.sambaNova(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${SAMBANOVA_API_KEY}",
    baseUrl = "https://api.sambanova.ai/v1",
)

// ---------------------------------------------------------------------------
// Google Gemini (OpenAI-compatible) — https://ai.google.dev
// ---------------------------------------------------------------------------

/**
 * [Google Gemini](https://ai.google.dev) via its OpenAI-compatible endpoint.
 *
 * ```
 * LlmAccess.Settings.geminiOpenAi("gemini-2.5-pro")
 * LlmAccess.Settings.geminiOpenAi("gemini-2.5-flash")
 * ```
 *
 * @param modelId Gemini model id (e.g. `gemini-2.5-pro`, `gemini-2.5-flash`).
 * @param apiKey Falls back to `GEMINI_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.geminiOpenAi(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${GEMINI_API_KEY}",
    baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
)

// ---------------------------------------------------------------------------
// Azure OpenAI — https://azure.microsoft.com/products/ai-services/openai-service
// ---------------------------------------------------------------------------

/**
 * [Azure OpenAI Service](https://learn.microsoft.com/azure/ai-services/openai/).
 * Azure uses a non-standard URL structure:
 * `https://<resource>.openai.azure.com/openai/deployments/<deployment>/`
 *
 * ```
 * LlmAccess.Settings.azureOpenAi(
 *     resourceName = "my-resource",
 *     deploymentId = "gpt-4o",
 *     apiKey = "\${AZURE_OPENAI_API_KEY}",
 * )
 * ```
 *
 * @param resourceName Azure resource name from your deployment.
 * @param deploymentId Deployment name (often matches the model name).
 * @param apiKey Falls back to `AZURE_OPENAI_API_KEY` env var.
 * @param apiVersion Azure API version string. Defaults to `2024-10-21`.
 */
public fun LlmAccess.Settings.Companion.azureOpenAi(
    resourceName: String,
    deploymentId: String,
    apiKey: String? = null,
    apiVersion: String = "2024-10-21",
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = deploymentId,
    apiKey = apiKey ?: "\${AZURE_OPENAI_API_KEY}",
    baseUrl = "https://$resourceName.openai.azure.com/openai/deployments/$deploymentId?api-version=$apiVersion",
)

// ---------------------------------------------------------------------------
// Cohere — https://cohere.com
// ---------------------------------------------------------------------------

/**
 * [Cohere](https://docs.cohere.com) — Command R models via OpenAI-compatible endpoint.
 *
 * ```
 * LlmAccess.Settings.cohere("command-r-plus")
 * LlmAccess.Settings.cohere("command-r")
 * ```
 *
 * @param modelId Cohere model id (e.g. `command-r-plus`, `command-r`).
 * @param apiKey Falls back to `COHERE_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.cohere(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${COHERE_API_KEY}",
    baseUrl = "https://api.cohere.ai/compatibility/v1",
)

// ---------------------------------------------------------------------------
// Databricks — https://databricks.com
// ---------------------------------------------------------------------------

/**
 * [Databricks](https://docs.databricks.com/aws/en/machine-learning/model-serving/) — models served
 * from your Databricks workspace via OpenAI-compatible endpoint.
 *
 * ```
 * LlmAccess.Settings.databricks(
 *     workspaceUrl = "https://my-workspace.cloud.databricks.com",
 *     modelId = "databricks-meta-llama-3-3-70b-instruct",
 * )
 * ```
 *
 * @param workspaceUrl Full URL of your Databricks workspace (e.g. `https://xxx.cloud.databricks.com`).
 * @param modelId Serving endpoint name or model id.
 * @param apiKey Falls back to `DATABRICKS_TOKEN` env var.
 */
public fun LlmAccess.Settings.Companion.databricks(
    workspaceUrl: String,
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${DATABRICKS_TOKEN}",
    baseUrl = "${workspaceUrl.trimEnd('/')}/serving-endpoints",
)

// ---------------------------------------------------------------------------
// Lepton AI — https://lepton.ai
// ---------------------------------------------------------------------------

/**
 * [Lepton AI](https://lepton.ai) — serverless inference.
 *
 * ```
 * LlmAccess.Settings.leptonAi("llama-3.3-70b")
 * ```
 *
 * @param modelId Lepton model id.
 * @param apiKey Falls back to `LEPTON_API_KEY` env var.
 */
public fun LlmAccess.Settings.Companion.leptonAi(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${LEPTON_API_KEY}",
    baseUrl = "https://api.lepton.ai/v1",
)

// ---------------------------------------------------------------------------
// OVHcloud AI Endpoints — https://ovhcloud.com
// ---------------------------------------------------------------------------

/**
 * [OVHcloud AI Endpoints](https://endpoints.ai.cloud.ovh.net) — EU-hosted models.
 *
 * ```
 * LlmAccess.Settings.ovhcloud("Mistral-7B-Instruct-v0.3")
 * ```
 *
 * @param modelId OVHcloud model id.
 * @param apiKey Falls back to `OVH_AI_ENDPOINTS_ACCESS_TOKEN` env var.
 */
public fun LlmAccess.Settings.Companion.ovhcloud(
    modelId: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "\${OVH_AI_ENDPOINTS_ACCESS_TOKEN}",
    baseUrl = "https://api.ai.cloud.ovh.net/v1",
)

// ---------------------------------------------------------------------------
// Local / Self-hosted
// ---------------------------------------------------------------------------

/**
 * [LM Studio](https://lmstudio.ai) — local model server.
 *
 * ```
 * LlmAccess.Settings.lmStudio("qwen2.5-coder")
 * LlmAccess.Settings.lmStudio("llama-3.3-70b", port = 1234)
 * ```
 *
 * LM Studio doesn't validate API keys; any non-empty string works. The model must be
 * **loaded** in the LM Studio UI before sending requests.
 *
 * @param modelId Model name as shown in LM Studio.
 * @param host Hostname. Defaults to `localhost`.
 * @param port Port. Defaults to `1234`.
 */
public fun LlmAccess.Settings.Companion.lmStudio(
    modelId: String,
    host: String = "localhost",
    port: Int = 1234,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = "lm-studio",
    baseUrl = "http://$host:$port/v1",
)

/**
 * [Ollama](https://ollama.com) via its OpenAI-compatible endpoint.
 *
 * ```
 * LlmAccess.Settings.ollamaOpenAi("llama3.3")
 * LlmAccess.Settings.ollamaOpenAi("codellama", port = 11434)
 * ```
 *
 * Ollama does not require an API key.
 *
 * @param modelId Model name as known to Ollama (e.g. `llama3.3`, `codellama`, `mistral`).
 * @param host Hostname. Defaults to `localhost`.
 * @param port Port. Defaults to `11434`.
 */
public fun LlmAccess.Settings.Companion.ollamaOpenAi(
    modelId: String,
    host: String = "localhost",
    port: Int = 11434,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = "ollama",
    baseUrl = "http://$host:$port/v1",
)

/**
 * [vLLM](https://github.com/vllm-project/vllm) — high-throughput local inference server.
 *
 * ```
 * LlmAccess.Settings.vllm("meta-llama/Llama-3.3-70B-Instruct")
 * ```
 *
 * @param modelId Model name as loaded in vLLM.
 * @param host Hostname. Defaults to `localhost`.
 * @param port Port. Defaults to `8000`.
 * @param apiKey Optional; vLLM supports optional API-key auth.
 */
public fun LlmAccess.Settings.Companion.vllm(
    modelId: String,
    host: String = "localhost",
    port: Int = 8000,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey ?: "vllm",
    baseUrl = "http://$host:$port/v1",
)

// ---------------------------------------------------------------------------
// Generic OpenAI-compatible
// ---------------------------------------------------------------------------

/**
 * Any server that speaks the OpenAI `/chat/completions` API.
 *
 * Use this when a provider isn't listed above, or for self-hosted deployments
 * behind a custom URL.
 *
 * ```
 * LlmAccess.Settings.openAiCompatible(
 *     modelId = "my-model",
 *     baseUrl = "https://my-server.example.com/v1",
 *     apiKey = "\${MY_API_KEY}",
 * )
 * ```
 *
 * @param modelId Model identifier expected by the server.
 * @param baseUrl API root URL (should end before `/chat/completions`).
 * @param apiKey API key for authentication. Pass null only if the server requires none.
 */
public fun LlmAccess.Settings.Companion.openAiCompatible(
    modelId: String,
    baseUrl: String,
    apiKey: String? = null,
): LlmAccess.Settings = buildOpenAiUrl(
    modelId = modelId,
    apiKey = apiKey,
    baseUrl = baseUrl,
)
