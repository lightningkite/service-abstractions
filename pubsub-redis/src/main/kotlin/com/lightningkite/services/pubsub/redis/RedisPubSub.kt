package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.SettingContext
import com.lightningkite.services.pubsub.MetricTrackingPubSub
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import io.lettuce.core.RedisClient
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.collect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import redis.embedded.RedisServer
import java.util.concurrent.ConcurrentHashMap

/**
 * A Redis implementation of the PubSub interface.
 */
public class RedisPubSub(
    override val name: String,
    override val context: SettingContext,
    private val client: RedisClient
) : MetricTrackingPubSub(context) {
    private val observables = ConcurrentHashMap<String, Flux<String>>()
    private val subscribeConnection = client.connectPubSub().reactive()
    private val publishConnection = client.connectPubSub().reactive()
    private val json = Json { serializersModule = context.internalSerializersModule }

    public companion object {
        init {
            PubSub.Settings.register("redis-test") { name, url, context ->
                val redisServer = RedisServer.builder()
                    .port(6379)
                    .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
                    .slaveOf("localhost", 6378)
                    .setting("daemonize no")
                    .setting("appendonly no")
                    .setting("maxmemory 128M")
                    .build()
                redisServer.start()
                RedisPubSub(name, context, RedisClient.create("redis://127.0.0.1:6378"))
            }
            PubSub.Settings.register("redis") { name, url, context ->
                RedisPubSub(name, context, RedisClient.create(url))
            }
        }
    }

    private fun key(key: String): Flux<String> = observables.getOrPut(key) {
        val reactive = subscribeConnection
        Flux.usingWhen(
            reactive.subscribe(key).then(Mono.just(reactive)),
            {
                it.observeChannels()
                    .filter { it.channel == key }
            },
            { it.unsubscribe(key) }
        ).map { it.message }
            .doOnError { it.printStackTrace() }
            .share()
    }

    override fun <T> getInternal(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        return object : PubSubChannel<T> {
            override suspend fun collect(collector: FlowCollector<T>) {
                key(key).map { json.decodeFromString(serializer, it) }.collect { collector.emit(it) }
            }

            override suspend fun emit(value: T) {
                publishConnection.publish(key, json.encodeToString(serializer, value)).awaitFirst()
            }
        }
    }

    override fun stringInternal(key: String): PubSubChannel<String> {
        return object : PubSubChannel<String> {
            override suspend fun collect(collector: FlowCollector<String>) {
                key(key).asFlow().collect { collector.emit(it) }
            }

            override suspend fun emit(value: String) {
                publishConnection.publish(key, value).awaitFirst()
            }
        }
    }

}