package com.lightningkite.services.pubsub.mqtt.paho.aws

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.pubsub.mqtt.MqttPubSub
import com.lightningkite.services.pubsub.mqtt.paho.PahoMqttPubSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

/**
 * Integration tests for AWS IoT Core using Paho MQTT client.
 *
 * These tests require a deployed AWS IoT Core infrastructure.
 * Run DeployAwsIotCore.main() first to deploy and save the configuration.
 * The config will be saved to pubsub-mqtt-paho/local/aws-iot-config.json.
 *
 * Alternatively, set the AWS_IOT_MQTT_URL environment variable directly.
 */
class AwsIotCoreIntegrationTest {

    @Serializable
    data class TestMessage(val text: String, val value: Int)

    companion object {
        @Serializable
        data class AwsIotConfig(
            val endpoint: String,
            val thingName: String,
            val certFile: String,
            val keyFile: String,
            val caFile: String
        ) {
            fun toUrl(): String {
                return "mqtts://$endpoint:8883?clientId=$thingName&certFile=$certFile&keyFile=$keyFile&caFile=$caFile"
            }
        }
        fun loadConfig(): AwsIotConfig? {
            // First check environment variable
            System.getenv("AWS_IOT_MQTT_URL")?.let { url ->
                // Parse URL back to config... or just use the URL directly
                // For now, prefer the config file approach
            }

            // Try multiple possible locations for the config file
            val possiblePaths = listOf(
                File("pubsub-mqtt-paho/local/aws-iot-config.json"),
                File("/Users/jivie/Projects/service-abstractions/pubsub-mqtt-paho/local/aws-iot-config.json"),
                File(System.getProperty("user.dir"), "pubsub-mqtt-paho/local/aws-iot-config.json")
            )

            for (configFile in possiblePaths) {
                if (configFile.exists()) {
                    try {
                        println("Found config at: ${configFile.absolutePath}")
                        return Json.decodeFromString<AwsIotConfig>(configFile.readText())
                    } catch (e: Exception) {
                        println("Failed to read config from ${configFile.absolutePath}: ${e.message}")
                    }
                }
            }

            println("Config file not found. Tried:")
            possiblePaths.forEach { println("  - ${it.absolutePath}") }
            return null
        }
    }

    private fun createPubSub(): PahoMqttPubSub? {
        val config = loadConfig() ?: return null

        // Force companion init to register handlers
        PahoMqttPubSub.Companion

        val context = TestSettingContext()
        return MqttPubSub.Settings(config.toUrl()).invoke("aws-iot-test", context) as PahoMqttPubSub
    }

