package com.lightningkite.services.ai.ollama

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmModelInfo
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmStreamEvent
import com.lightningkite.services.ai.LlmUsage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [LlmAccess] implementation talking to a local (or remote) Ollama server via its native
 * `/api/chat` endpoint.
 *
 * Construction parameters map to the `ollama://<model>?...` URL scheme — see
 * [OllamaSchemeRegistrar] for URL parsing.
 *
 * @param name Service instance name (for logs / telemetry).
 * @param baseUrl Ollama HTTP base URL, e.g. "http://localhost:11434".
 * @param context [SettingContext] used for serializer resolution (tool schema generation)
 *   and potentially for telemetry.
 * @param httpClient Ktor HTTP client. Caller may pass a pre-configured client; otherwise
 *   a dedicated one is created. If created internally, it is closed on [disconnect].
 */
public class OllamaLlmAccess(
    override val name: String,
    public val baseUrl: String,
    override val context: SettingContext,
    httpClient: HttpClient? = null,
) : LlmAccess {

    public companion object {
        init {
            OllamaSchemeRegistrar.ensureRegistered()
        }
    }

    private val logger = KotlinLogging.logger {}

    private val ownsClient: Boolean = httpClient == null

    private val client: HttpClient = httpClient ?: HttpClient {
        install(ContentNegotiation) {
            json(OllamaWire.json)
        }
        install(HttpTimeout) {
            // Long inference + model-load times justify big ceilings; streaming bodies
            // are still incremental so this is a safety cap, not per-token pacing.
            requestTimeoutMillis = 10 * 60 * 1000
            socketTimeoutMillis = 10 * 60 * 1000
            connectTimeoutMillis = 30 * 1000
        }
    }

    private val json: Json = OllamaWire.json

    override suspend fun disconnect() {
        if (ownsClient) client.close()
    }

    override suspend fun healthCheck(): HealthStatus = try {
        val r = client.get("$baseUrl/api/tags")
        if (r.status.isSuccess()) HealthStatus(HealthStatus.Level.OK)
        else HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "HTTP ${r.status}")
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }

    override suspend fun getModels(): List<LlmModelInfo> = wrapTransport {
        val response = client.get("$baseUrl/api/tags")
        if (!response.status.isSuccess()) {
            throw mapOllamaError(response.status, response.bodyAsText(), response)
        }
        val tags: OllamaTagsResponse = response.body()
        tags.models.map { entry ->
            LlmModelInfo(
                id = LlmModelId(entry.name),
                name = entry.name,
                description = entry.details?.parameter_size?.let { "Ollama $it model" },
                usdPerMillionInputTokens = 0.0,
                usdPerMillionOutputTokens = 0.0,
                roughIntelligenceRanking = estimateOllamaRanking(entry.name, entry.details?.parameter_size),
            )
        }
    }

    override suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent> = flow {
        val requestBody = OllamaWireBuilder.buildChatRequest(
            model.asString,
            prompt,
            context.internalSerializersModule,
            stream = true,
        )

        val inputTokens = intArrayOf(0)
        val outputTokens = intArrayOf(0)
        var stopReason: LlmStopReason = LlmStopReason.EndTurn
        val fabricatedIdCounter = intArrayOf(0)

        wrapTransport {
            client.preparePost("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw mapOllamaError(response.status, response.bodyAsText(), response, model)
                }

                val channel: ByteReadChannel = response.bodyAsChannel()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) continue

                    val frame: OllamaChatStreamFrame = try {
                        json.decodeFromString(OllamaChatStreamFrame.serializer(), line)
                    } catch (e: Exception) {
                        logger.warn { "Failed to parse Ollama NDJSON frame: $line - ${e.message}" }
                        continue
                    }

                    val message = frame.message
                    val thinking = message?.thinking
                    if (!thinking.isNullOrEmpty()) {
                        emit(LlmStreamEvent.ReasoningDelta(thinking))
                    }
                    val content = message?.content
                    if (!content.isNullOrEmpty()) {
                        emit(LlmStreamEvent.TextDelta(content))
                    }

                    val toolCalls = message?.tool_calls
                    if (!toolCalls.isNullOrEmpty()) {
                        toolCalls.forEach { tc ->
                            val id = tc.id ?: "call_${fabricatedIdCounter[0]++}"
                            val argsJson = json.encodeToString(
                                JsonElement.serializer(),
                                tc.function.arguments,
                            )
                            emit(LlmStreamEvent.ToolCallEmitted(id, tc.function.name, argsJson))
                        }
                        // Tool calls signal intent to invoke; the final frame's stop reason
                        // tells us whether the turn ended for that reason specifically.
                        stopReason = LlmStopReason.ToolUse
                    }

                    if (frame.done) {
                        frame.prompt_eval_count?.let { inputTokens[0] = it }
                        frame.eval_count?.let { outputTokens[0] = it }
                        stopReason = mapDoneReason(frame.done_reason, stopReason)
                    }
                }
            }
        }

        emit(
            LlmStreamEvent.Finished(
                stopReason = stopReason,
                usage = LlmUsage(inputTokens[0], outputTokens[0]),
            ),
        )
    }

    /**
     * Run [block], letting any [LlmException] and [CancellationException] pass through
     * unchanged but wrapping other [Throwable]s as [LlmException.Transport] — DNS /
     * connection-refused / socket errors are the dominant failure mode for Ollama since
     * it's typically local, and users need to see the underlying cause to diagnose
     * "is ollama running?".
     *
     * [CancellationException] (including [kotlinx.coroutines.flow.internal.AbortFlowException])
     * is explicitly re-thrown so callers that cancel a collecting flow (`.take(n)`,
     * timeouts, scope cancellation) don't see their cancellation misclassified as a
     * connection failure.
     */
    private inline fun <T> wrapTransport(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmException) {
        throw e
    } catch (e: Throwable) {
        throw LlmException.Transport("Could not reach Ollama at $baseUrl", e)
    }

    private fun mapDoneReason(doneReason: String?, fallback: LlmStopReason): LlmStopReason =
        when (doneReason) {
            null -> fallback
            "stop" -> if (fallback == LlmStopReason.ToolUse) LlmStopReason.ToolUse else LlmStopReason.EndTurn
            "length" -> LlmStopReason.MaxTokens
            "load", "unload" -> fallback
            else -> fallback
        }
}

