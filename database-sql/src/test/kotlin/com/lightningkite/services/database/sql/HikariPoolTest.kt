package com.lightningkite.services.database.sql

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.LargeTestModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HikariPoolUrlParseTest {

    @Test
    fun extractsPoolParamsAndKeepsJdbcParams() {
        // maxPoolSize/connectionTimeout are pool params; the rest must stay on the JDBC URL.
        val split = splitPoolQuery("localhost:5432/mydb?maxPoolSize=7&connectionTimeout=1234&sslmode=require&ApplicationName=app")
        assertEquals("localhost:5432/mydb", split.base)
        assertEquals(7, split.pool.maxPoolSize)
        assertEquals(1234L, split.pool.connectionTimeout)
        // JDBC params preserved, in order, pool params removed.
        assertEquals("sslmode=require&ApplicationName=app", split.jdbcQuery)
    }

    @Test
    fun allPoolParamsParsed() {
        val split = splitPoolQuery(
            "h:1/db?maxPoolSize=10&minIdle=2&connectionTimeout=30000&idleTimeout=60000&maxLifetime=1800000&validationTimeout=5000&poolName=mypool"
        )
        with(split.pool) {
            assertEquals(10, maxPoolSize)
            assertEquals(2, minIdle)
            assertEquals(30000L, connectionTimeout)
            assertEquals(60000L, idleTimeout)
            assertEquals(1800000L, maxLifetime)
            assertEquals(5000L, validationTimeout)
            assertEquals("mypool", poolName)
        }
        assertNull(split.jdbcQuery)
    }

    @Test
    fun noQueryMeansDefaults() {
        val split = splitPoolQuery("localhost:5432/mydb")
        assertEquals("localhost:5432/mydb", split.base)
        assertNull(split.jdbcQuery)
        assertNull(split.pool.maxPoolSize)
    }
}

class HikariPoolBehaviorTest {

    /** Builds a pooled H2 database against a shared, named in-memory DB so all pool connections see the same schema. */
    private fun pooled(name: String, maxPoolSize: Int): PooledDatabase = makePooledDatabase(
        jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
        pool = PoolParams(maxPoolSize = maxPoolSize, connectionTimeout = 30_000),
        bypassPool = false,
    )

    @Test
    fun concurrentTransactionsSerializeWithinPoolSize() = runBlocking {
        val maxPoolSize = 2
        val pooled = pooled("poolSerialize", maxPoolSize)
        try {
            val concurrent = AtomicInteger(0)
            val peak = AtomicInteger(0)

            // Launch more transactions than the pool can serve at once. Each holds its connection
            // briefly; the pool must serialize them rather than exhaust/fail.
            val jobs = (1..6).map {
                async(Dispatchers.IO) {
                    newSuspendedTransaction(Dispatchers.IO, db = pooled.database) {
                        exec("SELECT 1")
                        val now = concurrent.incrementAndGet()
                        peak.updateAndGet { maxOf(it, now) }
                        delay(50)
                        concurrent.decrementAndGet()
                    }
                }
            }
            jobs.awaitAll()

            assertTrue(
                peak.get() <= maxPoolSize,
                "At most $maxPoolSize transactions should run concurrently, but peaked at ${peak.get()}",
            )
            assertEquals(0, concurrent.get(), "All transactions should have completed")
        } finally {
            pooled.dataSource?.close()
        }
    }

    @Test
    fun disconnectClosesPool() = runBlocking {
        val db = SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            pooled("disconnectClose", maxPoolSize = 3)
        }
        // Force the lazy pool to materialize and run a real operation.
        db.collection<LargeTestModel>("x").insertOne(LargeTestModel())
        val dataSource = db.materializePool().dataSource
        assertTrue(dataSource != null && !dataSource.isClosed, "Pool should be open after use")

        db.disconnect()
        assertTrue(dataSource.isClosed, "disconnect() must close the Hikari pool")
    }

    @Test
    fun memDatabaseBypassesPoolByDefault() {
        // Registered sql-h2 mem URL: pooling must be bypassed (single connection).
        SqlDatabase.Companion // force class-load so the URL scheme handlers register
        val pooled = (com.lightningkite.services.database.Database.Settings("sql-h2://mem:bypassTest")
            .invoke("test", TestSettingContext(EmptySerializersModule())) as SqlDatabase)
            .materializePool()
        try {
            assertEquals(1, pooled.dataSource?.maximumPoolSize, "mem: H2 must run single-connection")
        } finally {
            pooled.dataSource?.close()
        }
    }

    @Test
    fun sqliteBypassesPoolByDefault() {
        SqlDatabase.Companion // force class-load so the URL scheme handlers register
        val pooled = (com.lightningkite.services.database.Database.Settings("sql-sqlite://:memory:")
            .invoke("test", TestSettingContext(EmptySerializersModule())) as SqlDatabase)
            .materializePool()
        try {
            assertEquals(1, pooled.dataSource?.maximumPoolSize, "SQLite must run single-connection")
        } finally {
            pooled.dataSource?.close()
        }
    }
}
