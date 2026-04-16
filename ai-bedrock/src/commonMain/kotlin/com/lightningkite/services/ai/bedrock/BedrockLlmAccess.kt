package com.lightningkite.services.ai.bedrock

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
import com.lightningkite.services.http.client
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

/**
 * [LlmAccess] implementation that talks directly to Amazon Bedrock's Converse API over raw
 * ktor HTTP. AWS SigV4 signing, event-stream parsing, and credential resolution are all
 * implemented in common-platform Kotlin — no AWS SDK, no Koog — so this class works on every
 * target supported by ktor-client-cio: JVM, Android, iOS, macOS, and JS.
 *
 * @property region AWS region the Bedrock endpoint lives in. Determines the URL host and the
 *   signing scope; must match the region where your model access has been granted.
 * @property credentials credentials used to sign requests. Caller is responsible for
 *   refreshing session credentials; the instance holds them for the service lifetime.
 * @property clock Source of "now" for signatures. Exposed primarily for deterministic tests.
 */
public class BedrockLlmAccess(
    override val name: String,
    override val context: SettingContext,
    public val region: String,
    private val credentials: AwsCredentials,
    private val clock: AmzDateClock = AmzDateClock.Default,
) : LlmAccess {

    private val httpClient: HttpClient = client.config { /* no extra config needed */ }

    private val module: SerializersModule get() = context.internalSerializersModule

    private val host: String = "bedrock-runtime.$region.amazonaws.com"

    override suspend fun getModels(): List<LlmModelInfo> = KNOWN_MODELS

    override suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent> = flow {
        try {
            val body = BedrockWire.buildRequestBody(prompt, module).toString().encodeToByteArray()
            val path = "/model/${encodePathSegment(model.asString)}/converse-stream"
            val amzDate = clock.nowAmzDate()

            val signed = SigV4.signHeaders(
                method = "POST",
                host = host,
                path = path,
                query = "",
                headers = mapOf("content-type" to "application/json"),
                body = body,
                credentials = credentials,
                region = region,
                service = "bedrock",
                amzDate = amzDate,
                includeContentSha256Header = true,
            )

            httpClient.preparePost("https://$host$path") {
                contentType(ContentType.Application.Json)
                accept(ContentType("application", "vnd.amazon.eventstream"))
                addSigned(signed)
                setBody(body)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val bodyText = response.bodyAsText()
                    val (awsType, awsMessage) = BedrockWire.parseAwsErrorBody(bodyText)
                    throw mapBedrockError(
                        type = awsType,
                        message = awsMessage ?: bodyText.take(500),
                        status = response.status,
                        modelId = model,
                    )
                }
                val state = BedrockStreamState()
                consumeEventStream(response.bodyAsChannel(), state, model) { emit(it) }
                if (!state.finished) {
                    emit(
                        LlmStreamEvent.Finished(
                            stopReason = state.stopReason,
                            usage = LlmUsage(state.inputTokens, state.outputTokens, state.cacheReadTokens),
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            throw e
        } catch (e: Throwable) {
            // DNS/TCP/TLS/read-timeout and SigV4-signer failures surface here; wrap as Transport so
            // callers can retry without digging through ktor/JDK-specific exception hierarchies.
            throw LlmException.Transport(e.message ?: "Transport failure", e)
        }
    }

    private fun HttpRequestBuilder.addSigned(signed: Map<String, String>) {
        for ((k, v) in signed) headers.append(k, v)
    }

    /**
     * Pull bytes off the response channel, feed them through [EventStreamParser], and
     * dispatch each decoded frame to [handleBedrockEvent]. Split from [stream] so tests can
     * reuse the same state machine against a canned byte source.
     */
    private suspend fun consumeEventStream(
        channel: ByteReadChannel,
        state: BedrockStreamState,
        model: LlmModelId,
        emit: suspend (LlmStreamEvent) -> Unit,
    ) {
        val parser = EventStreamParser()
        val chunk = ByteArray(CHUNK_SIZE)
        while (!channel.isClosedForRead) {
            val n = channel.readAvailable(chunk, 0, chunk.size)
            if (n <= 0) continue
            parser.feed(chunk.copyOfRange(0, n))
            for (msg in parser.drain()) {
                if (handleBedrockEvent(msg, state, model, emit)) return
            }
        }
        // Channel closed without emitting its own terminator — dispatch anything still in
        // the buffer (useful when the server flushes a final metadata message just before
        // closing the connection).
        for (msg in parser.drain()) {
            if (handleBedrockEvent(msg, state, model, emit)) return
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        // No cheap read-only Bedrock endpoint is reachable via our SigV4 code without extra
        // IAM permissions the caller may not have. Return OK so the service starts; real
        // errors surface when the first call is made. If the region was nonsense we already
        // would have failed at URL-parse time.
        return HealthStatus(HealthStatus.Level.OK)
    }

    public companion object {
        public const val SERVICE_NAME: String = "bedrock"

        private const val CHUNK_SIZE = 8192

        init {
            // Idempotent: registering the same scheme twice throws, which would be fatal if
            // classloading reached us from multiple paths (tests + live code).
            if (!LlmAccess.Settings.supports("bedrock")) {
                registerBedrockUrlScheme()
            }
        }

        /**
         * Locally-curated Bedrock model list used by [getModels]. Prices are USD per million
         * tokens per AWS's public pricing pages; [LlmModelInfo.roughIntelligenceRanking] is
         * subjective and will rot — refresh whenever this list is updated.
         *
         * The full Bedrock catalogue is large and regional; we include the Claude, Nova, and
         * Llama models most users of this library actually pick.
         */
        public val KNOWN_MODELS: List<LlmModelInfo> = listOf(
            LlmModelInfo(
                id = LlmModelId("anthropic.claude-sonnet-4-5-20250929-v1:0"),
                name = "Claude Sonnet 4.5",
                description = "Anthropic's balanced flagship on Bedrock.",
                usdPerMillionInputTokens = 3.0,
                usdPerMillionOutputTokens = 15.0,
                roughIntelligenceRanking = 0.90,
            ),
            LlmModelInfo(
                id = LlmModelId("anthropic.claude-sonnet-4-20250514-v1:0"),
                name = "Claude Sonnet 4",
                description = "Prior-generation Sonnet, still available on Bedrock.",
                usdPerMillionInputTokens = 3.0,
                usdPerMillionOutputTokens = 15.0,
                roughIntelligenceRanking = 0.85,
            ),
            LlmModelInfo(
                id = LlmModelId("anthropic.claude-opus-4-1-20250805-v1:0"),
                name = "Claude Opus 4.1",
                description = "Prior-generation top-tier Anthropic model.",
                usdPerMillionInputTokens = 15.0,
                usdPerMillionOutputTokens = 75.0,
                roughIntelligenceRanking = 0.93,
            ),
            LlmModelInfo(
                id = LlmModelId("anthropic.claude-haiku-4-5-20251001-v1:0"),
                name = "Claude Haiku 4.5",
                description = "Anthropic's fast, inexpensive model on Bedrock.",
                usdPerMillionInputTokens = 1.0,
                usdPerMillionOutputTokens = 5.0,
                roughIntelligenceRanking = 0.75,
            ),
            LlmModelInfo(
                id = LlmModelId("anthropic.claude-3-5-haiku-20241022-v1:0"),
                name = "Claude 3.5 Haiku",
                description = "Legacy fast/cheap Anthropic model.",
                usdPerMillionInputTokens = 0.80,
                usdPerMillionOutputTokens = 4.0,
                roughIntelligenceRanking = 0.55,
            ),
            LlmModelInfo(
                id = LlmModelId("amazon.nova-pro-v1:0"),
                name = "Amazon Nova Pro",
                description = "Amazon's top-tier Nova model; good balance of capability and price.",
                usdPerMillionInputTokens = 0.80,
                usdPerMillionOutputTokens = 3.20,
                roughIntelligenceRanking = 0.80,
            ),
            LlmModelInfo(
                id = LlmModelId("amazon.nova-lite-v1:0"),
                name = "Amazon Nova Lite",
                description = "Amazon Nova at a lower price tier.",
                usdPerMillionInputTokens = 0.06,
                usdPerMillionOutputTokens = 0.24,
                roughIntelligenceRanking = 0.65,
            ),
            LlmModelInfo(
                id = LlmModelId("amazon.nova-micro-v1:0"),
                name = "Amazon Nova Micro",
                description = "Cheapest Nova; for short, high-volume calls.",
                usdPerMillionInputTokens = 0.035,
                usdPerMillionOutputTokens = 0.14,
                roughIntelligenceRanking = 0.50,
            ),
            LlmModelInfo(
                id = LlmModelId("meta.llama3-3-70b-instruct-v1:0"),
                name = "Meta Llama 3.3 70B",
                description = "Latest mid-size Llama 3 on Bedrock.",
                usdPerMillionInputTokens = 0.72,
                usdPerMillionOutputTokens = 0.72,
                roughIntelligenceRanking = 0.70,
            ),
        )
    }
}

private val bedrockStreamLogger = KotlinLogging.logger("BedrockStream")

/**
 * Dispatch a single decoded ConverseStream frame. Returns true once the stream has produced
 * its [LlmStreamEvent.Finished] frame and the caller should stop reading.
 *
 * Kept as a top-level internal so the test suite can feed it synthetic messages without an
 * HTTP client in the loop.
 */
internal suspend fun handleBedrockEvent(
    msg: EventStreamMessage,
    state: BedrockStreamState,
    model: LlmModelId? = null,
    emit: suspend (LlmStreamEvent) -> Unit,
): Boolean {
    val eventType = msg.headers[":event-type"] ?: return false
    val messageType = msg.headers[":message-type"]

    // Exceptions come through with :message-type=exception. Surface as a typed LlmException
    // so callers can branch on the subtype instead of parsing free-form messages. The
    // :exception-type header (when present) is the authoritative AWS error classifier.
    if (messageType == "exception") {
        val payloadText = msg.payload.decodeToString()
        val exceptionType = msg.headers[":exception-type"] ?: eventType
        val (bodyType, awsMessage) = BedrockWire.parseAwsErrorBody(payloadText)
        throw mapBedrockError(
            type = exceptionType.ifEmpty { bodyType },
            message = awsMessage ?: payloadText.take(500),
            modelId = model,
        )
    }

    val payload = runCatching {
        BedrockWire.jsonCodec.parseToJsonElement(msg.payload.decodeToString()) as? JsonObject
    }.getOrNull() ?: run {
        // Swallow (don't throw) so one malformed frame doesn't kill the whole stream, but log
        // so operators can spot protocol drift or a corrupted payload.
        bedrockStreamLogger.warn {
            "Dropping Bedrock stream frame with unparseable payload (event=$eventType): " +
                    msg.payload.decodeToString().take(200)
        }
        return false
    }

    return when (eventType) {
        "messageStart" -> false  // Role-only; nothing to emit.

        "contentBlockStart" -> {
            val index = payload.int("contentBlockIndex") ?: return false
            val start = payload.obj("start") ?: return false
            val toolUse = start.obj("toolUse")
            if (toolUse != null) {
                val id = toolUse.str("toolUseId") ?: return false
                val name = toolUse.str("name") ?: return false
                state.toolsInFlight[index] = ToolCallInProgress(id = id, name = name)
            }
            false
        }

        "contentBlockDelta" -> {
            val index = payload.int("contentBlockIndex") ?: return false
            val delta = payload.obj("delta") ?: return false
            val text = delta.str("text")
            if (text != null && text.isNotEmpty()) {
                emit(LlmStreamEvent.TextDelta(text))
            }
            val toolUseDelta = delta.obj("toolUse")
            if (toolUseDelta != null) {
                val fragment = toolUseDelta.str("input") ?: ""
                state.toolsInFlight[index]?.argsJson?.append(fragment)
            }
            // Bedrock extended-thinking delta: {"reasoningContent":{"text": "..."}} carries
            // chain-of-thought text, and {"reasoningContent":{"signature": "..."}} carries the
            // opaque signature needed to replay reasoning back to the provider. Signatures are
            // not round-trippable in v1, so drop them silently.
            val reasoningDelta = delta.obj("reasoningContent")
            if (reasoningDelta != null) {
                val reasoningText = reasoningDelta.str("text")
                if (reasoningText != null && reasoningText.isNotEmpty()) {
                    emit(LlmStreamEvent.ReasoningDelta(reasoningText))
                }
            }
            false
        }

        "contentBlockStop" -> {
            val index = payload.int("contentBlockIndex") ?: return false
            state.toolsInFlight.remove(index)?.let { call ->
                val args = call.argsJson.toString().ifEmpty { "{}" }
                emit(LlmStreamEvent.ToolCallEmitted(id = call.id, name = call.name, inputJson = args))
            }
            false
        }

        "messageStop" -> {
            state.stopReason = BedrockWire.parseStopReason(payload.str("stopReason"))
            false
        }

        "metadata" -> {
            payload.obj("usage")?.let { usage ->
                usage.int("inputTokens")?.let { state.inputTokens = it }
                usage.int("outputTokens")?.let { state.outputTokens = it }
                // Prompt-caching tokens are only reported by newer models (Claude 3.5 Sonnet,
                // Nova) on cache hits — absent on older models and cache misses.
                usage.int("cacheReadInputTokenCount")?.let { state.cacheReadTokens = it }
            }
            state.finished = true
            emit(
                LlmStreamEvent.Finished(
                    stopReason = state.stopReason,
                    usage = LlmUsage(state.inputTokens, state.outputTokens, state.cacheReadTokens),
                ),
            )
            true
        }

        else -> false
    }
}

/**
 * URL-encode a single path segment per AWS's canonical URI rules (RFC 3986 unreserved chars
 * pass through, plus `:` which appears in Bedrock model ids and is safe unescaped).
 * Everything else is %-encoded in uppercase hex.
 *
 * Uppercase hex digits are required by RFC 3986 percent-encoding (§2.1 recommends uppercase
 * for normalization); this is intentionally different from [sha256Hex], which uses lowercase
 * because AWS's canonical-request format specifies lowercase for the payload hash. Please do
 * not "unify" these — mismatching the case breaks SigV4 signatures.
 *
 * Known limitation: this handles plain Bedrock model ids like
 * `anthropic.claude-sonnet-4-5-20250929-v1:0` but NOT ARN-style inference-profile ids such as
 * `arn:aws:bedrock:us-east-1:123:inference-profile/us.anthropic.claude-…`. The `/` inside an
 * ARN would be percent-encoded here and Bedrock would reject the URL. Supporting inference
 * profiles requires a richer path construction and is left for future work.
 */
internal fun encodePathSegment(segment: String): String {
    val sb = StringBuilder(segment.length)
    for (b in segment.encodeToByteArray()) {
        val c = b.toInt().toChar()
        when {
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c == '-' || c == '.' || c == '_' || c == '~' || c == ':' -> sb.append(c)
            else -> {
                sb.append('%')
                sb.append(HEX_UP[(b.toInt() ushr 4) and 0x0f])
                sb.append(HEX_UP[b.toInt() and 0x0f])
            }
        }
    }
    return sb.toString()
}

private val HEX_UP = "0123456789ABCDEF".toCharArray()

/**
 * Abstraction over "what time is it?" so tests can pin the timestamp used in SigV4
 * signatures (AWS test vectors require an exact `20150830T123600Z`-style value).
 */
public interface AmzDateClock {
    /** Return the current wall-clock timestamp formatted as `YYYYMMDDTHHMMSSZ`. */
    public fun nowAmzDate(): String

    public companion object {
        /**
         * Default clock: uses [Clock.System] and formats in UTC. Available on all KMP targets
         * via kotlinx-datetime.
         */
        public val Default: AmzDateClock = object : AmzDateClock {
            override fun nowAmzDate(): String {
                val now = Clock.System.now()
                val parts = now.toLocalDateTime(TimeZone.UTC)
                return buildString {
                    append(parts.year.toString().padStart(4, '0'))
                    append((parts.month.ordinal + 1).toString().padStart(2, '0'))
                    append(parts.day.toString().padStart(2, '0'))
                    append('T')
                    append(parts.hour.toString().padStart(2, '0'))
                    append(parts.minute.toString().padStart(2, '0'))
                    append(parts.second.toString().padStart(2, '0'))
                    append('Z')
                }
            }
        }
    }
}
