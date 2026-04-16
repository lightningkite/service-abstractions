package com.lightningkite.services.ai.openai.integration.lmstudio

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.openai.OpenAiLlmSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared configuration for the `:ai-openai` live LM Studio integration tests.
 *
 * LM Studio exposes an OpenAI-compatible API on `http://localhost:1234/v1` by default, so
 * it is reached via the `openai://` scheme with a `baseUrl` override rather than a
 * dedicated scheme. All suites skip silently when the server is unreachable or reports no
 * models. Note: LM Studio's `/v1/models` lists installed (not necessarily loaded) models —
 * when the first listed model isn't loaded, tests will fail rather than skip. Load the
 * target model in the LM Studio UI before running the suite.
 *
 * Environment variables:
 * - `LMSTUDIO_BASE_URL` — overrides the default `http://localhost:1234/v1`.
 * - `LMSTUDIO_API_KEY` — fake key sent as `Authorization: Bearer`. LM Studio ignores the
 *   value but the OpenAI client refuses to construct without something here; defaults to
 *   `lm-studio` (a widely-used placeholder).
 *
 * Model selection is automatic: at class-init we call `GET $baseUrl/models` and pick the
 * first listed id as [cheapModel]. If `/models` returns empty or the probe fails,
 * [servicePresent] stays false and every test skips.
 *
 * Vision model autodetection: scans the listed model ids for names matching `(?i)(llava|
 * vision|vl|image)`. Null when none match, which disables the multimodal suite.
 */
internal object LmStudioTestConfig {

    init {
        // Ensure the openai:// scheme is registered before any Settings lookup.
        OpenAiLlmSettings.ensureRegistered()
    }

    val baseUrl: String = (System.getenv("LMSTUDIO_BASE_URL") ?: "http://localhost:1234/v1")
        .trimEnd('/')

    private val apiKey: String = System.getenv("LMSTUDIO_API_KEY") ?: "lm-studio"

    /**
     * True when `GET $baseUrl/models` returns 2xx and reports at least one model id. The
     * probe is a short 2s GET to keep CI fast; any failure (timeout, connection refused,
     * non-2xx, empty list) sets this to false and the whole suite skips.
     */
    val servicePresent: Boolean by lazy { listedModelIds.isNotEmpty() }

    /**
     * First model id reported by `/models`. Callers must gate on [servicePresent] before
     * reading this; otherwise a missing server produces a placeholder id that will fail
     * every request.
     */
    val cheapModel: LlmModelId by lazy {
        LlmModelId(listedModelIds.firstOrNull() ?: "no-model-loaded")
    }

    /**
     * Vision model autodetected from listed models whose id contains "llava", "vision",
     * "vl", or "image" (case-insensitive). Null when no such model is listed → the
     * multimodal suite skips.
     */
    val visionModel: LlmModelId? by lazy {
        if (!servicePresent) null
        else listedModelIds.firstOrNull {
            Regex("(?i)(llava|vision|vl|image)").containsMatchIn(it)
        }?.let(::LlmModelId)
    }

    /**
     * LM Studio surfaces OpenAI-compatible Chat Completions, which reports
     * `finish_reason: "stop"` for both natural end-of-turn AND stop-sequence hits — the two
     * cases are indistinguishable at the wire level. Our adapter maps `"stop"` to
     * [com.lightningkite.services.ai.LlmStopReason.EndTurn], so the `stopSequences` test
     * cannot distinguish the stop-sequence case and must be skipped for LM Studio (and any
     * other OpenAI-compatible runtime).
     */
    const val supportsStopSequences: Boolean = false

    /**
     * Gemma reasoning variants (and other reasoning-capable models in LM Studio) emit
     * `reasoning_content` on the Chat Completions response, which the `:ai-openai` adapter
     * surfaces as [com.lightningkite.services.ai.LlmContent.Reasoning] / [com.lightningkite.services.ai.LlmStreamEvent.ReasoningDelta].
     */
    const val supportsReasoningContent: Boolean = true

    /**
     * Upper bound on output tokens for the abstract test suite when running against LM Studio.
     * Small reasoning models (e.g. Gemma 4 E4B) emit a chain-of-thought preamble before every
     * tool call; the adapter's / LM Studio's default (~1024) is not enough for CoT plus a
     * tool_call block, so tool-calling and streaming-with-tools tests truncate with
     * `LlmStopReason.MaxTokens` and empty tool_calls. 4096 leaves ample headroom without
     * blowing out a wall-clock-bounded CI run.
     */
    const val testMaxTokens: Int = 4096

    private val listedModelIds: List<String> by lazy { probe() }

    private val context: TestSettingContext by lazy { TestSettingContext() }

    /**
     * Live [LlmAccess] wired through the `openai://` scheme with a `baseUrl` pointing at
     * LM Studio. Lazy so we only build it when a test actually runs (after the skip gate).
     */
    val service: LlmAccess by lazy {
        LlmAccess.Settings(
            "openai://${cheapModel.id}?apiKey=$apiKey&baseUrl=$baseUrl",
        )("lmstudio-integration", context)
    }

    /**
     * Reachability probe. Returns the list of model ids reported by `/models`, or an empty
     * list for any failure (unreachable / non-2xx / parse error / timeout).
     */
    private fun probe(): List<String> = runBlocking {
        val client = HttpClient {}
        try {
            val body = withTimeoutOrNull(2000) {
                val resp = client.get("$baseUrl/models")
                if (resp.status.isSuccess()) resp.bodyAsText() else null
            } ?: return@runBlocking emptyList()
            Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(body).map {
                it.groupValues[1]
            }.toList()
        } catch (e: Exception) {
            emptyList()
        } finally {
            client.close()
        }
    }
}
