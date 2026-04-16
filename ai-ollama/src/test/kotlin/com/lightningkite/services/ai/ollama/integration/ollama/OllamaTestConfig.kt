package com.lightningkite.services.ai.ollama.integration.ollama

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.ollama.OllamaSchemeRegistrar
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared setup for the live Ollama integration test suites in this package.
 *
 * All suites share a single [service] instance so the Ktor client is not rebuilt per test
 * class. When a local Ollama server is not reachable at [baseUrl] on JVM startup, every
 * `@Test` in every suite skips (via `servicePresent = false`) rather than failing.
 *
 * Environment variables:
 * - `OLLAMA_BASE_URL` — overrides the default `http://localhost:11434`.
 * - `OLLAMA_CHEAP_MODEL` — overrides the default `llama3.2:1b` cheap-model id. Useful when
 *   the developer has a different small model pulled locally.
 * - `OLLAMA_TOOL_MODEL` — model id used by [OllamaToolCallingIntegrationTest] and
 *   [OllamaToolChoiceIntegrationTest]. Ollama's tool-calling support is strongly
 *   model-dependent; tiny models (llama3.2:1b, etc.) frequently don't emit valid tool calls.
 *   Set this to a 7B+ model like `llama3.2` or `qwen2.5:7b` and pull it first with
 *   `ollama pull`.
 * - `OLLAMA_VISION_MODEL` — model id used by [OllamaMultimodalIntegrationTest]. Defaults
 *   to `llava:7b`; set to empty to disable the multimodal suite.
 */
internal object OllamaTestConfig {

    val baseUrl: String = System.getenv("OLLAMA_BASE_URL")?.trimEnd('/')
        ?: "http://localhost:11434"

    /**
     * Preferred cheap-model id. If unset via env var and the default isn't installed, we
     * fall back to the first installed model (if any). Resolved lazily so it can consult
     * the live model list once the server is known reachable.
     */
    val cheapModel: LlmModelId by lazy {
        val configured = System.getenv("OLLAMA_CHEAP_MODEL")
        val preferred = configured ?: "llama3.2:1b"
        val installed = installedModelNames
        when {
            installed.any { it.equals(preferred, ignoreCase = true) } -> LlmModelId(preferred)
            // User explicitly set an env var but it's not installed → surface via name so
            // the failing test's message points the developer at the problem.
            configured != null -> LlmModelId(configured)
            installed.isNotEmpty() -> LlmModelId(installed.first())
            else -> LlmModelId(preferred)
        }
    }

    /**
     * Tool-calling model — configured via `OLLAMA_TOOL_MODEL` env var. When unset, the
     * [OllamaToolCallingIntegrationTest] and [OllamaToolChoiceIntegrationTest] suites skip
     * because Ollama's tool support varies sharply by model and the default tiny cheap
     * model (`llama3.2:1b`) does NOT reliably emit tool calls. Forcing the developer to
     * explicitly pick a real tool-capable model (`qwen2.5:7b`, `llama3.2` 8B, `mistral-nemo`,
     * …) avoids noisy failures on a stock install.
     */
    val toolModel: LlmModelId? by lazy {
        System.getenv("OLLAMA_TOOL_MODEL")?.let(::LlmModelId)
    }

    /**
     * True when the developer has opted into tool-calling integration tests by setting
     * `OLLAMA_TOOL_MODEL` AND the named model is installed locally.
     */
    val toolModelAvailable: Boolean
        get() {
            val tm = toolModel ?: return false
            return installedModelNames.any { it.equals(tm.asString, ignoreCase = true) }
        }

    /**
     * Vision model. Null disables the multimodal suite. Defaults to `llava:7b`; developers
     * without it pulled should set `OLLAMA_VISION_MODEL=` (empty) to skip, or pull it.
     *
     * Autodetect: once the server is reachable and the models list is fetched, if the
     * requested vision model is absent from the local catalogue we fall back to any
     * installed model whose id contains "llava" or "vision" (case-insensitive).
     */
    val visionModel: LlmModelId? by lazy {
        val configured = System.getenv("OLLAMA_VISION_MODEL")
        if (configured != null && configured.isEmpty()) return@lazy null
        val desired = configured ?: "llava:7b"
        if (!servicePresent) return@lazy null
        val installed = installedModelNames
        if (installed.any { it.equals(desired, ignoreCase = true) }) {
            LlmModelId(desired)
        } else {
            installed.firstOrNull { name ->
                val lower = name.lowercase()
                "llava" in lower || "vision" in lower
            }?.let(::LlmModelId)
        }
    }

    /**
     * True when the Ollama server answers `GET /api/tags` within 1.5s AND has at least one
     * model installed. Without a model, every inference test would fail with InvalidModel —
     * skipping in that case matches the "server not available" contract better than
     * failing loudly at the user. Probe result is cached for the JVM lifetime.
     */
    val servicePresent: Boolean get() = installedModelNames.isNotEmpty()

    /**
     * List of model names installed locally, as reported by `/api/tags`. Empty when the
     * server is unreachable, returns an error, or has no models pulled. Used to autodetect
     * a vision model and to gate [servicePresent].
     */
    val installedModelNames: List<String> by lazy { probe() }

    private val context: TestSettingContext by lazy { TestSettingContext() }

    /**
     * Live [LlmAccess] wired through the `ollama://` URL scheme. Built lazily so the tests
     * can be loaded without a running server.
     */
    val service: LlmAccess by lazy {
        OllamaSchemeRegistrar.ensureRegistered()
        LlmAccess.Settings("ollama://${cheapModel.asString}?baseUrl=$baseUrl")(
            "ollama-integration",
            context,
        )
    }

    /**
     * Single combined probe: fetches `/api/tags` with a 1.5s timeout, returning the parsed
     * list of installed model names. An empty list means "server unreachable OR empty" —
     * both map to "skip every test" so distinguishing them isn't useful.
     */
    private fun probe(): List<String> = runBlocking {
        val client = HttpClient {}
        try {
            val body = withTimeoutOrNull(1500) {
                val resp = client.get("$baseUrl/api/tags")
                if (resp.status.isSuccess()) resp.bodyAsText() else null
            } ?: return@runBlocking emptyList()
            // Extract "name":"..." entries. Avoids pulling a serializer dependency for a
            // one-shot probe.
            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(body).map {
                it.groupValues[1]
            }.toList()
        } catch (e: Exception) {
            emptyList()
        } finally {
            client.close()
        }
    }
}
