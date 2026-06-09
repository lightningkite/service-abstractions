package com.lightningkite.services.database.postgres

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.sql.Database
import org.junit.ClassRule
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Regression test pinning down "no stray stdout from the Postgres query path".
 *
 * Historically the Postgres collection and condition-mapping code leaked debug output to
 * stdout ("Fields: ...", "Key: ...", "Formatted ...", Exposed's StdOutSqlLogger SQL dumps,
 * "list is ..."). These leaked column names, query structure, and row contents - a real
 * security concern. This test exercises the most println-prone paths (insert/find/update)
 * and asserts the captured buffer contains none of the historical leak markers, so any
 * future regression fails fast.
 */
class PostgresLogLeakTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = EmbeddedPostgresRules.singleInstance()
    }

    private val database: com.lightningkite.services.database.Database by lazy {
        PostgresDatabase(
            "test",
            TestSettingContext(EmptySerializersModule())
        ) { PooledDatabase(Database.connect(postgres.embeddedPostgres.postgresDatabase), null) }
    }

    private lateinit var originalOut: PrintStream
    private lateinit var captured: ByteArrayOutputStream

    @BeforeTest
    fun captureStdout() {
        originalOut = System.out
        captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    }

    @AfterTest
    fun restoreStdout() {
        System.setOut(originalOut)
    }

    @Test
    fun queryPathDoesNotLeakToStdout() = runTest {
        val collection = database.table<LargeTestModel>("PostgresLogLeakTest")
        val model = LargeTestModel(int = 42, string = "leak-canary")
        collection.insertOne(model)

        // Exercise Equal condition path (the format(value) leak site).
        collection.find(condition<LargeTestModel> { it.int eq 42 }).toList()

        // Exercise modification path (uses formatSingleExpression / format).
        collection.updateOne(
            condition<LargeTestModel> { it.int eq 42 },
            modification<LargeTestModel> { it.int assign 43 }
        )

        // Flush anything buffered before we restore stdout.
        System.out.flush()
        val output = captured.toString(Charsets.UTF_8)

        val markers = listOf("Fields:", "Key:", "Formatted ", "SQL:", "list is ")
        for (marker in markers) {
            assertFalse(
                output.contains(marker),
                "Stdout leak: query path emitted '$marker'. Captured output:\n$output"
            )
        }
    }
}
