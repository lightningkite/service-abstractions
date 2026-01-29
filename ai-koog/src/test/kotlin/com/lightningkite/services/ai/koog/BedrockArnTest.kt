package com.lightningkite.services.ai.koog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Bedrock ARN generation to ensure correct format for foundation models
 * vs cross-region inference profiles.
 */
class BedrockArnTest {

    @Test
    fun `foundation model IDs are not detected as inference profiles`() {
        // Standard foundation model IDs don't have regional prefixes
        assertFalse(isInferenceProfile("anthropic.claude-3-5-sonnet-20241022-v2:0"))
        assertFalse(isInferenceProfile("anthropic.claude-haiku-4-5-20251001-v1:0"))
        assertFalse(isInferenceProfile("amazon.nova-pro-v1:0"))
        assertFalse(isInferenceProfile("meta.llama3-70b-instruct-v1:0"))
    }

    @Test
    fun `cross-region inference profile IDs are detected correctly`() {
        // US inference profiles
        assertTrue(isInferenceProfile("us.anthropic.claude-3-5-sonnet-20241022-v2:0"))
        assertTrue(isInferenceProfile("us.anthropic.claude-haiku-4-5-20251001-v1:0"))
        assertTrue(isInferenceProfile("us.amazon.nova-pro-v1:0"))

        // EU inference profiles
        assertTrue(isInferenceProfile("eu.anthropic.claude-3-5-sonnet-20241022-v2:0"))

        // AP inference profiles
        assertTrue(isInferenceProfile("ap.anthropic.claude-3-5-sonnet-20241022-v2:0"))
    }

    @Test
    fun `getBaseModelId removes regional prefix`() {
        assertEquals(
            "anthropic.claude-haiku-4-5-20251001-v1:0",
            getBaseModelId("us.anthropic.claude-haiku-4-5-20251001-v1:0")
        )
        assertEquals(
            "anthropic.claude-3-5-sonnet-20241022-v2:0",
            getBaseModelId("eu.anthropic.claude-3-5-sonnet-20241022-v2:0")
        )
        assertEquals(
            "amazon.nova-pro-v1:0",
            getBaseModelId("ap.amazon.nova-pro-v1:0")
        )
        // Foundation model IDs without prefix are returned unchanged
        assertEquals(
            "anthropic.claude-3-5-sonnet-20241022-v2:0",
            getBaseModelId("anthropic.claude-3-5-sonnet-20241022-v2:0")
        )
    }

    @Test
    fun `foundation model returns single ARN`() {
        val modelId = "anthropic.claude-3-5-sonnet-20241022-v2:0"
        val region = "us-west-2"

        val arns = bedrockModelArns(modelId, region)

        assertEquals(1, arns.size)
        assertEquals(
            "arn:aws:bedrock:us-west-2::foundation-model/anthropic.claude-3-5-sonnet-20241022-v2:0",
            arns[0]
        )
    }

    @Test
    fun `inference profile returns two ARNs - profile and foundation model`() {
        val modelId = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
        val region = "us-west-2"

        val arns = bedrockModelArns(modelId, region)

        assertEquals(2, arns.size)

        // First ARN: the inference profile in the configured region
        assertEquals(
            "arn:aws:bedrock:us-west-2:*:inference-profile/us.anthropic.claude-haiku-4-5-20251001-v1:0",
            arns[0]
        )

        // Second ARN: the underlying foundation model with wildcard region
        // (because cross-region profiles route to any region internally)
        assertEquals(
            "arn:aws:bedrock:*::foundation-model/anthropic.claude-haiku-4-5-20251001-v1:0",
            arns[1]
        )
    }

    @Test
    fun `inference profile foundation model ARN uses wildcard region`() {
        // This is critical: cross-region inference profiles can route to ANY region
        // so the IAM policy needs wildcard region for the foundation model
        val arns = bedrockModelArns("us.anthropic.claude-haiku-4-5-20251001-v1:0", "us-west-2")

        val foundationModelArn = arns[1]
        assertTrue(foundationModelArn.startsWith("arn:aws:bedrock:*::foundation-model/"))
    }

