package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.get
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for AWS WebSocket PubSub.
 *
 * These tests require a deployed AWS WebSocket PubSub infrastructure.
 * Set the PUBSUB_AWS_URL environment variable to run these tests.
 *
 * Example:
 * ```
 * PUBSUB_AWS_URL=aws-wss://abc123.execute-api.us-east-1.amazonaws.com/prod ./gradlew :pubsub-aws:test
 * ```
 */
class IntegrationTest {

    init {
        AwsWebSocketPubSub
    }

    @Serializable
    data class TestMessage(
        val id: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val websocketUrl: String? = System.getenv("PUBSUB_AWS_URL")

    private fun createPubSub(): PubSub? {
        val url = websocketUrl ?: return null
        val context = TestSettingContext()
        return PubSub.Settings(url).invoke("test", context)
    }

    @Test
    fun `publish and receive single message`() = runTest(timeout = 30.seconds) {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runTest
        }

        try {
            pubsub.connect()

            val channel = pubsub.get<TestMessage>("integration-test-single")
            val testMessage = TestMessage(id = "test-1", content = "Hello from integration test")

            // Start subscriber in background
            val receivedDeferred = async {
                withTimeout(10.seconds) {
                    channel.first()
                }
            }

            // Give subscriber time to connect
            delay(500)

            // Publish message
            channel.emit(testMessage)

            // Wait for message
            val received = receivedDeferred.await()

            assertEquals(testMessage.id, received.id)
            assertEquals(testMessage.content, received.content)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `publish and receive multiple messages`() = runTest(timeout = 30.seconds) {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runTest
        }

        try {
            pubsub.connect()

            val channel = pubsub.get<TestMessage>("integration-test-multi")
            val messages = listOf(
                TestMessage(id = "multi-1", content = "First"),
                TestMessage(id = "multi-2", content = "Second"),
                TestMessage(id = "multi-3", content = "Third")
            )

            // Start subscriber
            val receivedDeferred = async {
                withTimeout(15.seconds) {
                    channel.take(3).toList()
                }
            }

            delay(500)

            // Publish messages
            messages.forEach { msg ->
                channel.emit(msg)
                delay(100) // Small delay between publishes
            }

            // Wait for all messages
            val received = receivedDeferred.await()

            assertEquals(3, received.size)
            assertEquals(messages.map { it.id }.toSet(), received.map { it.id }.toSet())
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `string channel works`() = runTest(timeout = 30.seconds) {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runTest
        }

        try {
            pubsub.connect()

            val channel = pubsub.string("integration-test-string")
            val testString = "Hello, String Channel!"

            val receivedDeferred = async {
                withTimeout(10.seconds) {
                    channel.first()
                }
            }

            delay(500)

            channel.emit(testString)

            val received = receivedDeferred.await()
            assertEquals(testString, received)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `multiple subscribers receive same message`() = runTest(timeout = 30.seconds) {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runTest
        }

        try {
            pubsub.connect()

            val channel = pubsub.get<TestMessage>("integration-test-fanout")
            val testMessage = TestMessage(id = "fanout-1", content = "Broadcast message")

            // Start multiple subscribers
            val subscriber1 = async {
                withTimeout(10.seconds) { channel.first() }
            }
            val subscriber2 = async {
                withTimeout(10.seconds) { channel.first() }
            }

            delay(500)

            // Publish once
            channel.emit(testMessage)

            // Both should receive
            val received1 = subscriber1.await()
            val received2 = subscriber2.await()

            assertEquals(testMessage.id, received1.id)
            assertEquals(testMessage.id, received2.id)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `channel isolation works`() = runTest(timeout = 30.seconds) {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runTest
        }

        try {
            pubsub.connect()

            val channel1 = pubsub.get<TestMessage>("integration-test-isolation-1")
            val channel2 = pubsub.get<TestMessage>("integration-test-isolation-2")

            val message1 = TestMessage(id = "iso-1", content = "For channel 1")
            val message2 = TestMessage(id = "iso-2", content = "For channel 2")

            val received1 = async {
                withTimeout(10.seconds) { channel1.first() }
            }
            val received2 = async {
                withTimeout(10.seconds) { channel2.first() }
            }

            delay(500)

            // Publish to each channel
            channel1.emit(message1)
            channel2.emit(message2)

            // Each should only get its own message
            assertEquals("iso-1", received1.await().id)
            assertEquals("iso-2", received2.await().id)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `health check passes with valid connection`() = runTest(timeout = 30.seconds) {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runTest
        }

        try {
            val status = pubsub.healthCheck()
            assertEquals(com.lightningkite.services.HealthStatus.Level.OK, status.level)
        } finally {
            pubsub.disconnect()
        }
    }
}
