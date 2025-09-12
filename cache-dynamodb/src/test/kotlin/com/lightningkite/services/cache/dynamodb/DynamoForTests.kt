package com.lightningkite.services.cache.dynamodb

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.test.CacheTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DynamoTest : CacheTest() {
    init { DynamoDbCache }
    override val cache: DynamoDbCache
        get() = Cache.Settings("dynamodb-local").invoke("test", TestSettingContext()) as DynamoDbCache

    override fun runSuspendingTest(body: suspend CoroutineScope.() -> Unit) = runBlocking { body() }

    @Test fun parsing() {
        val target = setOf("asdf", "fdsa")
        val serializer = SetSerializer(String.serializer())
        assertEquals(target, serializer.fromDynamo(serializer.toDynamo(target, cache.context), cache.context))
    }

    override val waitScale: Duration
        get() = 2.seconds
}
