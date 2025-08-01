package com.lightningkite.services.demo

import com.lightningkite.services.*
import com.lightningkite.services.cache.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
        val cache = settings.cache(context)
        repeat(5) {
            val currentValue = cache.get<Int>("counter")
            println("Counter: $currentValue")
            cache.set("counter", (currentValue ?: 0) + 1)
        }
    }
}

@Serializable
data class MyServerSettings(
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    val cache: Cache.Settings = Cache.Settings(),
)