    @Test
    fun testBasicPublishSubscribe() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping AWS IoT Core integration test: No configuration found")
            println("Run DeployAwsIotCore.main() first or set AWS_IOT_MQTT_URL environment variable")
            return@runBlocking
        }

        try {
            pubsub.connect()

            val channel = pubsub.string("test/aws-iot/basic")
            val receivedMessages = mutableListOf<String>()

            val collectJob = launch {
                var count = 0
                channel.collect { msg ->
                    receivedMessages.add(msg)
                    count++
                    if (count >= 3) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }

            delay(1000) // Give time for subscription to be established

            channel.emit("AWS IoT Message 1")
            channel.emit("AWS IoT Message 2")
            channel.emit("AWS IoT Message 3")

            delay(1000) // Give time for messages to be received

            collectJob.cancel()
            collectJob.join()

            assertEquals(3, receivedMessages.size)
            assertEquals("AWS IoT Message 1", receivedMessages[0])
            assertEquals("AWS IoT Message 2", receivedMessages[1])
            assertEquals("AWS IoT Message 3", receivedMessages[2])

            println("✓ AWS IoT Core basic pub/sub works!")
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testSerializedMessages() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping AWS IoT Core integration test: No configuration found")
            return@runBlocking
        }

        try {
            pubsub.connect()

            val channel = pubsub.get("test/aws-iot/serialized", TestMessage.serializer())
            val receivedMessages = mutableListOf<TestMessage>()

            val collectJob = launch {
                var count = 0
                channel.collect { msg ->
                    receivedMessages.add(msg)
                    count++
                    if (count >= 2) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }

            delay(1000)

            channel.emit(TestMessage("first", 42))
            channel.emit(TestMessage("second", 99))

            delay(1000)

            collectJob.cancel()
            collectJob.join()

            assertEquals(2, receivedMessages.size)
            assertEquals("first", receivedMessages[0].text)
            assertEquals(42, receivedMessages[0].value)
            assertEquals("second", receivedMessages[1].text)
            assertEquals(99, receivedMessages[1].value)

            println("✓ AWS IoT Core serialized messages work!")
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testSingleLevelWildcard() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping AWS IoT Core integration test: No configuration found")
            return@runBlocking
        }

        try {
            pubsub.connect()

            // Subscribe with wildcard
            val wildcardChannel = pubsub.string("sensor/+/temperature")
            val receivedMessages = mutableListOf<String>()

            val collectJob = launch {
                var count = 0
                wildcardChannel.collect { msg ->
                    receivedMessages.add(msg)
                    count++
                    if (count >= 3) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }

            delay(1000)

            // Publish to topics that match
            pubsub.string("sensor/room1/temperature").emit("20.5")
            delay(200)
            pubsub.string("sensor/room2/temperature").emit("21.3")
            delay(200)
            pubsub.string("sensor/outdoor/temperature").emit("15.2")

            delay(1000)

            collectJob.cancel()
            collectJob.join()

            assertEquals(3, receivedMessages.size)
            assertTrue(receivedMessages.contains("20.5"))
            assertTrue(receivedMessages.contains("21.3"))
            assertTrue(receivedMessages.contains("15.2"))

            println("✓ AWS IoT Core single-level wildcards work!")
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testMultiLevelWildcard() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping AWS IoT Core integration test: No configuration found")
            return@runBlocking
        }

        try {
            pubsub.connect()

            // Subscribe with multi-level wildcard
            val wildcardChannel = pubsub.string("device/#")
            val receivedMessages = mutableListOf<String>()

            val collectJob = launch {
                var count = 0
                wildcardChannel.collect { msg ->
                    receivedMessages.add(msg)
                    count++
                    if (count >= 4) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }

            delay(1000)

            // All of these should match
            pubsub.string("device/sensor1/data").emit("data1")
            delay(200)
            pubsub.string("device/sensor2/state/active").emit("data2")
            delay(200)
            pubsub.string("device/actuator/command").emit("data3")
            delay(200)
            pubsub.string("device/status").emit("data4")

            delay(1000)

            collectJob.cancel()
            collectJob.join()

            assertEquals(4, receivedMessages.size)
            assertTrue(receivedMessages.contains("data1"))
            assertTrue(receivedMessages.contains("data2"))
            assertTrue(receivedMessages.contains("data3"))
            assertTrue(receivedMessages.contains("data4"))

            println("✓ AWS IoT Core multi-level wildcards work!")
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testQoS1() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping AWS IoT Core integration test: No configuration found")
            return@runBlocking
        }

        try {
            pubsub.connect()

            val channel = pubsub.get(
                "test/aws-iot/qos1",
                TestMessage.serializer(),
                qos = MqttPubSub.QoS.AtLeastOnce
            )

            val receivedMessages = mutableListOf<TestMessage>()

            val collectJob = launch {
                var count = 0
                channel.collect { msg ->
                    receivedMessages.add(msg)
                    count++
                    if (count >= 1) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }

            delay(1000)

            channel.emit(TestMessage("QoS 1", 1))

            delay(1000)

            collectJob.cancel()
            collectJob.join()

            assertEquals(1, receivedMessages.size)
            assertEquals("QoS 1", receivedMessages[0].text)
            assertEquals(1, receivedMessages[0].value)

            println("✓ AWS IoT Core QoS 1 works!")
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testTopicIsolation() = runBlocking {
        val pubsub = createPubSub() ?: run {
            println("Skipping AWS IoT Core integration test: No configuration found")
            return@runBlocking
        }

        try {
            pubsub.connect()

            val channel1 = pubsub.string("test/aws-iot/topic/a")
            val channel2 = pubsub.string("test/aws-iot/topic/b")

            val received1 = mutableListOf<String>()
            val received2 = mutableListOf<String>()

            val job1 = launch {
                var count = 0
                channel1.collect { msg ->
                    received1.add(msg)
                    count++
                    if (count >= 2) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }

            val job2 = launch {
                var count = 0
                channel2.collect { msg ->
                    received2.add(msg)
                    count++
                    if (count >= 2) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }

            delay(1000)

            // Each channel should only receive its own messages
            channel1.emit("A1")
            channel2.emit("B1")
            delay(200)
            channel1.emit("A2")
            channel2.emit("B2")

            delay(1000)

            job1.cancel()
            job2.cancel()
            job1.join()
            job2.join()

            assertEquals(2, received1.size)
            assertEquals(2, received2.size)
            assertEquals("A1", received1[0])
            assertEquals("A2", received1[1])
            assertEquals("B1", received2[0])
            assertEquals("B2", received2[1])

            println("✓ AWS IoT Core topic isolation works!")
        } finally {
            pubsub.disconnect()
        }
    }
}