/**
 * Map an Ollama HTTP error response to the common [LlmException] hierarchy.
 *
 * Ollama error bodies are plain JSON with a single `{"error": "..."}` string field — no
 * structured type codes — so classification is driven by HTTP status plus string matching
 * on the error message for the 404 "model not found" case.
 *
 * Made `internal` so unit tests in this module can verify mapping logic without hitting
 * a live server.
 */
internal fun mapOllamaError(
    status: HttpStatusCode,
    body: String,
    response: HttpResponse? = null,
    modelId: LlmModelId? = null,
): LlmException {
    val snippet = body.take(500)
    return when {
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
            LlmException.Auth("Ollama auth failure (HTTP ${status.value}): $snippet")
        status == HttpStatusCode.TooManyRequests ->
            LlmException.RateLimit(
                "Ollama rate limit (HTTP 429): $snippet",
                retryAfter = response?.let(::parseRetryAfter),
            )
        status == HttpStatusCode.NotFound -> {
            val lower = body.lowercase()
            if ("model" in lower || "not found" in lower) {
                val id = modelId ?: LlmModelId("")
                LlmException.InvalidModel(id, "Ollama model not found: $snippet")
            } else {
                LlmException.InvalidRequest("Ollama returned 404: $snippet")
            }
        }
        status.value in 400..499 ->
            LlmException.InvalidRequest("Ollama rejected request (HTTP ${status.value}): $snippet")
        status.value in 500..599 ->
            LlmException.ServerError("Ollama server error (HTTP ${status.value}): $snippet")
        else ->
            LlmException.InvalidRequest("Ollama unexpected HTTP ${status.value}: $snippet")
    }
}

/**
 * Parse the `Retry-After` header as either delta-seconds or an HTTP-date. Returns null
 * when the header is absent or unparseable; HTTP-date parsing is skipped since Ollama
 * does not emit it in practice and adding a date parser here would be dead code.
 */
private fun parseRetryAfter(response: HttpResponse): Duration? {
    val raw = response.headers[HttpHeaders.RetryAfter] ?: return null
    return raw.trim().toLongOrNull()?.seconds
}

// ----------------------------------------------------------------------------------------
// Wire-building internals exposed for testing
// ----------------------------------------------------------------------------------------

/**
 * Test-accessible wrapper around [OllamaWire] internals.
 *
 * Exposed as an `internal object` so unit tests in the same module can verify wire-level
 * behaviour without reaching through a class instance.
 */
internal object OllamaWireBuilder {
    fun buildChatRequest(
        model: String,
        prompt: LlmPrompt,
        module: kotlinx.serialization.modules.SerializersModule,
        stream: Boolean,
    ) = OllamaWire.buildChatRequest(model, prompt, module, stream)
}

/**
 * Rough intelligence ranking heuristic from an Ollama model identifier plus its reported
 * parameter size. Intentionally crude — gives agent selectors a hint, nothing more.
 */
private fun estimateOllamaRanking(modelName: String, parameterSize: String?): Double {
    val name = modelName.lowercase()
    val sizeB = parameterSize?.let { parseParamSizeToBillions(it) }
        ?: nameHintToBillions(name)
        ?: return 0.4

    return when {
        sizeB >= 400.0 -> 0.90
        sizeB >= 70.0 -> 0.80
        sizeB >= 30.0 -> 0.70
        sizeB >= 13.0 -> 0.60
        sizeB >= 7.0 -> 0.50
        sizeB >= 3.0 -> 0.40
        sizeB >= 1.0 -> 0.30
        else -> 0.20
    }
}

private fun parseParamSizeToBillions(raw: String): Double? {
    val trimmed = raw.trim().lowercase()
    val numberPart = trimmed.takeWhile { it.isDigit() || it == '.' }
    if (numberPart.isEmpty()) return null
    val n = numberPart.toDoubleOrNull() ?: return null
    return when {
        trimmed.endsWith("b") -> n
        trimmed.endsWith("m") -> n / 1000.0
        else -> n
    }
}

private fun nameHintToBillions(lower: String): Double? {
    // Scan "Nb"/"Nm" style substrings in the model name.
    val regex = Regex("(\\d+(?:\\.\\d+)?)([bm])(?=\\W|$)")
    val match = regex.find(lower) ?: return null
    val n = match.groupValues[1].toDoubleOrNull() ?: return null
    return when (match.groupValues[2]) {
        "b" -> n
        "m" -> n / 1000.0
        else -> null
    }
}
