package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.SettingContext
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for AwsWebSocketPubSub.
 *
 * These tests verify the message protocol serialization without requiring
 * actual AWS infrastructure.
 */
class AwsWebSocketPubSubTest {

    init {
        // Ensure the companion object is initialized (registers URL schemes)
        AwsWebSocketPubSub
    }

    @Serializable
    data class TestEvent(
        val id: String,
        val value: Int,
        val nested: NestedData? = null
    )

    @Serializable
    data class NestedData(
        val items: List<String>,
        val metadata: Map<String, String> = emptyMap()
    )

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun `URL scheme registration works for aws-ws`() {
        assertTrue(PubSub.Settings.supports("aws-ws"))
    }

    @Test
    fun `URL scheme registration works for aws-wss`() {
        assertTrue(PubSub.Settings.supports("aws-wss"))
    }

    @Test
    fun `subscribe request serialization`() {
        @Serializable
        data class SubscribeRequest(val action: String, val channel: String)

        val request = SubscribeRequest(action = "subscribe", channel = "test-channel")
        val serialized = json.encodeToString(SubscribeRequest.serializer(), request)

        assertEquals("""{"action":"subscribe","channel":"test-channel"}""", serialized)
    }

    @Test
    fun `publish request serialization`() {
        @Serializable
        data class PublishRequest(val action: String, val channel: String, val message: String)

        val event = TestEvent(id = "123", value = 42)
        val eventJson = json.encodeToString(TestEvent.serializer(), event)
        val request = PublishRequest(action = "publish", channel = "events", message = eventJson)
        val serialized = json.encodeToString(PublishRequest.serializer(), request)

        assertTrue(serialized.contains(""""action":"publish""""))
        assertTrue(serialized.contains(""""channel":"events""""))
        assertTrue(serialized.contains(""""message":""""))
    }

    @Test
    fun `incoming message deserialization`() {
        @Serializable
        data class IncomingMessage(val channel: String? = null, val message: String? = null)

        val incoming = """{"channel":"events","message":"{\"id\":\"123\",\"value\":42}"}"""
        val parsed = json.decodeFromString(IncomingMessage.serializer(), incoming)

        assertEquals("events", parsed.channel)
        assertEquals("""{"id":"123","value":42}""", parsed.message)

        // Parse the nested message
        val event = json.decodeFromString(TestEvent.serializer(), parsed.message!!)
        assertEquals("123", event.id)
        assertEquals(42, event.value)
    }

    @Test
    fun `complex nested data serialization roundtrip`() {
        val event = TestEvent(
            id = "complex-1",
            value = 100,
            nested = NestedData(
                items = listOf("a", "b", "c"),
                metadata = mapOf("key1" to "value1", "key2" to "value2")
            )
        )

        val serialized = json.encodeToString(TestEvent.serializer(), event)
        val deserialized = json.decodeFromString(TestEvent.serializer(), serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `settings URL parsing for aws-wss`() {
        val settings = PubSub.Settings("aws-wss://abc123.execute-api.us-east-1.amazonaws.com/prod")
        assertEquals("aws-wss://abc123.execute-api.us-east-1.amazonaws.com/prod", settings.url)
    }

    @Test
    fun `settings URL parsing for aws-ws`() {
        val settings = PubSub.Settings("aws-ws://localhost:4510")
        assertEquals("aws-ws://localhost:4510", settings.url)
    }

    @Test
    fun `helper function creates correct settings`() {
        with(AwsWebSocketPubSub) {
            val settings = PubSub.Settings.awsWebSocket("abc123.execute-api.us-east-1.amazonaws.com/prod", secure = true)
            assertEquals("aws-wss://abc123.execute-api.us-east-1.amazonaws.com/prod", settings.url)
        }
    }

    @Test
    fun `helper function creates insecure settings`() {
        with(AwsWebSocketPubSub) {
            val settings = PubSub.Settings.awsWebSocket("localhost:4510", secure = false)
            assertEquals("aws-ws://localhost:4510", settings.url)
        }
    }

    @Test
    fun `special characters in channel names are preserved`() {
        @Serializable
        data class SubscribeRequest(val action: String = "subscribe", val channel: String)

        val request = SubscribeRequest(channel = "user:123/events")
        val serialized = json.encodeToString(SubscribeRequest.serializer(), request)
        val deserialized = json.decodeFromString(SubscribeRequest.serializer(), serialized)

        assertEquals("user:123/events", deserialized.channel)
    }

    @Test
    fun `unicode in messages is handled correctly`() {
        val event = TestEvent(id = "unicode-\u00e9\u00e8\u00ea", value = 1)
        val serialized = json.encodeToString(TestEvent.serializer(), event)
        val deserialized = json.decodeFromString(TestEvent.serializer(), serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `empty nested data serialization`() {
        val event = TestEvent(
            id = "empty",
            value = 0,
            nested = NestedData(items = emptyList())
        )

        val serialized = json.encodeToString(TestEvent.serializer(), event)
        val deserialized = json.decodeFromString(TestEvent.serializer(), serialized)

        assertEquals(event, deserialized)
        assertEquals(emptyList(), deserialized.nested?.items)
    }
}
