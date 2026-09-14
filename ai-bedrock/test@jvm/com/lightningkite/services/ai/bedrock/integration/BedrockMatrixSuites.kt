package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.CachingTests
import com.lightningkite.services.ai.test.ErrorHandlingTests
import com.lightningkite.services.ai.test.MultimodalTests
import com.lightningkite.services.ai.test.StreamingTests
import com.lightningkite.services.ai.test.TextGenerationTests
import com.lightningkite.services.ai.test.ToolCallingTests
import com.lightningkite.services.ai.test.ToolChoiceTests

/*
 * Per-suite adapters that bind one of the shared contract suites to a [BedrockModelProfile].
 * Concrete per-model classes (see e.g. ClaudeHaiku45MatrixTests.kt) extend the adapter for each
 * suite the model supports and supply only `profile` — so adding a suite for a model is one line,
 * and every capability flag comes from the profile in one place.
 *
 * `supportsUrlAttachments` is false for all of these: the Bedrock Converse API only accepts inline
 * image bytes, never URL references, regardless of model.
 */

abstract class BedrockMatrixTextGeneration : TextGenerationTests() {
    abstract val profile: BedrockModelProfile
    override val service: LlmAccess get() = profile.service
    override val cheapModel: LlmModelId get() = profile.model
    override val servicePresent: Boolean get() = profile.servicePresent
    override val supportsStopSequences: Boolean get() = profile.supportsStopSequences
    override val reportsUsage: Boolean get() = profile.reportsUsage
    override val testMaxTokens: Int? get() = profile.testMaxTokens
}

abstract class BedrockMatrixStreaming : StreamingTests() {
    abstract val profile: BedrockModelProfile
    override val service: LlmAccess get() = profile.service
    override val cheapModel: LlmModelId get() = profile.model
    override val servicePresent: Boolean get() = profile.servicePresent
    override val reportsUsage: Boolean get() = profile.reportsUsage
    override val deterministicAtTemperatureZero: Boolean get() = profile.deterministicAtTemperatureZero
    override val testMaxTokens: Int? get() = profile.testMaxTokens
}

abstract class BedrockMatrixToolChoice : ToolChoiceTests() {
    abstract val profile: BedrockModelProfile
    override val service: LlmAccess get() = profile.service
    override val cheapModel: LlmModelId get() = profile.model
    override val servicePresent: Boolean get() = profile.servicePresent
    override val supportsToolChoiceNone: Boolean get() = profile.supportsToolChoiceNone
    override val supportsToolChoiceForced: Boolean get() = profile.supportsToolChoiceForced
    override val respectsToolChoiceAutoRestraint: Boolean get() = profile.respectsToolChoiceAutoRestraint
    override val testMaxTokens: Int? get() = profile.testMaxTokens
}

abstract class BedrockMatrixToolCalling : ToolCallingTests() {
    abstract val profile: BedrockModelProfile
    override val service: LlmAccess get() = profile.service
    override val cheapModel: LlmModelId get() = profile.model
    override val servicePresent: Boolean get() = profile.servicePresent
    override val supportsParallelToolCalls: Boolean get() = profile.supportsParallelToolCalls
    override val testMaxTokens: Int? get() = profile.testMaxTokens
}

abstract class BedrockMatrixMultimodal : MultimodalTests() {
    abstract val profile: BedrockModelProfile
    override val service: LlmAccess get() = profile.service
    override val cheapModel: LlmModelId get() = profile.model
    override val visionModel: LlmModelId? get() = profile.visionModel
    override val servicePresent: Boolean get() = profile.servicePresent
    // Bedrock Converse takes image bytes only, never URL references.
    override val supportsUrlAttachments: Boolean get() = false
    override val testMaxTokens: Int? get() = profile.testMaxTokens
}

abstract class BedrockMatrixCaching : CachingTests() {
    abstract val profile: BedrockModelProfile
    override val service: LlmAccess get() = profile.service
    override val cheapModel: LlmModelId get() = profile.model
    override val servicePresent: Boolean get() = profile.servicePresent
    override val supportsPromptCaching: Boolean get() = profile.supportsPromptCaching
    override val testMaxTokens: Int? get() = profile.testMaxTokens
}

abstract class BedrockMatrixErrorHandling : ErrorHandlingTests() {
    abstract val profile: BedrockModelProfile
    override val service: LlmAccess get() = profile.service
    override val cheapModel: LlmModelId get() = profile.model
    override val servicePresent: Boolean get() = profile.servicePresent
    // invalidCredentialsService intentionally left null — minting a bogus-SigV4 provider tempts
    // credential-like strings into logs; the Auth mapping is unit-tested in BedrockWireTest.
}
