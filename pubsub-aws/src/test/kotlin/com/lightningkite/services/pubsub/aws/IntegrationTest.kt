package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.get
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for AWS WebSocket PubSub.
 *
 * These tests require a deployed AWS WebSocket PubSub infrastructure.
 * Run DeployForTesting.main() first to deploy the infrastructure and save the URL.
 * The URL (including secret) will be saved to pubsub-aws/local/deployed-url.json.
 *
 * Alternatively, set the PUBSUB_AWS_URL environment variable directly.
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

    private val websocketUrl: String? = run {
        // First check environment variable
        System.getenv("PUBSUB_AWS_URL")?.let { return@run it }

        // Then try to read from local file saved by DeployForTesting
        val file = DeployForTesting.urlFile
        if (file.exists()) {
            try {
                val settings = Json.decodeFromString<PubSub.Settings>(file.readText())
                return@run settings.url
            } catch (e: Exception) {
                println("Failed to read URL from ${file.absolutePath}: ${e.message}")
            }
        }
        null
    }

    private fun createPubSub(): PubSub? {
        val url = websocketUrl ?: return null
        val context = TestSettingContext()
        return PubSub.Settings(url).invoke("test", context)
    }

    @Test
    fun `publish and receive single message`() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runBlocking
        }

        try {
            pubsub.connect()

            val channel = pubsub.get<TestMessage>("integration-test-single")
            val testMessage = TestMessage(id = "test-1", content = "Hello from integration test")

            // Start collecting BEFORE publishing
            // Use launch so collection starts immediately, not deferred with async
            var received: TestMessage? = null
            val job = launch {
                received = withTimeout(10.seconds) {
                    channel.first()
                }
            }

            // Give time for subscription to be established on server
            delay(1500)

            // Publish message
            channel.emit(testMessage)

            // Wait for collection to complete
            job.join()
            
            assertEquals(testMessage.id, received?.id)
            assertEquals(testMessage.content, received?.content)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `publish and receive multiple messages`() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runBlocking
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
    fun `string channel works`() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runBlocking
        }

        try {
            pubsub.connect()

            val channel = pubsub.string("integration-test-string")
            val testString = "Hello, String Channel!"

            val job = launch {
                val received = withTimeout(10.seconds) {
                    channel.first()
                }
                assertEquals(testString, received)
            }

            delay(1500)

            channel.emit(testString)

            job.join()
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `multiple subscribers receive same message`() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runBlocking
        }

        try {
            pubsub.connect()

            val channel = pubsub.get<TestMessage>("integration-test-fanout")
            val testMessage = TestMessage(id = "fanout-1", content = "Broadcast message")

            // Start multiple subscribers
            var received1: TestMessage? = null
            var received2: TestMessage? = null
            val job1 = launch {
                received1 = withTimeout(10.seconds) { channel.first() }
            }
            val job2 = launch {
                received2 = withTimeout(10.seconds) { channel.first() }
            }

            delay(1500)

            // Publish once
            channel.emit(testMessage)

            // Both should receive
            job1.join()
            job2.join()

            assertEquals(testMessage.id, received1?.id)
            assertEquals(testMessage.id, received2?.id)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `channel isolation works`() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runBlocking
        }

        try {
            pubsub.connect()

            val channel1 = pubsub.get<TestMessage>("integration-test-isolation-1")
            val channel2 = pubsub.get<TestMessage>("integration-test-isolation-2")

            val message1 = TestMessage(id = "iso-1", content = "For channel 1")
            val message2 = TestMessage(id = "iso-2", content = "For channel 2")

            var received1: TestMessage? = null
            var received2: TestMessage? = null
            val job1 = launch {
                received1 = withTimeout(10.seconds) { channel1.first() }
            }
            val job2 = launch {
                received2 = withTimeout(10.seconds) { channel2.first() }
            }

            delay(1500)

            // Publish to each channel
            channel1.emit(message1)
            channel2.emit(message2)

            // Each should only get its own message
            job1.join()
            job2.join()
            
            assertEquals("iso-1", received1?.id)
            assertEquals("iso-2", received2?.id)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `health check passes with valid connection`() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runBlocking
        }

        try {
            val status = pubsub.healthCheck()
            assertEquals(com.lightningkite.services.HealthStatus.Level.OK, status.level)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `connection without secret is rejected`() = runBlocking {
        val url = websocketUrl ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runBlocking
        }

        // Strip the secret from the URL to test unauthorized access
        val urlWithoutSecret = url.replace(Regex("[?&]secret=[^&]*"), "")
        val context = TestSettingContext()
        val pubsub = PubSub.Settings(urlWithoutSecret).invoke("test-no-secret", context)

        try {
            val status = pubsub.healthCheck()
            // Should fail to connect without the secret
            assertEquals(com.lightningkite.services.HealthStatus.Level.ERROR, status.level)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun `connection with wrong secret is rejected`() = runBlocking {
        val url = websocketUrl ?: run {
            println("Skipping integration test: PUBSUB_AWS_URL not set")
            return@runBlocking
        }

        // Replace the secret with a wrong one
        val urlWithWrongSecret = url.replace(Regex("secret=[^&]*"), "secret=wrongsecret123")
        val context = TestSettingContext()
        val pubsub = PubSub.Settings(urlWithWrongSecret).invoke("test-wrong-secret", context)

        try {
            val status = pubsub.healthCheck()
            // Should fail to connect with wrong secret
            assertEquals(com.lightningkite.services.HealthStatus.Level.ERROR, status.level)
        } finally {
            pubsub.disconnect()
        }
    }
}