    @Test
    fun `bedrockModelArn backwards compatibility returns first ARN`() {
        // bedrockModelArn (singular) returns the first ARN for backwards compatibility
        val modelId = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
        val region = "us-west-2"

        val singleArn = bedrockModelArn(modelId, region)
        val arns = bedrockModelArns(modelId, region)

        assertEquals(arns.first(), singleArn)
    }

    @Test
    fun `eu and ap prefixes also generate two ARNs`() {
        val euArns = bedrockModelArns("eu.anthropic.claude-3-5-sonnet-20241022-v2:0", "eu-west-1")
        assertEquals(2, euArns.size)
        assertEquals(
            "arn:aws:bedrock:eu-west-1:*:inference-profile/eu.anthropic.claude-3-5-sonnet-20241022-v2:0",
            euArns[0]
        )
        assertEquals(
            "arn:aws:bedrock:*::foundation-model/anthropic.claude-3-5-sonnet-20241022-v2:0",
            euArns[1]
        )

        val apArns = bedrockModelArns("ap.amazon.nova-pro-v1:0", "ap-northeast-1")
        assertEquals(2, apArns.size)
        assertEquals(
            "arn:aws:bedrock:ap-northeast-1:*:inference-profile/ap.amazon.nova-pro-v1:0",
            apArns[0]
        )
        assertEquals(
            "arn:aws:bedrock:*::foundation-model/amazon.nova-pro-v1:0",
            apArns[1]
        )
    }

    // Tests added by Claude for dash-prefixed inference profiles and edge cases
    @Test
    fun `dash-prefixed inference profiles are detected correctly`() {
        // Dash prefixes (us-, eu-, ap-) are also valid inference profile prefixes
        assertTrue(isInferenceProfile("us-anthropic.claude-3-5-sonnet-20241022-v2:0"))
        assertTrue(isInferenceProfile("eu-anthropic.claude-3-5-sonnet-20241022-v2:0"))
        assertTrue(isInferenceProfile("ap-anthropic.claude-3-5-sonnet-20241022-v2:0"))
    }

    @Test
    fun `getBaseModelId removes dash-prefixed regional prefix`() {
        assertEquals(
            "anthropic.claude-3-5-sonnet-20241022-v2:0",
            getBaseModelId("us-anthropic.claude-3-5-sonnet-20241022-v2:0")
        )
        assertEquals(
            "anthropic.claude-haiku-4-5-20251001-v1:0",
            getBaseModelId("eu-anthropic.claude-haiku-4-5-20251001-v1:0")
        )
        assertEquals(
            "amazon.nova-pro-v1:0",
            getBaseModelId("ap-amazon.nova-pro-v1:0")
        )
    }

    @Test
    fun `dash-prefixed inference profile generates two ARNs`() {
        val arns = bedrockModelArns("us-anthropic.claude-3-5-sonnet-20241022-v2:0", "us-east-1")

        assertEquals(2, arns.size)
        assertEquals(
            "arn:aws:bedrock:us-east-1:*:inference-profile/us-anthropic.claude-3-5-sonnet-20241022-v2:0",
            arns[0]
        )
        assertEquals(
            "arn:aws:bedrock:*::foundation-model/anthropic.claude-3-5-sonnet-20241022-v2:0",
            arns[1]
        )
    }

    @Test
    fun `empty model ID is not detected as inference profile`() {
        assertFalse(isInferenceProfile(""))
    }

    @Test
    fun `getBaseModelId returns empty string unchanged`() {
        assertEquals("", getBaseModelId(""))
    }

    @Test
    fun `model ID that happens to contain us or eu in middle is not inference profile`() {
        // Model IDs that contain "us" or "eu" but don't START with the prefix
        assertFalse(isInferenceProfile("anthropic.claude-opus-v1:0"))  // contains "us"
        assertFalse(isInferenceProfile("anthropic.neural-v1:0"))  // contains "eu"
        assertFalse(isInferenceProfile("cohere.command-r-plus-v1:0"))  // unrelated model
    }

    @Test
    fun `backwards compatibility bedrockModelArn for foundation model`() {
        val modelId = "anthropic.claude-3-5-sonnet-20241022-v2:0"
        val region = "us-west-2"

        val singleArn = bedrockModelArn(modelId, region)

        assertEquals(
            "arn:aws:bedrock:us-west-2::foundation-model/anthropic.claude-3-5-sonnet-20241022-v2:0",
            singleArn
        )
    }
}
