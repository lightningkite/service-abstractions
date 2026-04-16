package com.lightningkite.services.ai.openai

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmModelInfo
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStreamEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

private val logger = KotlinLogging.logger("OpenAiLlmAccess")

/**
 * OpenAI Chat Completions implementation of [LlmAccess].
 *
 * Works against the public OpenAI API by default and against any OpenAI-compatible server
 * (LM Studio, vLLM, Groq, Together, Fireworks, etc.) when [baseUrl] is pointed at it.
 *
 * The implementation uses raw ktor HTTP — no OpenAI SDK dependency — so it stays light and
 * matches the service-abstractions project's style of minimal external dependencies.
 */
public class OpenAiLlmAccess(
    override val name: String,
    override val context: SettingContext,
    /** Base URL without trailing slash. `https://api.openai.com/v1` for the public API. */
    public val baseUrl: String,
    public val apiKey: String,
    /** Optional OpenAI organization ID, sent as the `OpenAI-Organization` header. */
    public val organization: String? = null,
) : LlmAccess {

    private val client: HttpClient = com.lightningkite.services.http.client.config {
        install(ContentNegotiation) { json(openAiJson) }
    }

    override suspend fun getModels(): List<LlmModelInfo> {
        val response = try {
            client.get("$baseUrl/models") { authHeaders() }
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            throw LlmException.Transport(e.message ?: "Transport failure", e)
        }
        if (!response.status.isSuccess()) {
            throw mapOpenAiError(response.status, response.bodyAsText(), response)
        }
        val body = openAiJson.decodeFromString(ModelListResponse.serializer(), response.bodyAsText())
        return body.data.map { entry ->
            val catalogEntry = OpenAiModelCatalog.lookup(entry.id)
            LlmModelInfo(
                id = LlmModelId(entry.id),
                name = catalogEntry?.displayName ?: entry.id,
                description = catalogEntry?.description,
                usdPerMillionInputTokens = catalogEntry?.inputPrice ?: 0.0,
                usdPerMillionOutputTokens = catalogEntry?.outputPrice ?: 0.0,
                roughIntelligenceRanking = catalogEntry?.ranking ?: 0.5,
            )
        }
    }

    override suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent> = flow {
        val body = buildRequestBody(
            model = model.asString,
            prompt = prompt,
            stream = true,
            module = context.internalSerializersModule,
        )
        try {
            client.preparePost("$baseUrl/chat/completions") {
                authHeaders()
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw mapOpenAiError(
                        status = response.status,
                        body = response.bodyAsText(),
                        response = response,
                        modelId = model,
                    )
                }
                val parser = OpenAiStreamParser()
                // OpenAI SSE events are `data: {json}\n\n` with a `data: [DONE]\n\n` terminator.
                // We only care about `data:` lines and parse one JSON object per line. Reading via
                // the non-blocking ByteReadChannel (rather than a blocking InputStream) keeps the
                // coroutine dispatcher free while the network waits.
                val channel: ByteReadChannel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val raw = channel.readUTF8Line() ?: break
                    val line = raw.trimEnd('\r')
                    if (line.isEmpty()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trimStart()
                    if (payload == "[DONE]") break
                    parser.consume(payload).forEach { emit(it) }
                }
                parser.drain().forEach { emit(it) }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            // DNS/TCP/TLS/read-timeout failures all reach us as IOException subclasses; surface
            // them as Transport so callers can retry without digging through ktor internals.
            throw LlmException.Transport(e.message ?: "Transport failure", e)
        }
    }

    /**
     * Lightweight health check: hits `GET /models`. This both exercises auth and confirms
     * basic reachability. Counted as OK on any 2xx response.
     */
    override suspend fun healthCheck(): HealthStatus = try {
        val response = client.get("$baseUrl/models") { authHeaders() }
        if (response.status.isSuccess()) {
            HealthStatus(HealthStatus.Level.OK)
        } else {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "HTTP ${response.status}")
        }
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders() {
        header("Authorization", "Bearer $apiKey")
        organization?.let { header("OpenAI-Organization", it) }
    }
}
