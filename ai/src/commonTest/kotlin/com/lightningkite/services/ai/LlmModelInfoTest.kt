package com.lightningkite.services.ai

import com.lightningkite.services.data.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmModelInfoTest {
    private val opus = LlmModelInfo(
        id = LlmModelId("claude-opus-5-5"),
        name = "Claude Opus 5.5",
        usdPerMillionInputTokens = 4.0,
        usdPerMillionOutputTokens = 20.0,
        usdPerMillionCacheWriteTokens = 5.0,
        usdPerMillionCacheReadTokens = 0.20,
    )

    @Test
    fun costPricesEachKindOfTokenAtItsOwnRate() {
        // 1M input: 600K read from cache, 100K written to it, 300K plain; plus 50K output.
        val usage = LlmUsage(inputTokens = 1_000_000, outputTokens = 50_000, cacheReadTokens = 600_000, cacheWriteTokens = 100_000)
        assertEquals(0.3 * 4.0 + 0.6 * 0.20 + 0.1 * 5.0 + 0.05 * 20.0, opus.usdCost(usage)!!, 1e-9)
    }

    @Test
    fun notSentNoteNamesTheTypeAndTheLink() {
        val zip = MediaType("application/zip")
        assertEquals(
            "[Attachment not sent: application/zip at https://example.com/a.zip isn't supported here]",
            LlmAttachment.Url(zip, "https://example.com/a.zip").notSentNote().text,
        )
        assertEquals("[Attachment not sent: application/zip isn't supported here]", LlmAttachment.Base64(zip, "UEs=").notSentNote().text)
    }

    @Test
    fun unknownCachePricesGiveNoCostOnlyWhenTheCacheWasUsed() {
        val unpriced = opus.copy(usdPerMillionCacheReadTokens = null, usdPerMillionCacheWriteTokens = null)
        assertEquals(0.001 * 4.0 + 0.001 * 20.0, unpriced.usdCost(LlmUsage(inputTokens = 1_000, outputTokens = 1_000))!!, 1e-12)
        assertNull(unpriced.usdCost(LlmUsage(inputTokens = 1_000, outputTokens = 1_000, cacheReadTokens = 500)))
    }
}
