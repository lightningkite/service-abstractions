package com.lightningkite.services.pubsub.mqtt

import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline

/**
 * MQTT-specific PubSub with topic wildcards and QoS support.
 *
 * Extends the base PubSub interface with MQTT-specific features:
 * - Topic wildcards (+ single level, # multi-level)
 * - Quality of Service levels
 * - Retained messages
 * - Last Will and Testament
 *
 * ## URL Schemes
 *
 * - `mqtt://host:1883` - Unencrypted MQTT
 * - `mqtt://user:pass@host:1883` - With credentials
 * - `mqtts://host:8883` - TLS encrypted
 * - `aws-iot://endpoint.iot.region.amazonaws.com` - AWS IoT Core
 *
 * ## Topic Wildcards
 *
 * ```kotlin
 * // Subscribe to all sensors
 * mqtt.get<SensorReading>("sensors/+/temperature").collect { ... }
 *
 * // Subscribe to everything under a device
 * mqtt.get<DeviceEvent>("devices/device-123/#").collect { ... }
 * ```
 */
public interface MqttPubSub : PubSub {

    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "mqtt://localhost:1883"
    ) : Setting<MqttPubSub> {
        public companion object : UrlSettingParser<MqttPubSub>() {
            init {
                register("local") { name, _, context -> LocalMqttPubSub(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): MqttPubSub {
            return parse(name, url, context)
        }
    }

    /**
     * Get a channel with MQTT-specific options.
     */
    public fun <T> get(
        topic: String,
        serializer: KSerializer<T>,
        qos: QoS = QoS.AtLeastOnce,
        retained: Boolean = false
    ): MqttChannel<T>

    /**
     * MQTT Quality of Service levels.
     */
    public enum class QoS {
        /** Fire and forget - no delivery guarantee */
        AtMostOnce,
        /** Guaranteed delivery at least once (may duplicate) */
        AtLeastOnce,
        /** Guaranteed exactly once delivery */
        ExactlyOnce
    }
}

/**
 * MQTT channel with publish options.
 */
public interface MqttChannel<T> : PubSubChannel<T> {
    /** Publish with explicit retain flag */
    public suspend fun emit(value: T, retain: Boolean)
}

/** Extension for reified type */
public inline fun <reified T : Any> MqttPubSub.get(
    topic: String,
    qos: MqttPubSub.QoS = MqttPubSub.QoS.AtLeastOnce,
    retained: Boolean = false
): MqttChannel<T> = get(topic, context.internalSerializersModule.serializer(), qos, retained)
