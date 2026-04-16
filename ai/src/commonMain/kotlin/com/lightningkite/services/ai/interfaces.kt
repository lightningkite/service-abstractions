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
    val toolCalls = mutableListOf<LlmToolCall>()
    var stopReason: LlmStopReason = LlmStopReason.EndTurn
    var usage = LlmUsage(0, 0)
    stream(model, prompt).collect { event ->
        when (event) {
            is LlmStreamEvent.ReasoningDelta -> reasoning.append(event.text)
            is LlmStreamEvent.TextDelta -> text.append(event.text)
            is LlmStreamEvent.AttachmentEmitted -> attachments.add(event.attachment)
            is LlmStreamEvent.ToolCallEmitted -> toolCalls.add(
                LlmToolCall(event.id, event.name, event.inputJson)
            )
            is LlmStreamEvent.Finished -> {
                stopReason = event.stopReason
                usage = event.usage
            }
        }
    }
    val parts = buildList<LlmPart> {
        if (reasoning.isNotEmpty()) add(LlmPart.Reasoning(reasoning.toString()))
        if (text.isNotEmpty()) add(LlmPart.Text(text.toString()))
        attachments.forEach { add(LlmPart.Attachment(it)) }
        toolCalls.forEach { add(LlmPart.ToolCall(it)) }
    }
    return LlmResult(
        message = LlmMessage.Agent(parts),
        stopReason = stopReason,
        usage = usage,
    )
}

@Serializable
public data class LlmModelId(
    val id: String,
    /** Name of the [LlmAccess] that serves this model. Used for routing when multiple accesses are loaded. */
    val access: String? = null,
)


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
    val supportsToolCalling: Boolean = false,
    val supportsImageInput: Boolean = false,
    val supportsVideoInput: Boolean = false,
    val supportsAudioInput: Boolean = false,
    val supportsImageOutput: Boolean = false,
    val supportsAudioOutput: Boolean = false,
    val supportsReasoning: Boolean = false,
    /** Maximum input context window in tokens. Null means unknown. */
    val maxContextTokens: Int? = null,
    /** Maximum output tokens the model can produce. Null means unknown. */
    val maxOutputTokens: Int? = null,
)


/**
 * Input to a single inference turn.
 *
 * System instructions live in [systemPrompt], separate from conversation [messages].
 * Conversation messages are [LlmMessage.User], [LlmMessage.Agent], or [LlmMessage.ToolResult].
 *
 * Not @Serializable because [tools] carries [KSerializer] references, which are not
 * cross-network-transferable. Messages and other parts are individually serializable.
 */
public data class LlmPrompt(
    /**
     * System instructions. Rendered by providers as a top-level system field (Anthropic,
     * Bedrock) or as the first system-role message (OpenAI, Ollama).
     */
    val systemPrompt: List<LlmPart.ContentOnly> = emptyList(),
    val messages: List<LlmMessage>,
    val tools: List<LlmToolDescriptor<*>> = emptyList(),
    val toolChoice: LlmToolChoice = LlmToolChoice.Auto,
    /**
     * Cap on output tokens. If null, the adapter picks a provider-appropriate default
     * (required by some providers, e.g. Anthropic).
     */
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stopSequences: List<String> = emptyList(),
    /**
     * Hint to providers that support prompt caching: if true, cache the system prompt.
     *
     * Honored by: Anthropic, Bedrock (model-dependent).
     * Ignored by: OpenAI (auto-caches at >1024 tokens), Ollama, LM Studio.
     *
     * Cache hits require ≥~1024 tokens of cached content (provider-dependent).
     */
    val systemPromptCacheBoundary: Boolean = false,
) {
    /**
     * Collects all [LlmSharedContext] from [tools], deduplicates, and joins their content.
     * Returns null when no shared context exists.
     */
    public fun collectSharedContext(): String? {
        val unique = tools.flatMapTo(LinkedHashSet()) { it.sharedContext }
        if (unique.isEmpty()) return null
        return unique.joinToString("\n\n") { it.content }
    }
}

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
 * Shared background information that one or more tools may reference. When a provider
 * assembles the request, it collects all [LlmSharedContext] instances from the active tools,
 * deduplicates them (via pre-computed [hashCode]/[equals]), and renders them once in the
 * system prompt so the model understands the backing data without per-tool repetition.
 *
 * Construct once and share the same instance across tools that need it — reference equality
 * short-circuits dedup, falling back to content comparison only for independently-created
 * instances with identical text.
 */
