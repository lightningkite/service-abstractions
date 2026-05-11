package com.lightningkite.services.ai

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

/**
 * Decorator that sanitizes sensitive data from prompts before sending to an LLM
 * and restores placeholder tokens back to real values in responses.
 *
 * Per-request: scans the outgoing prompt for sensitive data, builds a bidirectional
 * mapping (real value <-> placeholder), sanitizes the prompt, then restores
 * placeholders in the streamed response. Each [stream] call gets an isolated mapping.
 *
 * Usage:
 * ```kotlin
 * val safeLlm = myLlm.sanitized(
 *     CommonPatterns.SSN, CommonPatterns.CREDIT_CARD,
 *     explicitValues = setOf(myApiKey)
 * )
 * ```
 */
public class SanitizingLlmAccess(
    private val delegate: LlmAccess,
    private val detectors: List<SensitiveDataDetector>,
) : LlmAccess {

    override val name: String get() = delegate.name
    override val context: SettingContext get() = delegate.context
    override suspend fun connect(): Unit = delegate.connect()
    override suspend fun disconnect(): Unit = delegate.disconnect()
    override val healthCheckFrequency: Duration get() = delegate.healthCheckFrequency
    override suspend fun healthCheck(): HealthStatus = delegate.healthCheck()
    override suspend fun getModels(): List<LlmModelInfo> = delegate.getModels()

    override suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent> {
        val mapping = buildMapping(prompt, detectors)
        if (mapping.isEmpty) return delegate.stream(model, prompt)

        val sanitizedPrompt = sanitizePrompt(prompt, mapping)
        return flow {
            val textRestorer = StreamingRestorer(mapping)
            val reasoningRestorer = StreamingRestorer(mapping)

            delegate.stream(model, sanitizedPrompt).collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> {
                        val restored = textRestorer.feed(event.text)
                        if (restored.isNotEmpty()) emit(LlmStreamEvent.TextDelta(restored))
                    }
                    is LlmStreamEvent.ReasoningDelta -> {
                        val restored = reasoningRestorer.feed(event.text)
                        if (restored.isNotEmpty()) emit(LlmStreamEvent.ReasoningDelta(restored))
                    }
                    is LlmStreamEvent.ToolCallEmitted -> emit(
                        LlmStreamEvent.ToolCallEmitted(
                            id = event.id,
                            name = event.name,
                            inputJson = mapping.restore(event.inputJson),
                        )
                    )
                    is LlmStreamEvent.Finished -> {
                        val remainingText = textRestorer.flush()
                        if (remainingText.isNotEmpty()) emit(LlmStreamEvent.TextDelta(remainingText))
                        val remainingReasoning = reasoningRestorer.flush()
                        if (remainingReasoning.isNotEmpty()) emit(LlmStreamEvent.ReasoningDelta(remainingReasoning))
                        emit(event)
                    }
                    is LlmStreamEvent.AttachmentEmitted -> emit(event)
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Convenience Extensions
// ──────────────────────────────────────────────────────────────────────

/** Wrap this [LlmAccess] with sensitive-data sanitization. */
public fun LlmAccess.sanitized(detectors: List<SensitiveDataDetector>): LlmAccess =
    SanitizingLlmAccess(delegate = this, detectors = detectors)

/**
 * Wrap this [LlmAccess] with sensitive-data sanitization using built-in regex
 * patterns and/or explicit values to redact.
 */
public fun LlmAccess.sanitized(
    vararg patterns: RegexDetector = arrayOf(
        CommonPatterns.SSN,
        CommonPatterns.CREDIT_CARD,
        CommonPatterns.EMAIL,
        CommonPatterns.PHONE_US,
        CommonPatterns.API_KEY,
    ),
    explicitValues: Set<String> = emptySet(),
): LlmAccess {
    val detectors = buildList<SensitiveDataDetector> {
        addAll(patterns)
        if (explicitValues.isNotEmpty()) add(ExplicitValueDetector(explicitValues))
    }
    return SanitizingLlmAccess(delegate = this, detectors = detectors)
}

// ──────────────────────────────────────────────────────────────────────
//  Sanitization Mapping
// ──────────────────────────────────────────────────────────────────────

/**
 * Bidirectional mapping between real sensitive values and placeholder tokens.
 * Built once per [SanitizingLlmAccess.stream] call.
 */
internal class SanitizationMapping {
    private val realToPlaceholder = LinkedHashMap<String, String>()
    private val placeholderToReal = LinkedHashMap<String, String>()
    private var nextIndex = 0

    /** Sorted entries for replacement — longest first to avoid partial clobbering. */
    private var sortedEntries: List<Pair<String, String>>? = null

    val isEmpty: Boolean get() = realToPlaceholder.isEmpty()

    fun getOrCreatePlaceholder(realValue: String): String =
        realToPlaceholder.getOrPut(realValue) {
            val placeholder = "<<REDACTED_${nextIndex.toString().padStart(2, '0')}>>"
            nextIndex++
            placeholderToReal[placeholder] = realValue
            sortedEntries = null // invalidate cache
            placeholder
        }

    /** Replace all known real values with their placeholders. */
    fun sanitize(text: String): String {
        val entries = sortedEntries ?: realToPlaceholder.entries
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }
            .also { sortedEntries = it }

        var result = text
        for ((real, placeholder) in entries) {
            result = result.replace(real, placeholder)
        }
        return result
    }

    /** Replace all placeholders with their real values. */
    fun restore(text: String): String {
        var result = text
        for ((placeholder, real) in placeholderToReal) {
            result = result.replace(placeholder, real)
        }
        return result
    }
}

/** Scan all text in [prompt] and build a [SanitizationMapping] of detected sensitive values. */
internal fun buildMapping(prompt: LlmPrompt, detectors: List<SensitiveDataDetector>): SanitizationMapping {
    val mapping = SanitizationMapping()
    for (text in collectAllText(prompt)) {
        for (detector in detectors) {
            for (range in detector.findAll(text)) {
                mapping.getOrCreatePlaceholder(text.substring(range))
            }
        }
    }
    return mapping
}

private fun collectAllText(prompt: LlmPrompt): List<String> = buildList {
    for (part in prompt.systemPrompt) if (part is LlmPart.Text) add(part.text)
    for (message in prompt.messages) {
        when (message) {
            is LlmMessage.User -> for (part in message.parts) {
                if (part is LlmPart.Text) add(part.text)
            }
            is LlmMessage.Agent -> for (part in message.parts) {
                when (part) {
                    is LlmPart.Text -> add(part.text)
                    is LlmPart.Reasoning -> add(part.text)
                    is LlmPart.ToolCall -> add(part.call.inputJson)
                    else -> {}
                }
            }
            is LlmMessage.ToolResult -> for (part in message.parts) {
                if (part is LlmPart.Text) add(part.text)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Prompt Sanitization
// ──────────────────────────────────────────────────────────────────────

private fun sanitizePrompt(prompt: LlmPrompt, mapping: SanitizationMapping): LlmPrompt =
    prompt.copy(
        systemPrompt = prompt.systemPrompt.map { it.sanitizeContentOnly(mapping) },
        messages = prompt.messages.map { it.sanitizeMessage(mapping) },
    )

private fun LlmPart.ContentOnly.sanitizeContentOnly(mapping: SanitizationMapping): LlmPart.ContentOnly =
    when (this) {
        is LlmPart.Text -> LlmPart.Text(mapping.sanitize(text))
        is LlmPart.Attachment -> this
    }

private fun LlmMessage.sanitizeMessage(mapping: SanitizationMapping): LlmMessage = when (this) {
    is LlmMessage.User -> copy(parts = parts.map { it.sanitizeContentOnly(mapping) })
    is LlmMessage.Agent -> copy(parts = parts.map { it.sanitizePart(mapping) })
    is LlmMessage.ToolResult -> copy(parts = parts.map { it.sanitizeContentOnly(mapping) })
}

private fun LlmPart.sanitizePart(mapping: SanitizationMapping): LlmPart = when (this) {
    is LlmPart.Text -> LlmPart.Text(mapping.sanitize(text))
    is LlmPart.Reasoning -> LlmPart.Reasoning(mapping.sanitize(text))
    is LlmPart.ToolCall -> LlmPart.ToolCall(
        LlmToolCall(call.id, call.name, mapping.sanitize(call.inputJson))
    )
    is LlmPart.Attachment -> this
}

// ──────────────────────────────────────────────────────────────────────
//  Streaming Restorer
// ──────────────────────────────────────────────────────────────────────

private val PLACEHOLDER_PREFIX = "<<REDACTED_"

/**
 * Buffers streaming text to handle placeholders split across chunk boundaries.
 *
 * Holds back a trailing suffix that could be the start of a placeholder (e.g. `<<RED`),
 * resolving it when the next chunk arrives. At stream end, [flush] emits whatever remains.
 */
internal class StreamingRestorer(private val mapping: SanitizationMapping) {
    private val buffer = StringBuilder()

    /** Feed a chunk, return text safe to emit (with placeholders restored). */
    fun feed(chunk: String): String {
        buffer.append(chunk)
        return drainSafe()
    }

    /** Flush remaining buffer at stream end. */
    fun flush(): String {
        val result = mapping.restore(buffer.toString())
        buffer.clear()
        return result
    }

    private fun drainSafe(): String {
        val text = buffer.toString()

        // Check for an incomplete placeholder: prefix found but no closing >>
        val lastPrefixIdx = text.lastIndexOf(PLACEHOLDER_PREFIX)
        val holdFromPrefix = if (lastPrefixIdx >= 0) {
            val closingIdx = text.indexOf(">>", lastPrefixIdx + PLACEHOLDER_PREFIX.length)
            if (closingIdx < 0) text.length - lastPrefixIdx else 0
        } else 0

        // Check for a partial prefix at the tail (e.g. "<<RED" that could become "<<REDACTED_00>>")
        val holdFromPartial = partialPrefixOverlap(text, PLACEHOLDER_PREFIX)

        val holdBack = maxOf(holdFromPrefix, holdFromPartial)
        if (holdBack >= text.length) return ""

        val safeRaw = text.substring(0, text.length - holdBack)
        val heldRaw = text.substring(text.length - holdBack)
        buffer.clear()
        buffer.append(heldRaw)
        return mapping.restore(safeRaw)
    }
}

/**
 * Length of the longest suffix of [text] that equals a proper prefix of [target].
 * E.g. text="hello<<RED", target="<<REDACTED_" → 5 (the "<<RED" suffix).
 */
internal fun partialPrefixOverlap(text: String, target: String): Int {
    val maxCheck = minOf(text.length, target.length - 1)
    for (len in maxCheck downTo 1) {
        if (text.endsWith(target.substring(0, len))) return len
    }
    return 0
}
