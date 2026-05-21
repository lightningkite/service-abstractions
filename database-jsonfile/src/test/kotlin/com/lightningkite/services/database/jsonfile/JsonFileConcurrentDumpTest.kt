package com.lightningkite.services.database.jsonfile

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.test.User
import com.lightningkite.services.kfile.KFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Proves [JsonFileTable.handleCollectionDump] is safe under concurrent invocation.
 *
 * Before the lock was added, the shutdown hook and `close()` could enter
 * `handleCollectionDump` simultaneously - one would `atomicMove` the `.saving`
 * temp file over the destination while the other was mid-write, corrupting
 * either the temp or destination file.
 *
 * Reuses the [User] model from `database-test` because it already has the
 * kotlinx-serialization compiler plugin applied, which is required by
 * `InMemoryTable` to discover the `_id` field for uniqueness checks.
 */
@OptIn(ExperimentalUuidApi::class)
class JsonFileConcurrentDumpTest {

    @Test
    fun concurrentDumpsProduceValidFile() {
        val folder = KFile("build/testrun-concurrent-dump").also {
            it.deleteRecursively()
            it.createDirectories()
        }
        val database = JsonFileDatabase("test", folder, TestSettingContext())

        // JsonFileDatabase always constructs a JsonFileTable; cast so we can call
        // the dump method directly.
        @Suppress("UNCHECKED_CAST")
        val table = database.table(serializer<User>(), "User") as JsonFileTable<User>

        val seed = List(50) { i ->
            User(
                email = "user$i@example.com",
                phoneNumber = "555-000-${i.toString().padStart(4, '0')}",
                age = i.toLong(),
            )
        }
        runBlocking { table.insert(seed) }

        val threadCount = 32
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val start = CountDownLatch(1)
            val done = CountDownLatch(threadCount)
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            repeat(threadCount) {
                pool.submit {
                    try {
                        start.await()
                        table.handleCollectionDump()
                    } catch (t: Throwable) {
                        failures.add(t)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "Threads did not finish in time")
            assertTrue(failures.isEmpty(), "handleCollectionDump threw: $failures")
        } finally {
            pool.shutdownNow()
        }

        val destination = folder.then("User.json")
        assertTrue(destination.exists(), "Destination JSON file should exist")

        // No stray .saving file should remain - atomicMove always renames it away.
        val saving = folder.then("User.json.saving")
        assertTrue(!saving.exists(), "Temp .saving file should not remain after dumps")

        // File parses as valid JSON and contains exactly the inserted rows.
        val parsed = Json.decodeFromString(ListSerializer(serializer<User>()), destination.readString())
        assertEquals(seed.toSet(), parsed.toSet(), "Persisted contents must match inserted data")
    }
}
