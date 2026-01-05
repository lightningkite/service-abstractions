package com.lightningkite.services.pubsub.mqtt

import com.lightningkite.services.SettingContext
import com.lightningkite.services.pubsub.PubSubChannel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * In-memory MQTT PubSub for testing.
 * Simulates MQTT topic wildcards but runs entirely in-process.
 */
public class LocalMqttPubSub(
    override val name: String,
    override val context: SettingContext
) : MqttPubSub {
    private val masterFlow = MutableSharedFlow<Pair<String, String>>(replay = 0)
    private val json = Json { serializersModule = context.internalSerializersModule }

    private val mutex = Mutex()

    // Store retained messages: topic -> json string
    private val retainedMessages = mutableMapOf<String, String>()

    override fun <T> get(
        topic: String,
        serializer: KSerializer<T>,
        qos: MqttPubSub.QoS,
        retained: Boolean
    ): MqttChannel<T> {
        return object : MqttChannel<T> {
            override suspend fun emit(value: T) = emit(value, retained)

            override suspend fun emit(value: T, retain: Boolean) {
                val jsonStr = json.encodeToString(serializer, value)

                // Store retained message
                if (retain) {
                    if (jsonStr.isEmpty() || jsonStr == "\"\"") {
                        // Empty message clears retained
                        retainedMessages.remove(topic)
                    } else {
                        retainedMessages[topic] = jsonStr
                    }
                }

                // Publish to subscribers
                masterFlow.emit(topic to jsonStr)
            }

            override suspend fun collect(collector: FlowCollector<T>) {
                // First, emit any matching retained messages
                mutex.withLock {
                    retainedMessages.forEach { (retainedTopic, jsonStr) ->
                        if (topicMatches(topic, retainedTopic)) {
                            // Don't use collector.emit here because we're in synchronized block
                            // We'll collect it via the flow instead
                        }
                    }
                }

                // Emit retained messages immediately for this subscription
                val matchingRetained = mutex.withLock {
                    retainedMessages.filterKeys { retainedTopic ->
                        topicMatches(topic, retainedTopic)
                    }
                }

                // Emit retained messages
                matchingRetained.forEach { (_, jsonStr) ->
                    val value = json.decodeFromString(serializer, jsonStr)
                    collector.emit(value)
                }

                // Then collect new messages
                masterFlow
                    .filter { (publishedTopic, _) -> topicMatches(topic, publishedTopic) }
                    .map { (_, jsonStr) -> json.decodeFromString(serializer, jsonStr) }
                    .collect(collector)
            }
        }
    }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        return get(key, serializer, MqttPubSub.QoS.AtLeastOnce, false)
    }

    override fun string(key: String): PubSubChannel<String> {
        return get(key, String.serializer())
    }

    // MQTT topic matching: + matches one level, # matches remaining levels
    private fun topicMatches(pattern: String, topic: String): Boolean {
        val patternParts = pattern.split("/")
        val topicParts = topic.split("/")

        var pi = 0
        var ti = 0

        while (pi < patternParts.size && ti < topicParts.size) {
            when (patternParts[pi]) {
                "#" -> return true  // # matches everything remaining
                "+" -> { pi++; ti++ }  // + matches exactly one level
                topicParts[ti] -> { pi++; ti++ }
                else -> return false
            }
        }

        return pi == patternParts.size && ti == topicParts.size
    }
}