public class LlmSharedContext(public val content: String) {
    private val cachedHash: Int = content.hashCode()
    override fun hashCode(): Int = cachedHash
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LlmSharedContext) return false
        return cachedHash == other.cachedHash && content == other.content
    }
    override fun toString(): String = "LlmSharedContext(${content.take(40)}...)"
}

/**
 * A callable tool exposed to the model. [type] must be a [KSerializer] for a
 * class whose shape can be mapped to a JSON Schema object; the adapter walks
 * its descriptor to produce the provider-specific schema.
 *
 * Not @Serializable: [KSerializer] itself is not cross-network-transferable.
 */
public data class LlmToolDescriptor<T>(
    val name: String,
    val description: String,
    val type: KSerializer<T>,
    /**
     * Background context shared across tools. Providers collect all [LlmSharedContext] from
     * the active tool set, deduplicate, and render once in the system prompt. Use this for
     * schema descriptions, API docs, or other reference material that multiple tools share.
     */
    val sharedContext: List<LlmSharedContext> = emptyList(),
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


/**
 * A message in an LLM conversation. Each variant carries only the content types
 * that are valid for its role, enforced at the type level.
 *
 * System instructions are not a message — they live on [LlmPrompt.systemPrompt].
 */
@Serializable
public sealed interface LlmMessage {
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
    public val cacheBoundary: Boolean

    @Serializable
    public data class User(
        val parts: List<LlmPart.ContentOnly>,
        override val cacheBoundary: Boolean = false,
    ) : LlmMessage

    @Serializable
    public data class Agent(
        val parts: List<LlmPart>,
        override val cacheBoundary: Boolean = false,
    ) : LlmMessage

    @Serializable
    public data class ToolResult(
        val toolCallId: String,
        val parts: List<LlmPart.ContentOnly>,
        val isError: Boolean = false,
        override val cacheBoundary: Boolean = false,
    ) : LlmMessage
}

/**
 * An ordered content block inside an [LlmMessage]. [ContentOnly] is the subset of parts
 * valid in [LlmMessage.User] and [LlmMessage.ToolResult] messages (text + attachments).
 * [LlmMessage.Agent] messages accept the full [LlmPart] hierarchy, which adds
 * [Reasoning] and [ToolCall].
 */
@Serializable
public sealed interface LlmPart {
    /** Content types valid in User, ToolResult, and Agent messages. */
    @Serializable
    public sealed interface ContentOnly : LlmPart

    @Serializable public data class Text(val text: String) : ContentOnly
    @Serializable public data class Attachment(val attachment: LlmAttachment) : ContentOnly

    /**
     * Model-emitted chain-of-thought / "thinking" output, distinct from the final answer.
     *
     * Producers: only models with reasoning capability (Claude extended thinking, OpenAI
     * o-series, DeepSeek R1, Gemma reasoning variants, etc.). Most models never emit this.
     *
     * Round-trip: receive-only in this release. If a [Reasoning] block is included in an
     * outgoing prompt, providers MAY drop it. Providers MUST NOT fail the request because
     * of it.
     *
     * Ordering: when present, reasoning blocks always precede the final [Text] / [ToolCall]
     * blocks within an agent message — matching the order the model produced them.
     */
    @Serializable public data class Reasoning(val text: String) : LlmPart

    /** An assistant-produced call to a tool. */
    @Serializable public data class ToolCall(val call: LlmToolCall) : LlmPart
}

/**
 * A tool call emitted by the model. [id] uniquely identifies this call within
 * the turn and must be echoed back in the matching [LlmMessage.ToolResult.toolCallId]
 * on the next turn. [inputJson] is the tool's arguments as a JSON string.
 */
@Serializable
public data class LlmToolCall(
    val id: String,
    val name: String,
    val inputJson: String,
)

/** Concatenate every [LlmPart.Text] block in an agent message into one string. */
public fun LlmMessage.Agent.plainText(): String =
    parts.filterIsInstance<LlmPart.Text>().joinToString("") { it.text }

/** Return all [LlmToolCall]s in an agent message. */
public fun LlmMessage.Agent.toolCalls(): List<LlmToolCall> =
    parts.filterIsInstance<LlmPart.ToolCall>().map { it.call }

/** Return the first [LlmToolCall] in an agent message, or null. */
public fun LlmMessage.Agent.firstToolCall(): LlmToolCall? =
    parts.filterIsInstance<LlmPart.ToolCall>().firstOrNull()?.call

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
     * The assistant's reply, possibly containing text, reasoning, attachments,
     * and/or tool calls.
     */
    val message: LlmMessage.Agent,
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
