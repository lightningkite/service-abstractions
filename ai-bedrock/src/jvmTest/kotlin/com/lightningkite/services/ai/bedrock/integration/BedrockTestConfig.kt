package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.bedrock.BedrockLlmSettings

/**
 * Shared setup + **model matrix** for the live Bedrock integration suites in this package.
 *
 * Why a matrix: Bedrock hosts models from ~18 providers, and their real-world behavior varies
 * wildly — most sharply around tool use. Amazon Nova, for example, returns a 200 and streams a
 * response but then throws `Model produced invalid sequence as part of ToolUse` the moment a tool
 * is attached (a documented, model-side defect). Testing a single model would let that kind of
 * breakage ship unnoticed, exactly as it did. So each capable model gets its own set of concrete
 * suite subclasses (one file per model, e.g. `ClaudeHaiku45MatrixTests.kt`), and every one runs
 * the full contract suite against the live API. Add a model only after confirming it invokes with
 * `scripts/bedrock-probe.sh <id>` — otherwise it reddens the suite for the wrong reason.
 *
 * Credentials resolve in order (same for every model):
 *  1. AWS profile `lk` from `~/.aws/credentials` (primary path for local dev)
 *  2. Environment variables `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` (CI fallback)
 * Optional: `AWS_SESSION_TOKEN` (STS/temporary creds), `AWS_REGION` (defaults to `us-west-2`).
 *
 * Every suite gates on [servicePresent] and skips silently when credentials are absent, so CI
 * without AWS access stays green. The target account must also have model access **enabled** for
 * each model id in the chosen region; a valid key alone is not enough (Bedrock gates per-account,
 * per-region). An `AccessDeniedException` means "enable the model in the Bedrock console", not a
 * bug in this file.
 */
internal object BedrockTestConfig {

    /** AWS profile name used for local development. */
    private const val AWS_PROFILE = "lk"

    /** AWS region — defaults to us-west-2. */
    val region: String = System.getenv("AWS_REGION") ?: "us-west-2"

    /**
     * True when credentials are available — either the `lk` profile exists in
     * `~/.aws/credentials` or the env vars are set. Gates every `@Test` via `servicePresent`.
     */
    val servicePresent: Boolean by lazy {
        profileAvailable(AWS_PROFILE) ||
            (System.getenv("AWS_ACCESS_KEY_ID") != null && System.getenv("AWS_SECRET_ACCESS_KEY") != null)
    }

    private val context: TestSettingContext by lazy { TestSettingContext() }

    // One LlmAccess per model id, reused across every suite in the JVM so we pay the httpClient
    // startup cost once per model rather than once per test class.
    private val services: MutableMap<String, LlmAccess> = mutableMapOf()

    /**
     * Live [LlmAccess] for [modelId], wired through the `bedrock://` URL scheme. Prefers the `lk`
     * AWS profile for local dev; falls back to the env-var default chain so CI can supply
     * credentials the usual way. Callers must gate on [servicePresent] first.
     */
    @Synchronized
    fun serviceFor(modelId: String): LlmAccess = services.getOrPut(modelId) {
        BedrockLlmSettings.ensureRegistered()
        if (!servicePresent) {
            error("BedrockTestConfig.serviceFor accessed without AWS credentials; gate on servicePresent first")
        }
        val url = if (profileAvailable(AWS_PROFILE)) {
            "bedrock://$AWS_PROFILE@$modelId?region=$region"
        } else {
            "bedrock://$modelId?region=$region"
        }
        LlmAccess.Settings(url)("bedrock-integration-$modelId", context)
    }

    /** Check whether the named profile has credentials in `~/.aws/credentials`. */
    private fun profileAvailable(profile: String): Boolean {
        val home = System.getProperty("user.home") ?: return false
        val file = java.io.File("$home/.aws/credentials")
        if (!file.exists()) return false
        return file.readText().contains("[$profile]")
    }
}

/**
 * One model's identity + how it behaves against our contract, so a per-model file can declare a
 * model in a single place and hand the flags to each suite. Every flag mirrors the same-named
 * capability flag on [com.lightningkite.services.ai.test.LlmAccessTests]; see there for exact
 * semantics. Defaults encode Bedrock-wide truths (no URL image attachments; streamed vs
 * non-streamed output is never guaranteed byte-identical) plus the safe assumption that a newly
 * added model is text-only until proven otherwise.
 *
 * @property id the exact id you invoke — a foundation-model id for on-demand models
 *   (`amazon.nova-lite-v1:0`) or a cross-region inference-profile id for models that require one
 *   (`us.anthropic.claude-haiku-4-5-20251001-v1:0`). Confirm with `scripts/bedrock-probe.sh`.
 */
class BedrockModelProfile(
    val id: String,
    val supportsVision: Boolean = false,
    val supportsStopSequences: Boolean = false,
    val supportsToolCalling: Boolean = false,
    val supportsToolChoiceNone: Boolean = false,
    val supportsToolChoiceForced: Boolean = false,
    val respectsToolChoiceAutoRestraint: Boolean = false,
    val supportsParallelToolCalls: Boolean = true,
    val supportsReasoningContent: Boolean = false,
    val supportsPromptCaching: Boolean = false,
    val reportsUsage: Boolean = true,
    // Bedrock does not guarantee a streamed call and a separate non-streamed call at temperature 0
    // produce identical text, for any model — the streaming suite's equality check stays off.
    val deterministicAtTemperatureZero: Boolean = false,
    val testMaxTokens: Int? = null,
) {
    val model: LlmModelId get() = LlmModelId(id)
    val visionModel: LlmModelId? get() = if (supportsVision) model else null
    val service: LlmAccess get() = BedrockTestConfig.serviceFor(id)
    val servicePresent: Boolean get() = BedrockTestConfig.servicePresent
}
