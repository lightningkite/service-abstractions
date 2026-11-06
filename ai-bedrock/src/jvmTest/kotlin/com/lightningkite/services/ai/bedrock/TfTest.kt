package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.test.assertPlannableAws
import kotlin.test.Test
import kotlin.test.assertEquals

class TfTest {
    init {
        BedrockLlmSettings.ensureRegistered()
    }

    @Test
    fun testAwsBedrockFoundationModel() {
        assertPlannableAws<LlmAccess.Settings>("testAwsBedrockFoundation") {
            it.awsBedrock(modelId = "anthropic.claude-sonnet-4-5-20250929-v1:0")
        }
    }

    @Test
    fun testAwsBedrockInferenceProfile() {
        assertPlannableAws<LlmAccess.Settings>("testAwsBedrockInference") {
            it.awsBedrock(modelId = "us.anthropic.claude-haiku-4-5")
        }
    }

    @Test
    fun globalInferenceProfileArns() {
        assertEquals(
            listOf(
                "arn:aws:bedrock:us-west-2:*:inference-profile/global.anthropic.claude-haiku-5-5",
                "arn:aws:bedrock:*::foundation-model/anthropic.claude-haiku-5-5",
                "arn:aws:bedrock:::foundation-model/anthropic.claude-haiku-5-5",
            ),
            bedrockModelArns("global.anthropic.claude-haiku-5-5", "us-west-2"),
        )
    }
}
