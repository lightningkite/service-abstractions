package com.lightningkite.services.pubsub.mqtt

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMqttPubSubTest {

    @Serializable
    data class TestMessage(val text: String, val value: Int)

    @Test
    fun testBasicPublishSubscribe() = runTest {
        val context = TestSettingContext()
        val pubsub = LocalMqttPubSub("test", context)

        val channel = pubsub.string("test/topic")

        val receivedMessages = mutableListOf<String>()

        // Launch collector
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

        delay(50)

        channel.emit("Message 1")
        channel.emit("Message 2")
        channel.emit("Message 3")

        delay(50)

        collectJob.cancel()
        collectJob.join()

        assertEquals(3, receivedMessages.size)
        assertEquals("Message 1", receivedMessages[0])
        assertEquals("Message 2", receivedMessages[1])
        assertEquals("Message 3", receivedMessages[2])
    }

    @Test
    fun testSerializedMessages() = runTest {
        val context = TestSettingContext()
        val pubsub = LocalMqttPubSub("test", context)

        val channel = pubsub.get("test/messages", TestMessage.serializer())

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

        delay(50)

        channel.emit(TestMessage("first", 42))
        channel.emit(TestMessage("second", 99))

        delay(50)

        collectJob.cancel()
        collectJob.join()

        assertEquals(2, receivedMessages.size)
        assertEquals("first", receivedMessages[0].text)
        assertEquals(42, receivedMessages[0].value)
        assertEquals("second", receivedMessages[1].text)
        assertEquals(99, receivedMessages[1].value)
    }

    @Test
    fun testMultipleSubscribers() = runTest {
        val context = TestSettingContext()
        val pubsub = LocalMqttPubSub("test", context)

        val channel = pubsub.string("test/broadcast")

        val received1 = mutableListOf<String>()
        val received2 = mutableListOf<String>()

        // First subscriber
        val job1 = launch {
            var count = 0
            channel.collect { msg ->
                received1.add(msg)
                count++
                if (count >= 2) {
                    throw kotlinx.coroutines.CancellationException()
                }
            }
        }

        // Second subscriber
        val job2 = launch {
            var count = 0
            channel.collect { msg ->
                received2.add(msg)
                count++
                if (count >= 2) {
                    throw kotlinx.coroutines.CancellationException()
                }
            }
        }

        delay(50)

        // Both should receive both messages
        channel.emit("Broadcast 1")
        channel.emit("Broadcast 2")

        delay(50)

        job1.cancel()
        job2.cancel()
        job1.join()
        job2.join()

        assertEquals(2, received1.size)
        assertEquals(2, received2.size)
        assertEquals("Broadcast 1", received1[0])
        assertEquals("Broadcast 1", received2[0])
        assertEquals("Broadcast 2", received1[1])
        assertEquals("Broadcast 2", received2[1])
    }

    @Test
    fun testSingleLevelWildcard() = runTest {
        val context = TestSettingContext()
        val pubsub = LocalMqttPubSub("test", context)

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

        delay(50)

        // Publish to specific topics that match the wildcard
        pubsub.string("sensor/room1/temperature").emit("20.5")
        pubsub.string("sensor/room2/temperature").emit("21.3")
        pubsub.string("sensor/outdoor/temperature").emit("15.2")

        // This should NOT match (wrong path structure)
        pubsub.string("sensor/temperature").emit("99.9")
        pubsub.string("sensor/room1/humidity").emit("65")

        delay(50)

        collectJob.cancel()
        collectJob.join()

        assertEquals(3, receivedMessages.size)
        assertTrue(receivedMessages.contains("20.5"))
        assertTrue(receivedMessages.contains("21.3"))
        assertTrue(receivedMessages.contains("15.2"))
    }

    @Test
    fun testMultiLevelWildcard() = runTest {
        val context = TestSettingContext()
        val pubsub = LocalMqttPubSub("test", context)

        // Subscribe with multi-level wildcard
        val wildcardChannel = pubsub.string("sensor/#")

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

        delay(50)

        // All of these should match
        pubsub.string("sensor/room1/temperature").emit("20.5")
        pubsub.string("sensor/room2/humidity").emit("65")
        pubsub.string("sensor/outdoor/temperature/current").emit("15.2")
        pubsub.string("sensor/data").emit("test")

        // This should NOT match
        pubsub.string("actuator/valve1/state").emit("open")

        delay(50)

        collectJob.cancel()
        collectJob.join()

        assertEquals(4, receivedMessages.size)
        assertTrue(receivedMessages.contains("20.5"))
        assertTrue(receivedMessages.contains("65"))
        assertTrue(receivedMessages.contains("15.2"))
        assertTrue(receivedMessages.contains("test"))
    }

    @Test
    fun testRetainedMessages() = runTest {
        val context = TestSettingContext()
        val pubsub = LocalMqttPubSub("test", context)

        val channel = pubsub.get(
            "retained/topic",
            TestMessage.serializer(),
            qos = MqttPubSub.QoS.AtLeastOnce,
            retained = true
        )

        // Publish retained message BEFORE subscribing
        channel.emit(TestMessage("retained", 123), retain = true)

        delay(50)

        val receivedMessages = mutableListOf<TestMessage>()

        // New subscriber should receive the retained message
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

        delay(50)

        collectJob.cancel()
        collectJob.join()

        assertEquals(1, receivedMessages.size)
        assertEquals("retained", receivedMessages[0].text)
        assertEquals(123, receivedMessages[0].value)
    }

    @Test
    fun testRetainedMessageUpdate() = runTest {
        val context = TestSettingContext()
        val pubsub = LocalMqttPubSub("test", context)

        val channel = pubsub.get(
            "retained/update",
            String.serializer(),
            qos = MqttPubSub.QoS.AtLeastOnce,
            retained = true
        )

        // Publish first retained message
        channel.emit("First", retain = true)
        delay(50)

        // Publish second retained message (should replace first)
        channel.emit("Second", retain = true)
        delay(50)

        val receivedMessages = mutableListOf<String>()

        // New subscriber should only receive the latest retained message
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

        delay(50)

        collectJob.cancel()
        collectJob.join()

        assertEquals(1, receivedMessages.size)
        assertEquals("Second", receivedMessages[0])
    }

    @Test
    fun testClearRetainedMessage() = runTest {
        val context = TestSettingContext()
        val pubsub = LocalMqttPubSub("test", context)

        val channel = pubsub.get(
            "retained/clear",
            String.serializer(),
            qos = MqttPubSub.QoS.AtLeastOnce,
            retained = true
        )

        // Publish retained message
        channel.emit("Will be cleared", retain = true)
        delay(50)

        // Clear retained message by publishing empty with retain flag
        channel.emit("", retain = true)
        delay(50)

        val receivedMessages = mutableListOf<String>()

        // New subscriber should NOT receive any retained message
        val collectJob = launch {
            channel.collect { msg ->
                receivedMessages.add(msg)
            }
        }

        delay(100)

        collectJob.cancel()
        collectJob.join()

        // Should have received nothing (empty string clears retained)
        assertTrue(receivedMessages.isEmpty() || (receivedMessages.size == 1 && receivedMessages[0] == ""))
    }

    @Test
    fun testDifferentQoSLevels() = runTest {
        val context = TestSettingContext()
        val pubsub = LocalMqttPubSub("test", context)

        // Test all QoS levels work
        val qos0Channel = pubsub.get("qos/0", String.serializer(), qos = MqttPubSub.QoS.AtMostOnce)
        val qos1Channel = pubsub.get("qos/1", String.serializer(), qos = MqttPubSub.QoS.AtLeastOnce)
        val qos2Channel = pubsub.get("qos/2", String.serializer(), qos = MqttPubSub.QoS.ExactlyOnce)

        val received0 = mutableListOf<String>()
        val received1 = mutableListOf<String>()
        val received2 = mutableListOf<String>()

        val job0 = launch {
            qos0Channel.collect { msg ->
                received0.add(msg)
                if (received0.size >= 1) throw kotlinx.coroutines.CancellationException()
            }
        }

        val job1 = launch {
            qos1Channel.collect { msg ->
                received1.add(msg)
                if (received1.size >= 1) throw kotlinx.coroutines.CancellationException()
            }
        }

        val job2 = launch {
            qos2Channel.collect { msg ->
                received2.add(msg)
                if (received2.size >= 1) throw kotlinx.coroutines.CancellationException()
            }
        }

        delay(50)

        qos0Channel.emit("QoS 0 message")
        qos1Channel.emit("QoS 1 message")
        qos2Channel.emit("QoS 2 message")

        delay(50)

        job0.cancel()
        job1.cancel()
        job2.cancel()
        job0.join()
        job1.join()
        job2.join()

        assertEquals(1, received0.size)
        assertEquals(1, received1.size)
        assertEquals(1, received2.size)
        assertEquals("QoS 0 message", received0[0])
        assertEquals("QoS 1 message", received1[0])
        assertEquals("QoS 2 message", received2[0])
    }

    @Test
    fun testTopicIsolation() = runTest {
        val context = TestSettingContext()
        val pubsub = LocalMqttPubSub("test", context)

        val channel1 = pubsub.string("topic/a")
        val channel2 = pubsub.string("topic/b")

        val received1 = mutableListOf<String>()
        val received2 = mutableListOf<String>()

        val job1 = launch {
            channel1.collect { msg ->
                received1.add(msg)
                if (received1.size >= 2) throw kotlinx.coroutines.CancellationException()
            }
        }

        val job2 = launch {
            channel2.collect { msg ->
                received2.add(msg)
                if (received2.size >= 2) throw kotlinx.coroutines.CancellationException()
            }
        }

        delay(50)

        // Each channel should only receive its own messages
        channel1.emit("A1")
        channel2.emit("B1")
        channel1.emit("A2")
        channel2.emit("B2")

        delay(50)

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
    }
}
