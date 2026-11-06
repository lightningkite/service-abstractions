package com.lightningkite.services.ai.bedrock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RegionalClaudeTest {
    private fun known(id: String) = BedrockLlmAccess.KNOWN_MODELS.single { it.id.id == id }

    @Test
    fun swapsTheGlobalPrefixAndAddsTheRegionalPremium() {
        val eu = BedrockLlmAccess.regionalClaude(known("global.anthropic.claude-opus-5-5"), BedrockGeography.EU)
        assertEquals("eu.anthropic.claude-opus-5-5", eu.id.id)
        assertEquals("Claude Opus 5.5 (EU)", eu.name)
        assertEquals(4.4, eu.usdPerMillionInputTokens, 1e-9)
        assertEquals(22.0, eu.usdPerMillionOutputTokens, 1e-9)
        assertEquals(5.5, eu.usdPerMillionCacheWriteTokens!!, 1e-9)
        assertEquals(0.22, eu.usdPerMillionCacheReadTokens!!, 1e-9)
    }

    @Test
    fun keepsDatedIdSuffixes() {
        val us = BedrockLlmAccess.regionalClaude(known("global.anthropic.claude-haiku-4-5-20251001-v1:0"), BedrockGeography.US)
        assertEquals("us.anthropic.claude-haiku-4-5-20251001-v1:0", us.id.id)
    }

    @Test
    fun rejectsModelsThatAreNotGlobalClaudeProfiles() {
        assertFailsWith<IllegalArgumentException> {
            BedrockLlmAccess.regionalClaude(known("amazon.nova-pro-v1:0"), BedrockGeography.US)
        }
    }
}
