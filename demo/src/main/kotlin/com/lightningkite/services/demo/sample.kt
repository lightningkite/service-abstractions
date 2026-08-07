package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

/**
 * The README's sample: demonstrates the cache subsystem (get/set/add/remove and TTL expiry)
 * against an in-memory `ram://` cache, configured from a settings JSON string.
 */
fun main() {
    val settingsFile = """
        {
            "port": 8941,
            "host": "127.0.0.1",
            "cache": "ram"
        }
    """.trimIndent()
    val context = TestSettingContext()
    val settings = Json.decodeFromString<MyServerSettings>(settingsFile)

    runBlocking {
        val cache = settings.cache("cache", context)

        repeat(5) {
            val currentValue = cache.get<Int>("counter")
            println("Counter: $currentValue")
            cache.set("counter", (currentValue ?: 0) + 1)
        }

        // add() only writes if the key doesn't already exist yet.
        val firstAdd = cache.setIfNotExists("greeting", "hello")
        val secondAdd = cache.setIfNotExists("greeting", "ignored, already set")
        println("First add succeeded: $firstAdd, second add succeeded: $secondAdd, value: ${cache.get<String>("greeting")}")

        // Values can expire on their own after a TTL.
        cache.set("temporary", "still here", timeToLive = 200.milliseconds)
        println("Right after set: ${cache.get<String>("temporary")}")
        delay(300.milliseconds)
        println("After the TTL expires: ${cache.get<String>("temporary")}")

        cache.remove("counter")
        println("After remove: ${cache.get<Int>("counter")}")
    }
}

@Serializable
data class MyServerSettings(
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    val cache: Cache.Settings = Cache.Settings(),
)
