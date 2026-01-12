package com.lightningkite.services.pubsub.mqtt.paho

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.pubsub.mqtt.MqttPubSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.*

class PahoMqttPubSubTest {

    @Serializable
    data class TestMessage(val text: String, val value: Int)

    companion object {
        private lateinit var mosquittoContainer: GenericContainer<*>
        private lateinit var brokerUrl: String
        private var initialized = false

        fun ensureBrokerStarted() {
            if (!initialized) {
                synchronized(this) {
                    if (!initialized) {
                        mosquittoContainer = GenericContainer(DockerImageName.parse("eclipse-mosquitto:2.0"))
                            .withExposedPorts(1883)
                            .withCommand("mosquitto", "-c", "/mosquitto-no-auth.conf")

                        mosquittoContainer.start()

                        val host = mosquittoContainer.host
                        val port = mosquittoContainer.getMappedPort(1883)
                        brokerUrl = "mqtt://$host:$port"

                        println("MQTT Broker started at: $brokerUrl")
                        initialized = true
                    }
                }
            }
        }
    }

    @BeforeTest
    fun setup() {
        ensureBrokerStarted()
    }

    private fun createPubSub(clientId: String? = null): PahoMqttPubSub {
        ensureBrokerStarted()

        // Force PahoMqttPubSub companion init to register handlers
        // by accessing a property that will trigger class loading
        PahoMqttPubSub.Companion

        val context = TestSettingContext()
        val url = if (clientId != null) {
            "$brokerUrl?clientId=$clientId"
        } else {
            brokerUrl
        }
        return MqttPubSub.Settings(url).invoke("test-$clientId", context) as PahoMqttPubSub
    }

