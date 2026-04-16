package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.bedrock.BedrockLlmSettings

/**
 * Shared setup for the live Bedrock integration test suites in this package.
 *
 * All suites share a single [service] instance (reused across classes within the same JVM so
 * we don't pay the httpClient startup cost per test class) and skip silently when AWS
 * credentials are absent so CI without AWS access stays green.
 *
 * Credentials are resolved in order:
 *  1. AWS profile `lk` from `~/.aws/credentials` (primary path for local dev)
 *  2. Environment variables `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` (CI fallback)
 *
 * Optional:
 *  - `AWS_SESSION_TOKEN` — when using STS / temporary credentials via env vars
 *  - `AWS_REGION` — defaults to `us-west-2` if unset
 *
 * Note: The target AWS account must have model access **enabled** for the chosen model id in
 * the chosen region. Bedrock gates model access per-account and per-region; a valid key alone
 * is not enough. If the tests fail with `AccessDeniedException` the fix is to enable the
 * model in the AWS Bedrock console, not to touch this file.
 */
internal object BedrockTestConfig {

    /** AWS profile name used for local development. */
    private const val AWS_PROFILE = "lk"

    /** AWS region — defaults to us-west-2, which has Claude 3.5 Haiku available as of 2026-04. */
    val region: String = System.getenv("AWS_REGION") ?: "us-west-2"

    /**
     * Cheapest Claude on Bedrock as of writing that still supports tool-use and vision.
     *
     * Nova Micro (`amazon.nova-micro-v1:0`) is ~20x cheaper per token but lacks image
     * support, which would force us to use a second model id for the multimodal suite and
     * complicate the other suites. Sticking with Haiku keeps the config uniform and matches
     * the existing [com.lightningkite.services.ai.bedrock.BedrockLiveTest] choice.
     */
    val cheapModel: LlmModelId = LlmModelId("anthropic.claude-3-5-haiku-20241022-v1:0")

    /** Haiku supports vision, so the multimodal suite reuses [cheapModel]. */
    val visionModel: LlmModelId = cheapModel

    /**
     * True when credentials are available — either the `lk` profile exists in
     * `~/.aws/credentials` or the env vars are set. Gates every `@Test` via `servicePresent`.
     */
    val servicePresent: Boolean by lazy {
        profileAvailable(AWS_PROFILE) ||
            (System.getenv("AWS_ACCESS_KEY_ID") != null && System.getenv("AWS_SECRET_ACCESS_KEY") != null)
    }

    private val context: TestSettingContext by lazy { TestSettingContext() }

    /**
     * Live [LlmAccess] wired through the `bedrock://` URL scheme. Built lazily so missing
     * credentials don't throw at class-load time — every test suite assume-skips instead.
     *
     * Prefers the `lk` AWS profile for local dev; falls back to the env-var default chain
     * so CI can still supply credentials the usual way.
     */
    val service: LlmAccess by lazy {
        BedrockLlmSettings.ensureRegistered()
        if (!servicePresent) {
            error("BedrockTestConfig.service accessed without AWS credentials; gate on servicePresent first")
        }
        val url = if (profileAvailable(AWS_PROFILE)) {
            "bedrock://$AWS_PROFILE@${cheapModel.id}?region=$region"
        } else {
            "bedrock://${cheapModel.id}?region=$region"
        }
        LlmAccess.Settings(url)("bedrock-integration", context)
    }

    /** Check whether the named profile has credentials in `~/.aws/credentials`. */
    private fun profileAvailable(profile: String): Boolean {
        val home = System.getProperty("user.home") ?: return false
        val file = java.io.File("$home/.aws/credentials")
        if (!file.exists()) return false
        return file.readText().contains("[$profile]")
    }
}
