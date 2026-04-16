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
 * Required environment variables for the suite to actually run:
 *  - `AWS_ACCESS_KEY_ID`
 *  - `AWS_SECRET_ACCESS_KEY`
 *
 * Optional:
 *  - `AWS_SESSION_TOKEN` — when using STS / temporary credentials
 *  - `AWS_REGION` — defaults to `us-west-2` if unset
 *
 * Note: The target AWS account must have model access **enabled** for the chosen model id in
 * the chosen region. Bedrock gates model access per-account and per-region; a valid key alone
 * is not enough. If the tests fail with `AccessDeniedException` the fix is to enable the
 * model in the AWS Bedrock console, not to touch this file.
 */
internal object BedrockTestConfig {

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

    /** True iff both required AWS env vars are present; gates every `@Test` via `servicePresent`. */
    val servicePresent: Boolean =
        System.getenv("AWS_ACCESS_KEY_ID") != null &&
            System.getenv("AWS_SECRET_ACCESS_KEY") != null

    private val context: TestSettingContext by lazy { TestSettingContext() }

    /**
     * Live [LlmAccess] wired through the `bedrock://` URL scheme. Built lazily so a missing
     * key doesn't throw at class-load time — it just leaves every test suite to assume-skip.
     *
     * Uses the default credential chain (env vars) rather than baking keys into the URL so
     * accidental log output can't leak credentials.
     */
    val service: LlmAccess by lazy {
        BedrockLlmSettings.ensureRegistered()
        if (!servicePresent) {
            error("BedrockTestConfig.service accessed without AWS credentials; gate on servicePresent first")
        }
        LlmAccess.Settings("bedrock://${cheapModel.asString}?region=$region")(
            "bedrock-integration",
            context,
        )
    }
}