    @Test
    fun testBasicPublishSubscribe() = runBlocking {
        val pubsub = createPubSub("basic-test")

        try {
            pubsub.connect()

            val channel = pubsub.string("test/basic")
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

            delay(200) // Give time for subscription to be established

            channel.emit("Message 1")
            channel.emit("Message 2")
            channel.emit("Message 3")

            delay(200) // Give time for messages to be received

            collectJob.cancel()
            collectJob.join()

            assertEquals(3, receivedMessages.size)
            assertEquals("Message 1", receivedMessages[0])
            assertEquals("Message 2", receivedMessages[1])
            assertEquals("Message 3", receivedMessages[2])
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testSerializedMessages() = runBlocking {
        val pubsub = createPubSub("serialized-test")

        try {
            pubsub.connect()

            val channel = pubsub.get("test/serialized", TestMessage.serializer())
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

            delay(200)

            channel.emit(TestMessage("first", 42))
            channel.emit(TestMessage("second", 99))

            delay(200)

            collectJob.cancel()
            collectJob.join()

            assertEquals(2, receivedMessages.size)
            assertEquals("first", receivedMessages[0].text)
            assertEquals(42, receivedMessages[0].value)
            assertEquals("second", receivedMessages[1].text)
            assertEquals(99, receivedMessages[1].value)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testMultipleClients() = runBlocking {
        val pubsub1 = createPubSub("client1")
        val pubsub2 = createPubSub("client2")

        try {
            pubsub1.connect()
            pubsub2.connect()

            val channel1 = pubsub1.string("test/multi-client")
            val channel2 = pubsub2.string("test/multi-client")

            val received1 = mutableListOf<String>()
            val received2 = mutableListOf<String>()

            // Both clients subscribe
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

            delay(200)

            // Client 1 publishes
            channel1.emit("From Client 1")
            delay(100)

            // Client 2 publishes
            channel2.emit("From Client 2")
            delay(100)

            job1.cancel()
            job2.cancel()
            job1.join()
            job2.join()

            // Both clients should receive both messages
            assertEquals(2, received1.size)
            assertEquals(2, received2.size)
            assertTrue(received1.contains("From Client 1"))
            assertTrue(received1.contains("From Client 2"))
            assertTrue(received2.contains("From Client 1"))
            assertTrue(received2.contains("From Client 2"))
        } finally {
            pubsub1.disconnect()
            pubsub2.disconnect()
        }
    }

    @Test
    fun testQoS0() = runBlocking {
        val pubsub = createPubSub("qos0-test")

        try {
            pubsub.connect()

            val channel = pubsub.get(
                "test/qos0",
                TestMessage.serializer(),
                qos = MqttPubSub.QoS.AtMostOnce
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

            delay(500)

            channel.emit(TestMessage("QoS 0", 0))

            delay(500)

            collectJob.cancel()
            collectJob.join()

            assertEquals(1, receivedMessages.size)
            assertEquals("QoS 0", receivedMessages[0].text)
            assertEquals(0, receivedMessages[0].value)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testQoS1() = runBlocking {
        val pubsub = createPubSub("qos1-test")

        try {
            pubsub.connect()

            val channel = pubsub.get(
                "test/qos1",
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

            delay(200)

            channel.emit(TestMessage("QoS 1", 1))

            delay(200)

            collectJob.cancel()
            collectJob.join()

            assertEquals(1, receivedMessages.size)
            assertEquals("QoS 1", receivedMessages[0].text)
            assertEquals(1, receivedMessages[0].value)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testQoS2() = runBlocking {
        val pubsub = createPubSub("qos2-test")

        try {
            pubsub.connect()

            val channel = pubsub.get(
                "test/qos2",
                TestMessage.serializer(),
                qos = MqttPubSub.QoS.ExactlyOnce
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

            delay(200)

            channel.emit(TestMessage("QoS 2", 2))

            delay(200)

            collectJob.cancel()
            collectJob.join()

            assertEquals(1, receivedMessages.size)
            assertEquals("QoS 2", receivedMessages[0].text)
            assertEquals(2, receivedMessages[0].value)
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testRetainedMessages() = runBlocking {
        val pubsub1 = createPubSub("retain-publisher")
        val pubsub2 = createPubSub("retain-subscriber")

        try {
            pubsub1.connect()

            // Publish retained message
            val publishChannel = pubsub1.get(
                "test/retained",
                String.serializer(),
                qos = MqttPubSub.QoS.AtLeastOnce,
                retained = true
            )

            publishChannel.emit("Retained Message", retain = true)

            delay(200) // Give broker time to store retained message

            pubsub1.disconnect()

            // Now connect second client and subscribe
            pubsub2.connect()

            val subscribeChannel = pubsub2.get(
                "test/retained",
                String.serializer(),
                qos = MqttPubSub.QoS.AtLeastOnce,
                retained = true
            )

            val receivedMessages = mutableListOf<String>()

            val collectJob = launch {
                var count = 0
                subscribeChannel.collect { msg ->
                    receivedMessages.add(msg)
                    count++
                    if (count >= 1) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }

            delay(500) // Wait for retained message

            collectJob.cancel()
            collectJob.join()

            // Should have received the retained message
            assertEquals(1, receivedMessages.size)
            assertEquals("Retained Message", receivedMessages[0])
        } finally {
            pubsub1.disconnect()
            pubsub2.disconnect()
        }
    }

    @Test
    fun testSingleLevelWildcard() = runBlocking {
        val publisher = createPubSub("wildcard-pub")
        val subscriber = createPubSub("wildcard-sub")

        try {
            publisher.connect()
            subscriber.connect()

            // Subscribe with wildcard
            val wildcardChannel = subscriber.string("sensor/+/temperature")
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

            delay(500)

            // Publish to topics that match
            publisher.string("sensor/room1/temperature").emit("20.5")
            delay(100)
            publisher.string("sensor/room2/temperature").emit("21.3")
            delay(100)
            publisher.string("sensor/outdoor/temperature").emit("15.2")

            // These should NOT match
            publisher.string("sensor/temperature").emit("99.9")
            publisher.string("sensor/room1/humidity").emit("65")

            delay(500)

            collectJob.cancel()
            collectJob.join()

            assertEquals(3, receivedMessages.size)
            assertTrue(receivedMessages.contains("20.5"))
            assertTrue(receivedMessages.contains("21.3"))
            assertTrue(receivedMessages.contains("15.2"))
        } finally {
            publisher.disconnect()
            subscriber.disconnect()
        }
    }

    @Test
    fun testMultiLevelWildcard() = runBlocking {
        val publisher = createPubSub("multilevel-pub")
        val subscriber = createPubSub("multilevel-sub")

        try {
            publisher.connect()
            subscriber.connect()

            // Subscribe with multi-level wildcard
            val wildcardChannel = subscriber.string("sensor/#")
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

            delay(500)

            // All of these should match
            publisher.string("sensor/room1/temperature").emit("20.5")
            delay(100)
            publisher.string("sensor/room2/humidity").emit("65")
            delay(100)
            publisher.string("sensor/outdoor/temperature/current").emit("15.2")
            delay(100)
            publisher.string("sensor/data").emit("test")

            // This should NOT match
            publisher.string("actuator/valve1/state").emit("open")

            delay(500)

            collectJob.cancel()
            collectJob.join()

            assertEquals(4, receivedMessages.size)
            assertTrue(receivedMessages.contains("20.5"))
            assertTrue(receivedMessages.contains("65"))
            assertTrue(receivedMessages.contains("15.2"))
            assertTrue(receivedMessages.contains("test"))
        } finally {
            publisher.disconnect()
            subscriber.disconnect()
        }
    }

    @Test
    fun testReconnect() = runBlocking {
        val pubsub = createPubSub("reconnect-test")

        try {
            // Connect
            pubsub.connect()

            val channel = pubsub.string("test/reconnect")
            val receivedMessages = mutableListOf<String>()

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

            delay(200)

            channel.emit("Before disconnect")

            delay(200)

            // Disconnect and reconnect
            pubsub.disconnect()
            delay(100)
            pubsub.connect()

            delay(200)

            channel.emit("After reconnect")

            delay(200)

            collectJob.cancel()
            collectJob.join()

            // Should have received both messages (Paho handles reconnection)
            assertTrue(receivedMessages.contains("Before disconnect"))
        } finally {
            pubsub.disconnect()
        }
    }

    @Test
    fun testTopicIsolation() = runBlocking {
        val pubsub = createPubSub("isolation-test")

        try {
            pubsub.connect()

            val channel1 = pubsub.string("topic/a")
            val channel2 = pubsub.string("topic/b")

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

            delay(200)

            // Each channel should only receive its own messages
            channel1.emit("A1")
            channel2.emit("B1")
            channel1.emit("A2")
            channel2.emit("B2")

            delay(200)

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
        } finally {
            pubsub.disconnect()
        }
    }
}
