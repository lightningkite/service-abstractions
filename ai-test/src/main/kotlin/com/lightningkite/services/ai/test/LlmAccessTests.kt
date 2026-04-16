package com.lightningkite.services.ai.test

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmToolDescriptor
import com.lightningkite.services.ai.LlmUsage

/**
 * Common base for every shared [LlmAccess] contract test class.
 *
 * Subclass in a provider module and override [service] + [cheapModel]. Override capability
 * flags ([visionModel], [supportsParallelToolCalls], [supportsUrlAttachments],
 * [supportsStopSequences]) when the target provider does not implement the feature at all;
 * the suite will skip those tests rather than fail.
 *
 * Example consumer:
 * ```kotlin
 * class OpenAiTextGenerationTest : TextGenerationTests() {
 *     override val service: LlmAccess = OpenAiLlmAccess(...)
 *     override val cheapModel: LlmModelId = LlmModelId("gpt-4o-mini")
 *     override val visionModel: LlmModelId? = LlmModelId("gpt-4o-mini")
 * }
 * ```
 *
 * Each consumer subclass is expected to provide its own API key / credentials via the
 * service constructor. If the subclass detects missing credentials it may override
 * [servicePresent] to return false, which skips the whole suite.
 */
public abstract class LlmAccessTests {

    /** The service under test. Subclasses construct it from their SDK / URL. */
    public abstract val service: LlmAccess

    /**
     * The model id used for cheap text tests (simple prompts, stop sequences, etc.).
     * Intended to be a "mini" / "haiku" / "nano" tier model to keep live-CI cost low.
     */
    public abstract val cheapModel: LlmModelId

    /**
     * The model id used for multimodal tests. Null disables the multimodal suite.
     * Most providers serve vision on the same model family as text, so this often
     * equals [cheapModel].
     */
    public open val visionModel: LlmModelId? = null

    /**
     * True when the provider can emit multiple tool calls in a single turn. When false,
     * the parallel-tool-calls test accepts one-or-more calls instead of requiring two.
     */
    public open val supportsParallelToolCalls: Boolean = true

    /**
     * True when the provider accepts [com.lightningkite.services.ai.LlmAttachment.Url] for
     * images. When false, only the base64-attachment multimodal test runs.
     */
    public open val supportsUrlAttachments: Boolean = true

    /**
     * True when the provider honors [com.lightningkite.services.ai.LlmPrompt.stopSequences].
     * When false, the matching test is skipped rather than failed.
     */
    public open val supportsStopSequences: Boolean = true

    /**
     * True when the provider reliably honors [com.lightningkite.services.ai.LlmToolChoice.None]
     * (i.e. produces no tool calls when told not to). When false, the `noneForbidsToolCall`
     * test is skipped. Most providers support this but local ones (e.g. Ollama) may not.
     */
    public open val supportsToolChoiceNone: Boolean = true

    /**
     * True when the provider reliably honors [com.lightningkite.services.ai.LlmToolChoice.Required]
     * / [com.lightningkite.services.ai.LlmToolChoice.Specific]. When false, the matching tests
     * degrade to "accepts the choice without error" rather than verifying behavior.
     */
    public open val supportsToolChoiceForced: Boolean = true

    /**
     * True if the model returns chain-of-thought / reasoning content as a separate
     * LlmContent.Reasoning block alongside the final answer. Most models don't.
     * Reasoning-capable: Claude extended thinking, OpenAI o-series, DeepSeek R1, Gemma reasoning variants.
     */
    public open val supportsReasoningContent: Boolean = false

    /**
     * True when the provider honors [LlmMessage.cacheBoundary] / [LlmToolDescriptor.cacheBoundary]
     * and reports cache hits via [LlmUsage.cacheReadTokens]. When false, prompt-caching tests
     * are skipped. Anthropic and Bedrock (model-dependent) support this; OpenAI auto-caches
     * and Ollama has no cache, so both leave this false.
     */
    public open val supportsPromptCaching: Boolean = false

    /**
     * Override to false if the subclass cannot contact the provider (e.g. no API key in env).
     * When false, every `@Test` in the class delegates to [skipAllTests] which prints and
     * returns, rather than hitting the network.
     */
    public open val servicePresent: Boolean = true

    /**
     * Upper bound on output tokens to request on test LLM calls that don't otherwise set a specific
     * value. Some providers (reasoning models via LM Studio, Ollama with thinking-enabled templates)
     * need a generous budget to emit a chain-of-thought preamble AND a tool call; tests that use the
     * default budget fail with [com.lightningkite.services.ai.LlmStopReason.MaxTokens] on those
     * providers even though the model would succeed given more room.
     *
     * Null means "use the provider adapter's default" (the current behavior). Concrete subclasses
     * should override to a higher value when targeting reasoning or local models.
     *
     * NOTE: this does NOT apply to tests that deliberately set `maxTokens` (e.g.
     * `TextGenerationTests.maxTokensTruncation` sets it to 10 to trigger truncation). Only tests
     * that would otherwise send null consult this override.
     */
    public open val testMaxTokens: Int? = null

    /**
     * Called from each test at its very start. When [servicePresent] is false, throws a
     * [org.junit.AssumptionViolatedException] which JUnit treats as "skipped" rather than
     * "failed".
     */
    protected fun skipIfServiceAbsent() {
        if (!servicePresent) {
            org.junit.Assume.assumeTrue(
                "LlmAccess service is not available (missing credentials?); skipping.",
                false,
            )
        }
    }
}
