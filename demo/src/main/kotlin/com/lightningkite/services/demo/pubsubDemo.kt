package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.get
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates the pubsub subsystem via `local://`: a subscriber collects messages from a
 * channel while a publisher emits into it, all within a single process.
 */
fun main() = runBlocking {
    val context = TestSettingContext()
    val pubsub: PubSub = PubSub.Settings("local")("messaging", context)
    val channel = pubsub.get<String>("notifications")

    val subscriber = launch {
        channel.collect { message -> println("Received: $message") }
    }

    delay(100) // let the subscriber start collecting before we publish
    channel.emit("Hello, subscribers!")
    channel.emit("A second message")
    delay(100) // give the subscriber a moment to print both messages

    subscriber.cancel()
}
