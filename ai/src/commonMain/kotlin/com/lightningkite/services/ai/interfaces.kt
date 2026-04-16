package com.lightningkite.services.ai

import com.lightningkite.MediaType
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Single-turn LLM access. Implementations provide [stream]; non-streaming [inference]
 * is derived from it by collection.
 *
 * Tool execution and multi-turn agent loops are the caller's responsibility: this
 * abstraction only maps one request (messages + tool definitions) to one response
 * (assistant message, possibly containing tool-call blocks).
 */
public interface LlmAccess : Service {
    public suspend fun getModels(): List<LlmModelInfo>

    /**
     * Run one turn of inference, streaming frames as the model produces them.
     * The flow always ends with exactly one [LlmStreamEvent.Finished] frame.
     */
    public suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent>

    /**
     * URL-based configuration for an [LlmAccess] implementation.
     *
     * Provider modules register their URL schemes via this parser's companion object.
     * Example URLs:
     * ```
     * anthropic://claude-haiku-4-5?apiKey=${ANTHROPIC_API_KEY}
     * openai://gpt-4o?apiKey=${OPENAI_API_KEY}
     * ```
     *
     * @property url Connection string defining provider, model, and options.
     */
    @Serializable
    @JvmInline
    public value class Settings(public val url: String) : Setting<LlmAccess> {
        override fun invoke(name: String, context: SettingContext): LlmAccess =
            parse(name, url, context)

        public companion object : UrlSettingParser<LlmAccess>()
    }
}

/**
 * Non-streaming convenience: collect the full stream into a single [LlmResult].
 */
public suspend fun LlmAccess.inference(model: LlmModelId, prompt: LlmPrompt): LlmResult {
    val reasoning = StringBuilder()
    val text = StringBuilder()
    val attachments = mutableListOf<LlmAttachment>()
    val toolCalls = mutableListOf<LlmContent.ToolCall>()
    var stopReason: LlmStopReason = LlmStopReason.EndTurn
    var usage = LlmUsage(0, 0)
    stream(model, prompt).collect { event ->
        when (event) {
            is LlmStreamEvent.ReasoningDelta -> reasoning.append(event.text)
            is LlmStreamEvent.TextDelta -> text.append(event.text)
            is LlmStreamEvent.AttachmentEmitted -> attachments.add(event.attachment)
            is LlmStreamEvent.ToolCallEmitted -> toolCalls.add(
                LlmContent.ToolCall(event.id, event.name, event.inputJson)
            )
            is LlmStreamEvent.Finished -> {
                stopReason = event.stopReason
                usage = event.usage
            }
        }
    }
    val content = buildList<LlmContent> {
        if (reasoning.isNotEmpty()) add(LlmContent.Reasoning(reasoning.toString()))
        if (text.isNotEmpty()) add(LlmContent.Text(text.toString()))
        attachments.forEach { add(LlmContent.Attachment(it)) }
        addAll(toolCalls)
    }
    return LlmResult(
        message = LlmMessage(LlmMessageSource.Agent, content),
        stopReason = stopReason,
        usage = usage,
    )
}

@JvmInline
@Serializable
public value class LlmModelId(public val asString: String)


@Serializable
public data class LlmModelInfo(
    val id: LlmModelId,
    val name: String,
    val description: String? = null,
    val usdPerMillionInputTokens: Double,
    val usdPerMillionOutputTokens: Double,
    /**
     * Subjective quality ranking in [0.0, 1.0]. Higher = more capable.
     * Intended as a rough sort hint when picking among available models.
     * Values are inherently imprecise and rot as new models ship — implementations
     * should refresh them whenever their model list is updated.
     */
    val roughIntelligenceRanking: Double = 0.5,
)


/**
 * Input to a single inference turn.
 *
 * System instructions go in the first [LlmMessage] with source [LlmMessageSource.System].
 * Tool results from a prior turn go as [LlmContent.ToolResult] blocks inside an
 * [LlmMessageSource.Tool] message.
 *
 * Not @Serializable because [tools] carries [KSerializer] references, which are not
 * cross-network-transferable. Messages and other parts are individually serializable.
 */
public data class LlmPrompt(
    val messages: List<LlmMessage>,
    val tools: List<LlmToolDescriptor> = emptyList(),
    val toolChoice: LlmToolChoice = LlmToolChoice.Auto,
    /**
     * Cap on output tokens. If null, the adapter picks a provider-appropriate default
     * (required by some providers, e.g. Anthropic).
     */
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stopSequences: List<String> = emptyList(),
)

/**
 * Controls whether and how the model calls tools.
 *
 * - [Auto]: model decides. Use when tools are optional.
 * - [None]: tools stay visible (necessary if history contains prior tool_use/tool_result
 *   blocks that providers validate against the tool list) but no new calls allowed.
 *   Use to force a final natural-language summary.
 * - [Required]: model MUST call at least one tool. Primary use: structured output —
 *   pass a single tool whose schema is your target type, force a call, extract typed data.
 * - [Specific]: force one named tool. For step-by-step orchestration or schema
 *   extraction when multiple tools are available.
 */
