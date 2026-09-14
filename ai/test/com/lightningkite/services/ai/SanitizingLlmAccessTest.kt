package com.lightningkite.services.ai

import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.SettingContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// ──────────────────────────────────────────────────────────────────────
//  Detector Tests
// ──────────────────────────────────────────────────────────────────────

class RegexDetectorTest {
    @Test
    fun ssnDetection() {
        val matches = CommonPatterns.SSN.findAll("My SSN is 123-45-6789 and hers is 987-65-4321.")
        assertEquals(2, matches.size)
        assertEquals("123-45-6789", "My SSN is 123-45-6789 and hers is 987-65-4321.".substring(matches[0]))
        assertEquals("987-65-4321", "My SSN is 123-45-6789 and hers is 987-65-4321.".substring(matches[1]))
    }

    @Test
    fun emailDetection() {
        val text = "Contact alice@example.com or bob.smith+work@corp.co.uk"
        val matches = CommonPatterns.EMAIL.findAll(text)
        assertEquals(2, matches.size)
        assertEquals("alice@example.com", text.substring(matches[0]))
        assertEquals("bob.smith+work@corp.co.uk", text.substring(matches[1]))
    }

    @Test
    fun apiKeyDetection() {
        val text = "Use key sk-abc123def456ghi789jkl012 for auth"
        val matches = CommonPatterns.API_KEY.findAll(text)
        assertEquals(1, matches.size)
        assertEquals("sk-abc123def456ghi789jkl012", text.substring(matches[0]))
    }

    @Test
    fun noFalsePositives() {
        val text = "The number 42 is just a number. Hello world."
        assertEquals(0, CommonPatterns.SSN.findAll(text).size)
        assertEquals(0, CommonPatterns.EMAIL.findAll(text).size)
        assertEquals(0, CommonPatterns.API_KEY.findAll(text).size)
    }
}

class ExplicitValueDetectorTest {
    @Test
    fun findsExactValues() {
        val detector = ExplicitValueDetector(setOf("SECRET_KEY_123", "password"))
        val text = "The password is SECRET_KEY_123 and password again"
        val matches = detector.findAll(text)
        // "password" appears twice, "SECRET_KEY_123" once
        assertEquals(3, matches.size)
    }

