package com.lightningkite.services.ai.bedrock.integration

/**
 * Live Bedrock matrix entry: **Claude Haiku 4.5** via its cross-region inference profile.
 *
 * Confirmed invocable (text + tools) with `scripts/bedrock-probe.sh us.anthropic.claude-haiku-4-5-20251001-v1:0`.
 * Claude on Bedrock in us-west-2 requires the `us.` inference-profile id, not the bare
 * `anthropic.claude-…` foundation-model id (which is not available for on-demand throughput).
 *
 * This is the reference "fully capable" model in the matrix: tools, vision, stop sequences,
 * tool-choice (including the prompt-emulated `none`, which Claude obeys), and prompt caching all
 * work. It is the model the AI-package tool/streaming contract is validated against end to end.
 */
private val CLAUDE_HAIKU_45 = BedrockModelProfile(
    id = "us.anthropic.claude-haiku-4-5-20251001-v1:0",
    supportsVision = true,
    supportsStopSequences = true,
    supportsToolCalling = true,
    supportsToolChoiceNone = true,
    supportsToolChoiceForced = true,
    respectsToolChoiceAutoRestraint = true,
    supportsParallelToolCalls = true,
    supportsPromptCaching = true,
)

class ClaudeHaiku45TextGenerationTest : BedrockMatrixTextGeneration() {
    override val profile = CLAUDE_HAIKU_45
}

class ClaudeHaiku45StreamingTest : BedrockMatrixStreaming() {
    override val profile = CLAUDE_HAIKU_45
}

class ClaudeHaiku45ToolChoiceTest : BedrockMatrixToolChoice() {
    override val profile = CLAUDE_HAIKU_45
}

class ClaudeHaiku45ToolCallingTest : BedrockMatrixToolCalling() {
    override val profile = CLAUDE_HAIKU_45
}

class ClaudeHaiku45MultimodalTest : BedrockMatrixMultimodal() {
    override val profile = CLAUDE_HAIKU_45
}

class ClaudeHaiku45CachingTest : BedrockMatrixCaching() {
    override val profile = CLAUDE_HAIKU_45
}

class ClaudeHaiku45ErrorHandlingTest : BedrockMatrixErrorHandling() {
    override val profile = CLAUDE_HAIKU_45
}