@Serializable
public sealed class LlmToolChoice {
    @Serializable public data object Auto : LlmToolChoice()

    /**
     * Forbid any new tool calls on this turn, regardless of whether the conversation
     * history contains prior `tool_use`/`tool_result` blocks. Tools remain declared in
     * the request so that providers validating history against the tool list still
     * accept the request.
     */
    @Serializable public data object None : LlmToolChoice()
    @Serializable public data object Required : LlmToolChoice()
    @Serializable public data class Specific(val name: String) : LlmToolChoice()
}

/**
 * A callable tool exposed to the model. [type] must be a [KSerializer] for a
 * class whose shape can be mapped to a JSON Schema object; the adapter walks
 * its descriptor to produce the provider-specific schema.
 *
 * Not @Serializable: [KSerializer] itself is not cross-network-transferable.
 */
public data class LlmToolDescriptor(
    val name: String,
    val description: String,
    val type: KSerializer<*>,
    /**
     * Hint to providers that support prompt caching: if true, cache the tool definitions
     * up to and including this tool. Anthropic sends tools before messages, so a tool
     * boundary caches only the tool list (and system prompt); use [LlmMessage.cacheBoundary]
     * to cache conversation history.
     *
     * Same provider support and limits as [LlmMessage.cacheBoundary].
     */
    val cacheBoundary: Boolean = false,
)


@Serializable
public data class LlmMessage(
    val source: LlmMessageSource,
    val content: List<LlmContent>,
    /**
     * Hint to providers that support prompt caching: if true, cache everything in
     * the prompt UP TO AND INCLUDING this message. Subsequent calls reusing the
     * same prefix will read from the provider's cache instead of re-processing.
     *
     * Honored by: Anthropic, Bedrock (model-dependent).
     * Ignored by: OpenAI (auto-caches at >1024 tokens), Ollama, LM Studio.
     *
     * Limits: Anthropic/Bedrock allow up to 4 cache boundaries per request across
     * messages and tools combined. Exceeding the limit raises [LlmException.InvalidRequest].
     * Cache hits require ≥~1024 tokens of cached content (provider-dependent); smaller
     * prefixes are silently not cached.
     *
     * Tradeoff: cache writes cost ~25% more than normal input tokens; cache reads cost
     * ~90% less. Net win when the same prefix is reused across multiple calls.
     */
    val cacheBoundary: Boolean = false,
)

@Serializable
public enum class LlmMessageSource { System, User, Agent, Tool }

/**
 * An ordered content block inside an [LlmMessage]. Providers interleave text with
 * tool uses and attachments, so content is a list rather than a single string.
 */
@Serializable
public sealed class LlmContent {
    @Serializable public data class Text(val text: String) : LlmContent()
    @Serializable public data class Attachment(val attachment: LlmAttachment) : LlmContent()

    /**
     * An assistant-produced call to a tool. [id] uniquely identifies this call within
     * the turn and must be echoed back in the matching [ToolResult.toolCallId] on the
     * next turn. [inputJson] is the tool's arguments as a JSON string.
     *
     * An assistant turn may contain zero or more `ToolCall` blocks; providers that
     * support parallel tool calling can emit several in one turn. Callers must handle
     * both single and multi-call turns.
     */
    @Serializable public data class ToolCall(
        val id: String,
        val name: String,
        val inputJson: String,
    ) : LlmContent()

    /**
     * The result of executing a tool call. Carried in a message with source
     * [LlmMessageSource.Tool] on the next turn.
     */
    @Serializable public data class ToolResult(
        val toolCallId: String,
        val content: String,
        val isError: Boolean = false,
    ) : LlmContent()

    /**
     * Model-emitted chain-of-thought / "thinking" output, distinct from the final answer.
     *
     * Producers: only models with reasoning capability (Claude extended thinking, OpenAI
     * o-series, DeepSeek R1, Gemma reasoning variants, etc.). Most models never emit this.
     *
     * Display: typically shown collapsed/secondary to [Text] blocks, or hidden entirely.
     *
     * Round-trip: receive-only in this release. If a [Reasoning] block is included in an
     * outgoing prompt, providers MAY drop it. Providers MUST NOT fail the request because
     * of it. Multi-turn extended thinking with tool use on Anthropic is not yet supported
     * (would require preserving provider-opaque signature data).
     *
     * Ordering: when present, reasoning blocks always precede the final [Text] / [ToolCall]
     * blocks within an assistant message — matching the order the model produced them.
     */
    @Serializable public data class Reasoning(val text: String) : LlmContent()
}

/**
 * Image/file content. Exactly one of [Url] or [Base64] is supplied — which one works
 * depends on the provider (most accept both for images).
 */
@Serializable
public sealed class LlmAttachment {
    public abstract val mediaType: MediaType

    @Serializable public data class Url(
        override val mediaType: MediaType,
        val url: String,
    ) : LlmAttachment()

    @Serializable public data class Base64(
        override val mediaType: MediaType,
        val base64: String,
    ) : LlmAttachment()
}

