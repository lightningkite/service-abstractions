package com.lightningkite.services.database.jsonfile

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.database.DatabaseTableDefinition
import com.lightningkite.services.database.test.LargeTestModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Regression test for [Service.disconnect][com.lightningkite.services.Service.disconnect] on
 * [JsonFileDatabase]. Before the fix, `disconnect()` was the inherited no-op: the documented
 * shutdown/serverless-freeze path never flushed a table to disk, and only a JVM shutdown hook
 * (which doesn't run on a freeze, and which this test deliberately avoids relying on) saved data.
 */
class JsonFileDisconnectTest {
    private val root = KFile("build/testrun-disconnect").also { it.deleteRecursively() }

    @Test
    fun disconnectPersistsWithoutProcessExit() = runTest {
        val dir = root.then("1")
        val db = JsonFileDatabase("test", dir, TestSettingContext())
        val table = db.prepare(DatabaseTableDefinition<LargeTestModel>("disconnectTest"))
        table.insert(listOf(LargeTestModel(string = "persisted")))

        val storage = dir.then("disconnecttest.json")
        assertFalse(storage.exists(), "Nothing should be on disk before disconnect() flushes it")

        db.disconnect()

        assertTrue(storage.exists(), "disconnect() must flush the table to disk")
        assertTrue(
            storage.readStringOrNull()?.contains("persisted") == true,
            "The flushed file must contain the inserted data"
        )
    }

    @Test
    fun disconnectIsIdempotent() = runTest {
        val dir = root.then("2")
        val db = JsonFileDatabase("test", dir, TestSettingContext())
        val table = db.prepare(DatabaseTableDefinition<LargeTestModel>("disconnectTest"))
        table.insert(listOf(LargeTestModel(string = "first")))

        db.disconnect()
        // A second disconnect() with no open tables (the first cleared the collection cache)
        // must not throw, matching Service.disconnect()'s documented idempotency contract.
        db.disconnect()
    }
}