    @Test
    fun emptyValuesIgnored() {
        val detector = ExplicitValueDetector(setOf("", "real"))
        val text = "Something real here"
        val matches = detector.findAll(text)
        assertEquals(1, matches.size)
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Mapping Tests
// ──────────────────────────────────────────────────────────────────────

class SanitizationMappingTest {
    @Test
    fun roundTrip() {
        val mapping = SanitizationMapping()
        mapping.getOrCreatePlaceholder("123-45-6789")
        val sanitized = mapping.sanitize("SSN: 123-45-6789")
        assertEquals("SSN: <<REDACTED_00>>", sanitized)
        assertEquals("SSN: 123-45-6789", mapping.restore(sanitized))
    }

    @Test
    fun multipleValues() {
        val mapping = SanitizationMapping()
        mapping.getOrCreatePlaceholder("secret1")
        mapping.getOrCreatePlaceholder("secret2")
        val sanitized = mapping.sanitize("A=secret1 B=secret2")
        assertEquals("A=<<REDACTED_00>> B=<<REDACTED_01>>", sanitized)
        assertEquals("A=secret1 B=secret2", mapping.restore(sanitized))
    }

    @Test
    fun duplicateValueSamePlaceholder() {
        val mapping = SanitizationMapping()
        val p1 = mapping.getOrCreatePlaceholder("same")
        val p2 = mapping.getOrCreatePlaceholder("same")
        assertEquals(p1, p2)
    }

    @Test
    fun longerMatchReplacedFirst() {
        val mapping = SanitizationMapping()
        mapping.getOrCreatePlaceholder("12")
        mapping.getOrCreatePlaceholder("1234")
        // "1234" should be replaced as a whole, not as "<<REDACTED_00>>34"
        val sanitized = mapping.sanitize("value: 1234")
        assertEquals("value: <<REDACTED_01>>", sanitized)
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Streaming Restorer Tests
// ──────────────────────────────────────────────────────────────────────

class StreamingRestorerTest {
    private fun mappingWith(vararg values: String): SanitizationMapping {
        val m = SanitizationMapping()
        values.forEach { m.getOrCreatePlaceholder(it) }
        return m
    }

    @Test
    fun completePlaceholderInOneChunk() {
        val mapping = mappingWith("secret")
        val restorer = StreamingRestorer(mapping)
        val result = restorer.feed("Hello <<REDACTED_00>> world")
        // May hold back trailing chars if they look like placeholder start — flush to get rest
        val flushed = restorer.flush()
        assertEquals("Hello secret world", result + flushed)
    }

    @Test
    fun placeholderSplitAcrossTwoChunks() {
        val mapping = mappingWith("secret")
        val restorer = StreamingRestorer(mapping)
        val r1 = restorer.feed("Hello <<REDAC")
        val r2 = restorer.feed("TED_00>> world")
        val r3 = restorer.flush()
        assertEquals("Hello secret world", r1 + r2 + r3)
    }

    @Test
    fun placeholderSplitAcrossThreeChunks() {
        val mapping = mappingWith("secret")
        val restorer = StreamingRestorer(mapping)
        val r1 = restorer.feed("<<RED")
        val r2 = restorer.feed("ACTED_")
        val r3 = restorer.feed("00>>!")
        val r4 = restorer.flush()
        assertEquals("secret!", r1 + r2 + r3 + r4)
    }

    @Test
    fun falseAlarmSingleLessThan() {
        val mapping = mappingWith("x")
        val restorer = StreamingRestorer(mapping)
        val r1 = restorer.feed("a < b")
        val r2 = restorer.flush()
        assertEquals("a < b", r1 + r2)
    }

    @Test
    fun falseAlarmDoubleLessThan() {
        val mapping = mappingWith("x")
        val restorer = StreamingRestorer(mapping)
        val r1 = restorer.feed("use << for")
        val r2 = restorer.feed(" shift")
        val r3 = restorer.flush()
        // "<<" at end of first chunk matches partial prefix "<<" of "<<REDACTED_",
        // so it's held back, then resolved when next chunk doesn't continue the pattern.
        assertEquals("use << for shift", r1 + r2 + r3)
    }

    @Test
    fun noPlaceholdersPassThrough() {
        val mapping = mappingWith("x")
        val restorer = StreamingRestorer(mapping)
        val r1 = restorer.feed("just normal text")
        val r2 = restorer.flush()
        assertEquals("just normal text", r1 + r2)
    }
}

// ──────────────────────────────────────────────────────────────────────
//  partialPrefixOverlap Tests
// ──────────────────────────────────────────────────────────────────────

class PartialPrefixOverlapTest {
    @Test
    fun fullMatch() {
        // Suffix "<<REDACTED" is a proper prefix of "<<REDACTED_"
        assertEquals(10, partialPrefixOverlap("hello<<REDACTED", "<<REDACTED_"))
    }

    @Test
    fun partialMatch() {
        assertEquals(5, partialPrefixOverlap("hello<<RED", "<<REDACTED_"))
    }

    @Test
    fun singleChar() {
        assertEquals(1, partialPrefixOverlap("hello<", "<<REDACTED_"))
    }

    @Test
    fun noMatch() {
        assertEquals(0, partialPrefixOverlap("hello world", "<<REDACTED_"))
    }
}

// ──────────────────────────────────────────────────────────────────────
//  buildMapping Tests
// ──────────────────────────────────────────────────────────────────────

class BuildMappingTest {
    @Test
    fun scansSystemPromptAndMessages() {
        val prompt = LlmPrompt(
            systemPrompt = systemPrompt("Contact admin@corp.com for help"),
            messages = listOf(
                userMessage("My SSN is 111-22-3333"),
                LlmMessage.Agent(listOf(LlmPart.Text("Got it"))),
                toolResult("t1", "Result for 444-55-6666"),
            ),
        )
        val mapping = buildMapping(prompt, listOf(CommonPatterns.SSN, CommonPatterns.EMAIL))
        // Should have found: admin@corp.com, 111-22-3333, 444-55-6666
        val sanitized = mapping.sanitize("admin@corp.com 111-22-3333 444-55-6666")
        assertTrue("<<REDACTED_" in sanitized, "Expected placeholders in: $sanitized")
        assertEquals("admin@corp.com 111-22-3333 444-55-6666", mapping.restore(sanitized))
    }

    @Test
    fun emptyWhenNoMatches() {
        val prompt = LlmPrompt(
            systemPrompt = systemPrompt("You are helpful"),
            messages = listOf(userMessage("What is 2+2?")),
        )
        val mapping = buildMapping(prompt, listOf(CommonPatterns.SSN))
        assertTrue(mapping.isEmpty)
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Full Decorator Integration Tests
// ──────────────────────────────────────────────────────────────────────

class SanitizingLlmAccessTest {
    /** Fake LLM that echoes back the first user message text. */
    private class EchoLlmAccess : LlmAccess {
        override val name: String = "echo"
        override val context: SettingContext get() = error("unused")
        override suspend fun getModels(): List<LlmModelInfo> = emptyList()
        override val healthCheckFrequency: Duration = 1.minutes
        override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)

        var lastPrompt: LlmPrompt? = null

        override suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent> {
            lastPrompt = prompt
            val userText = (prompt.messages.firstOrNull() as? LlmMessage.User)
                ?.parts?.filterIsInstance<LlmPart.Text>()?.joinToString("") { it.text }
                ?: ""
            return flow {
                // Split the echo into chunks to test streaming restoration
                for (i in userText.indices) {
                    emit(LlmStreamEvent.TextDelta(userText[i].toString()))
                }
                emit(LlmStreamEvent.Finished(LlmStopReason.EndTurn, LlmUsage(10, 5)))
            }
        }
    }

    @Test
    fun sanitizesPromptAndRestoresResponse() = runTest {
        val echo = EchoLlmAccess()
        val safe = echo.sanitized(CommonPatterns.SSN, explicitValues = emptySet())

        val prompt = LlmPrompt(
            systemPrompt = systemPrompt("You are helpful"),
            messages = listOf(userMessage("My SSN is 123-45-6789")),
        )
        val events = safe.stream(LlmModelId("test"), prompt).toList()

        // The delegate should have received a sanitized prompt
        val delegateText = (echo.lastPrompt!!.messages[0] as LlmMessage.User)
            .parts.filterIsInstance<LlmPart.Text>().joinToString("") { it.text }
        assertTrue("<<REDACTED_00>>" in delegateText, "Delegate prompt should be sanitized: $delegateText")
        assertTrue("123-45-6789" !in delegateText, "Real SSN should not reach delegate")

        // The response events should have the real value restored
        val responseText = events.filterIsInstance<LlmStreamEvent.TextDelta>()
            .joinToString("") { it.text }
        assertEquals("My SSN is 123-45-6789", responseText)
    }

    @Test
    fun passesThoughWhenNoSensitiveData() = runTest {
        val echo = EchoLlmAccess()
        val safe = echo.sanitized(CommonPatterns.SSN)

        val prompt = LlmPrompt(
            systemPrompt = systemPrompt("You are helpful"),
            messages = listOf(userMessage("What is 2+2?")),
        )
        val events = safe.stream(LlmModelId("test"), prompt).toList()

        // No sanitization needed — delegate gets original prompt
        val delegateText = (echo.lastPrompt!!.messages[0] as LlmMessage.User)
            .parts.filterIsInstance<LlmPart.Text>().joinToString("") { it.text }
        assertEquals("What is 2+2?", delegateText)

        val responseText = events.filterIsInstance<LlmStreamEvent.TextDelta>()
            .joinToString("") { it.text }
        assertEquals("What is 2+2?", responseText)
    }

    @Test
    fun explicitValuesSanitized() = runTest {
        val echo = EchoLlmAccess()
        val safe = echo.sanitized(explicitValues = setOf("MY_SECRET_API_KEY"))

        val prompt = LlmPrompt(
            systemPrompt = emptyList(),
            messages = listOf(userMessage("Key is MY_SECRET_API_KEY")),
        )
        safe.stream(LlmModelId("test"), prompt).toList()

        val delegateText = (echo.lastPrompt!!.messages[0] as LlmMessage.User)
            .parts.filterIsInstance<LlmPart.Text>().joinToString("") { it.text }
        assertTrue("MY_SECRET_API_KEY" !in delegateText, "API key should be redacted")
        assertTrue("<<REDACTED_00>>" in delegateText)
    }
}