/**
 * Aggregated result of a non-streaming inference turn.
 */
@Serializable
public data class LlmResult(
    /**
     * The assistant's reply. Source is always [LlmMessageSource.Agent], even when the
     * message contains only [LlmContent.ToolCall] blocks and no text.
     */
    val message: LlmMessage,
    val stopReason: LlmStopReason,
    val usage: LlmUsage,
)

@Serializable
public enum class LlmStopReason {
    /** Model finished its response naturally. */
    EndTurn,
    /** Model emitted tool calls; caller should run them and continue the conversation. */
    ToolUse,
    /**
     * Model hit one of the configured stop sequences.
     *
     * The stop sequence text is NOT included in the returned message — text ends
     * immediately before it.
     */
    StopSequence,
    /** Output was truncated because [LlmPrompt.maxTokens] was reached. */
    MaxTokens,
}

@Serializable
public data class LlmUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    /**
     * Input tokens that were served from the provider's prompt cache rather than re-processed.
     * A subset of [inputTokens]: if the caller wants total billable input it should still use
     * [inputTokens] - [cacheReadTokens]. Zero if the provider does not support prompt caching
     * or the request missed the cache. Providers that don't expose cache-read metrics should
     * leave this at 0.
     */
    val cacheReadTokens: Int = 0,
)

/**
 * A single frame of a streaming response. The stream always terminates with exactly
 * one [Finished] frame carrying the stop reason and final token usage.
 */
public sealed class LlmStreamEvent {
    /**
     * A chunk of assistant text.
     *
     * Delta granularity is implementation-defined. A provider MAY emit a single
     * `TextDelta` containing the full response, or many small chunks. Callers must
     * accumulate across all `TextDelta` events until [Finished].
     */
    public data class TextDelta(val text: String) : LlmStreamEvent()

    /**
     * A chunk of model reasoning / chain-of-thought text. Same accumulation rules as
     * [TextDelta]: delta granularity is implementation-defined; callers concatenate
     * across all `ReasoningDelta` events.
     *
     * Streams from reasoning-capable models typically emit ALL reasoning deltas before
     * any [TextDelta] / [ToolCallEmitted]. Adapters preserve this ordering.
     */
    public data class ReasoningDelta(val text: String) : LlmStreamEvent()

    /**
     * An assistant-generated attachment.
     *
     * RESERVED FOR FUTURE USE. No provider in this release emits assistant-generated
     * attachments during streaming. Consumers should still handle this frame so
     * forward-compatibility is preserved.
     */
    public data class AttachmentEmitted(val attachment: LlmAttachment) : LlmStreamEvent()
    /**
     * A complete tool call. Providers typically deliver tool-call arguments as they
     * stream in; adapters accumulate deltas and emit this event once per call when
     * the arguments are fully assembled.
     */
    public data class ToolCallEmitted(
        val id: String,
        val name: String,
        val inputJson: String,
    ) : LlmStreamEvent()
    public data class Finished(val stopReason: LlmStopReason, val usage: LlmUsage) : LlmStreamEvent()
}

/**
 * Typed exception hierarchy for LLM calls. Provider adapters wrap their underlying HTTP /
 * transport / protocol exceptions into one of these subclasses so callers can handle errors
 * uniformly across providers.
 *
 * Callers should catch [LlmException] at the coarsest level, then branch on the subtype.
 * The [cause] chain preserves the original exception for debugging.
 */
public sealed class LlmException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Authentication / authorization failure: missing, invalid, or expired credentials. 401/403-class. */
    public class Auth(message: String, cause: Throwable? = null) : LlmException(message, cause)

    /**
     * Quota, rate limit, or concurrency limit hit. 429-class and provider-specific throttling.
     *
     * @property retryAfter If the provider supplied a retry hint (e.g. `Retry-After` header),
     *   the duration the caller should wait before retrying. Null when unknown.
     */
    public class RateLimit(
        message: String,
        public val retryAfter: kotlin.time.Duration? = null,
        cause: Throwable? = null,
    ) : LlmException(message, cause)

    /** Model ID unknown, unavailable in this region/account, or not enabled. */
    public class InvalidModel(
        public val modelId: LlmModelId,
        message: String,
        cause: Throwable? = null,
    ) : LlmException(message, cause)

    /**
     * Request was malformed or violated provider-specific constraints (oversized input,
     * disallowed combination of fields, schema mismatch). 400-class other than [Auth] /
     * [RateLimit] / [InvalidModel].
     */
    public class InvalidRequest(message: String, cause: Throwable? = null) : LlmException(message, cause)

    /** Provider-side failure: 5xx, internal error, overloaded, model-serving transient failure. */
    public class ServerError(message: String, cause: Throwable? = null) : LlmException(message, cause)

    /**
     * Network / transport failure before or during a valid response: DNS failure, TCP reset,
     * TLS error, socket closed mid-stream, read timeout. Distinct from [ServerError] which
     * implies the server responded with an error status.
     */
    public class Transport(message: String, cause: Throwable? = null) : LlmException(message, cause)
}
