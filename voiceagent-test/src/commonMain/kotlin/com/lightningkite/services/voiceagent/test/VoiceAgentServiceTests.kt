package com.lightningkite.services.voiceagent.test

import com.lightningkite.services.test.runTestWithClock
import com.lightningkite.services.voiceagent.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Abstract test suite for [VoiceAgentService] implementations.
 *
 * Implementations should extend this class and provide their service instance
 * to verify they satisfy the contract.
 *
 * ## Usage
 *
 * ```kotlin
 * class OpenAIVoiceAgentServiceTests : VoiceAgentServiceTests() {
 *     override val service: VoiceAgentService by lazy {
 *         // Create your service instance
 *     }
 * }
 * ```
 */
public abstract class VoiceAgentServiceTests {

    /**
     * The voice agent service to test.
     */
    public abstract val service: VoiceAgentService

    /**
     * Runs a suspending test. Override to customize test execution.
     */
    public open fun runSuspendingTest(body: suspend CoroutineScope.() -> Unit): TestResult = runTestWithClock { body() }

    @Test
    public fun createSession_returnsSession(): TestResult = runSuspendingTest {
        val config = VoiceAgentSessionConfig(
            instructions = "You are a helpful assistant.",
        )

        val session = service.createSession(config)

        assertNotNull(session.sessionId)
        assertEquals(config, session.config)

        session.close()
    }

    @Test
    public fun session_emitsSessionCreatedEvent(): TestResult = runSuspendingTest {
        val session = service.createSession()

        val event = withTimeout(5.seconds) {
            session.events.first()
        }

        assertIs<VoiceAgentEvent.SessionCreated>(event)
        assertEquals(session.sessionId, (event as VoiceAgentEvent.SessionCreated).sessionId)

        session.close()
    }

    @Test
    public fun session_canSendAudio(): TestResult = runSuspendingTest {
        val session = service.createSession()

        // Wait for session created
        withTimeout(5.seconds) {
            session.events.first { it is VoiceAgentEvent.SessionCreated }
        }

        // Send some audio (doesn't need to be real audio for test)
        val audio = ByteArray(1024) { it.toByte() }
        session.sendAudio(audio)

        // Should not throw
        session.close()
    }

    @Test
    public fun session_canCommitAudio(): TestResult = runSuspendingTest {
        val session = service.createSession(
            VoiceAgentSessionConfig(
                turnDetection = TurnDetection.None,
            )
        )

        // Wait for session created
        withTimeout(5.seconds) {
            session.events.first { it is VoiceAgentEvent.SessionCreated }
        }

        // Send and commit audio
        val audio = ByteArray(1024) { it.toByte() }
        session.sendAudio(audio)
        session.commitAudio()

        // Should not throw
        session.close()
    }

    @Test
    public fun session_canClearInputBuffer(): TestResult = runSuspendingTest {
        val session = service.createSession()

        // Wait for session created
        withTimeout(5.seconds) {
            session.events.first { it is VoiceAgentEvent.SessionCreated }
        }

        val audio = ByteArray(1024) { it.toByte() }
        session.sendAudio(audio)
        session.clearInputBuffer()

        // Should not throw
        session.close()
    }

    @Test
    public fun session_canCancelResponse(): TestResult = runSuspendingTest {
        val session = service.createSession()

        // Wait for session created
        withTimeout(5.seconds) {
            session.events.first { it is VoiceAgentEvent.SessionCreated }
        }

        session.cancelResponse()

        // Should not throw
        session.close()
    }

    @Test
    public fun session_canUpdateSession(): TestResult = runSuspendingTest {
        val initialConfig = VoiceAgentSessionConfig(
            instructions = "Initial instructions",
        )
        val session = service.createSession(initialConfig)

        // Wait for session created
        withTimeout(5.seconds) {
            session.events.first { it is VoiceAgentEvent.SessionCreated }
        }

        val newConfig = VoiceAgentSessionConfig(
            instructions = "Updated instructions",
            voice = VoiceConfig(name = "echo"),
        )
        session.updateSession(newConfig)

        // Should not throw
        session.close()
    }

    @Test
    public fun session_canAddMessage(): TestResult = runSuspendingTest {
        val session = service.createSession()

        // Wait for session created
        withTimeout(5.seconds) {
            session.events.first { it is VoiceAgentEvent.SessionCreated }
        }

        session.addMessage("user", "Hello, how are you?")

        // Should not throw
        session.close()
    }

    @Test
    public fun session_canSendToolResult(): TestResult = runSuspendingTest {
        val session = service.createSession(
            VoiceAgentSessionConfig(
                tools = listOf(
                    SerializableToolDescriptor(
                        name = "test_tool",
                        description = "A test tool",
                    )
                )
            )
        )

        // Wait for session created
        withTimeout(5.seconds) {
            session.events.first { it is VoiceAgentEvent.SessionCreated }
        }

        // Simulate sending a tool result
        session.sendToolResult("test-call-id", """{"result": "success"}""")

        // Should not throw
        session.close()
    }

    @Test
    public fun healthCheck_returnsOK(): TestResult = runSuspendingTest {
        val status = service.healthCheck()
        assertEquals(com.lightningkite.services.HealthStatus.Level.OK, status.level)
    }
}
