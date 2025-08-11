package com.lightningkite.services.pubsub

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A service for publishing and subscribing to messages.
 */
public interface PubSub : Service {
    /**
     * Settings for configuring a pub-sub service.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "local"
    ) : Setting<PubSub> {
        public companion object : UrlSettingParser<PubSub>() {
            init {
                register("local") { name, _, context -> LocalPubSub(name, context) }
                register("debug") { name, _, context -> DebugPubSub(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): PubSub {
            return parse(name, url, context)
        }
    }

    /**
     * Gets a channel for a specific key with a serializer.
     */
    public fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T>

    /**
     * Gets a string channel for a specific key.
     */
    public fun string(key: String): PubSubChannel<String>

    /**
     * The frequency at which health checks should be performed.
     */
    public override val healthCheckFrequency: Duration
        get() = 1.minutes

    /**
     * Checks the health of the pub-sub service by publishing a test message.
     */
    public override suspend fun healthCheck(): HealthStatus {
        return try {
            get<Boolean>("health-check-test-key").emit(true)
            HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}

/**
 * Gets a channel for a specific key using the default serializer.
 */
public inline operator fun <reified T : Any> PubSub.get(key: String): PubSubChannel<T> {
    return get(key, context.internalSerializersModule.serializer<T>())
}

/**
 * A channel for publishing and subscribing to messages.
 */
public interface PubSubChannel<T> : Flow<T>, FlowCollector<T>