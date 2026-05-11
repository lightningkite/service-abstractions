package com.lightningkite.services.ai

// ──────────────────────────────────────────────────────────────────────
//  Message Constructors
// ──────────────────────────────────────────────────────────────────────

/**
 * Create a [LlmMessage.User] carrying a single text part.
 */
public fun userMessage(text: String): LlmMessage.User =
    LlmMessage.User(listOf(LlmPart.Text(text)))

/**
 * Create a [LlmMessage.User] carrying a text caption and an attachment.
 */
public fun userMessage(caption: String, attachment: LlmAttachment): LlmMessage.User =
    LlmMessage.User(listOf(LlmPart.Text(caption), LlmPart.Attachment(attachment)))

/**
 * Create a system prompt content list from a single text string.
 *
 * Usage: `LlmPrompt(systemPrompt = systemPrompt("You are helpful."), ...)`
 */
public fun systemPrompt(text: String): List<LlmPart.ContentOnly> =
    listOf(LlmPart.Text(text))

/**
 * Create a [LlmMessage.ToolResult] carrying a single text part.
 */
public fun toolResult(
    toolCallId: String,
    text: String,
    isError: Boolean = false,
): LlmMessage.ToolResult =
    LlmMessage.ToolResult(
        toolCallId = toolCallId,
        parts = listOf(LlmPart.Text(text)),
        isError = isError,
    )

// ──────────────────────────────────────────────────────────────────────
//  Cache Break Helper
// ──────────────────────────────────────────────────────────────────────

/**
 * Return a copy of this message with [LlmMessage.cacheBreak] set to [enabled].
 *
 * Because [LlmMessage] is a sealed interface, the caller would otherwise need to
 * pattern-match to the concrete type before calling `copy()`. This extension handles
 * that dispatch internally.
 */
public fun LlmMessage.withCacheBreak(enabled: Boolean = true): LlmMessage = when (this) {
    is LlmMessage.User -> copy(cacheBreak = enabled)
    is LlmMessage.Agent -> copy(cacheBreak = enabled)
    is LlmMessage.ToolResult -> copy(cacheBreak = enabled)
}

// ──────────────────────────────────────────────────────────────────────
//  Cache-Preserving Operations
//
//  These only append to the message list tail or change fields that are
//  NOT part of the cached prefix (maxTokens, temperature, toolChoice,
//  stopSequences). All existing cache boundaries remain valid.
// ──────────────────────────────────────────────────────────────────────

/**
 * Append [message] to the end of the conversation.
 *
 * **Cache-preserving:** only appends to the message list tail.
 */
public operator fun LlmPrompt.plus(message: LlmMessage): LlmPrompt =
    copy(messages = messages + message)

/**
 * Append [newMessages] to the end of the conversation.
 *
 * **Cache-preserving:** only appends to the message list tail.
 */
public operator fun LlmPrompt.plus(newMessages: List<LlmMessage>): LlmPrompt =
    copy(messages = messages + newMessages)

/**
 * Continue the conversation after an agent response that contains tool calls.
 *
 * Appends the [agentMessage], then all [toolResults], then an optional [userFollowUp].
 *
 * **Cache-preserving:** only appends to the message list tail.
 *
 * @param agentMessage The agent's response (appended to preserve tool_use/tool_result linkage).
 * @param toolResults Tool execution results to feed back.
 * @param userFollowUp Optional follow-up user message appended after all tool results.
 */
public fun LlmPrompt.continueTurn(
    agentMessage: LlmMessage.Agent,
    toolResults: List<LlmMessage.ToolResult>,
    userFollowUp: LlmMessage.User? = null,
): LlmPrompt {
    val newMessages = buildList {
        add(agentMessage)
        addAll(toolResults)
        if (userFollowUp != null) add(userFollowUp)
    }
    return copy(messages = messages + newMessages)
}

/**
 * Return a copy with [LlmPrompt.maxTokens] changed.
 *
 * **Cache-preserving:** maxTokens is not part of the cached prefix.
 */
public fun LlmPrompt.withMaxTokens(maxTokens: Int?): LlmPrompt =
    copy(maxTokens = maxTokens)

/**
 * Return a copy with [LlmPrompt.temperature] changed.
 *
 * **Cache-preserving:** temperature is not part of the cached prefix.
 */
public fun LlmPrompt.withTemperature(temperature: Double?): LlmPrompt =
    copy(temperature = temperature)

/**
 * Return a copy with [LlmPrompt.toolChoice] changed.
 *
 * **Cache-preserving:** toolChoice is not part of the cached prefix.
 * The tool definitions themselves ARE cached; this only controls how the model uses them.
 */
public fun LlmPrompt.withToolChoice(toolChoice: LlmToolChoice): LlmPrompt =
    copy(toolChoice = toolChoice)

/**
 * Return a copy with [LlmPrompt.stopSequences] changed.
 *
 * **Cache-preserving:** stopSequences are not part of the cached prefix.
 */
public fun LlmPrompt.withStopSequences(stopSequences: List<String>): LlmPrompt =
    copy(stopSequences = stopSequences)

// ──────────────────────────────────────────────────────────────────────
//  Cache-Breaking Operations
//
//  These modify the cached prefix. The `replacing` prefix in the name
//  signals that existing cache boundaries for the affected region (and
//  everything after it) are invalidated.
//
//  Cache order: system prompt → tools → messages.
// ──────────────────────────────────────────────────────────────────────

/**
 * Return a copy with the system prompt replaced.
 *
 * **Cache-breaking:** modifies the system prompt, which is the first element of the
 * cached prefix. All cache boundaries (system, tool, and message) are invalidated.
 */
public fun LlmPrompt.replacingSystemPrompt(
    systemPrompt: List<LlmPart.ContentOnly>,
): LlmPrompt = copy(systemPrompt = systemPrompt)

/**
 * Return a copy with the system prompt replaced from a single text string.
 *
 * **Cache-breaking:** modifies the system prompt, which is the first element of the
 * cached prefix. All cache boundaries (system, tool, and message) are invalidated.
 */
public fun LlmPrompt.replacingSystemPrompt(
    text: String,
): LlmPrompt = copy(systemPrompt = listOf(LlmPart.Text(text)))

/**
 * Return a copy with the tool list replaced.
 *
 * **Cache-breaking:** tools sit between the system prompt and messages in the cached
 * prefix. Replacing them invalidates tool-level and message-level cache boundaries.
 * System prompt cache boundaries remain valid only if the system prompt is unchanged.
 */
public fun LlmPrompt.replacingTools(
    tools: List<LlmToolDescriptor<*>>,
    toolChoice: LlmToolChoice = this.toolChoice,
): LlmPrompt = copy(tools = tools, toolChoice = toolChoice)

/**
 * Return a copy with the entire message list replaced.
 *
 * **Cache-breaking:** replaces all messages, invalidating any message-level cache
 * boundaries. System prompt and tool cache boundaries remain valid only if those
 * fields are unchanged.
 *
 * Prefer [plus] or [continueTurn] when you only need to append to the conversation.
 */
public fun LlmPrompt.replacingMessages(messages: List<LlmMessage>): LlmPrompt =
    copy(messages = messages)
