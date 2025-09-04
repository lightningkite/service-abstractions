package com.lightningkite.services.database.postgres

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.postgres.PostgresRetrievalTest.StarWarsFilms.sequelId
import com.lightningkite.services.database.test.*
import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transactionManager
import org.junit.Assert.*
import org.junit.ClassRule
import org.junit.Rule
import org.postgresql.util.PGobject
import java.sql.Connection.TRANSACTION_READ_COMMITTED
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock.System.now
import kotlin.time.Instant
import kotlin.uuid.Uuid

class BasicTest() {
    @Rule
    @JvmField
    val pg = EmbeddedPostgresRules.singleInstance()

    @Test fun schema2() {
        val db = Database.connect(pg.embeddedPostgres.postgresDatabase)
        val collection = PostgresCollection(db, "LargeTestModel", LargeTestModel.serializer(), ClientModule)
        runBlocking {
            // Quick test
            val t = LargeTestModel()
            collection.insertOne(t)
            assertEquals(t, collection.find(Condition.Always).firstOrNull())
            assertEquals(t, collection.find(condition { it.byte eq 0 }).firstOrNull())
            assertEquals(t.byte, collection.updateOne(Condition.Always, modification { it.byte += 1 }).old?.byte)
            assertEquals(
                t.byte.plus(1).toByte(),
                collection.updateOne(Condition.Always, modification { it.byte += 1 }).old?.byte
            )
            assertEquals(
                t.byte.plus(2).toByte(),
                collection.updateOne(Condition.Always, modification { it.byte += 1 }).old?.byte
            )
            assertEquals(
                t.byte.plus(3).toByte(),
                collection.updateOne(Condition.Always, modification { it.byte += 1 }).old?.byte
            )
        }
    }
}

class CodingTest() {
    @Serializable
    data class TestModel(
        val uuid: Uuid = Uuid.random(),
        val time: Instant,
        val x: String?,
        val y: Int,
        val z: ClassUsedForEmbedding?,
        val array: List<Int>,
        val embArray: List<ClassUsedForEmbedding>,
        val nested: List<List<Int>>,
        val map: Map<String, Int>,
    )

    @Test
    fun quick() {
        val out = LinkedHashMap<String, Any?>()
        val format = DbMapLikeFormat(EmptySerializersModule())
        format.encode(
            TestModel.serializer(),
            TestModel(
                time = now(),
                x = "test",
                y = 1,
                z = ClassUsedForEmbedding("def", 2),
                array = listOf(1, 2, 3),
                embArray = listOf(
                    ClassUsedForEmbedding("a", 3),
                    ClassUsedForEmbedding("b", 4),
                ),
                nested = listOf(listOf(1, 2), listOf(3, 4)),
                map = mapOf("one" to 1, "two" to 2)
            ),
            out
        )
        println(out)
        println(out.mapValues { it.value?.let { it::class.simpleName } ?: "NULL" })
        println(format.decode(TestModel.serializer(), out))
    }

    @Test
    fun large() {
        val out = LinkedHashMap<String, Any?>()
        val format = DbMapLikeFormat(EmptySerializersModule())
        format.encode(
            LargeTestModel.serializer(),
            LargeTestModel(instant = Instant.fromEpochMilliseconds(123456L)),
            out
        )
        println(out)
        println(out.mapValues { it.value?.let { it::class.qualifiedName } ?: "NULL" })
        println(format.decode(LargeTestModel.serializer(), out))
    }
}


class PostgresRetrievalTest {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    val database: com.lightningkite.services.database.Database by lazy {
        PostgresDatabase("test", TestSettingContext(ClientModule)){Database.connect(postgres.embeddedPostgres.postgresDatabase)}
    }
    @kotlin.test.Test fun inout() = runTest {
        val collection = database.collection<LargeTestModel>("PostgresRetrievalTest")
        val lower = LargeTestModel(instant = Instant.fromEpochMilliseconds(5000L))
        val higher = LargeTestModel(instant = Instant.fromEpochMilliseconds(15000L))
        val middle = LargeTestModel(instant = Instant.fromEpochMilliseconds(10000L))
        collection.insertMany(listOf(lower, higher, middle))
        val result = collection.find(Condition.Always, orderBy = sort { it.instant.ascending() }).toList()
        println(result)
        assertEquals(result.size, 3)
        assertEquals(result[0], lower)
        assertEquals(result[1], middle)
        assertEquals(result[2], higher)
        Unit
    }
    @Test fun test_Instant_nullable_eq() = runTest {
        val collection = database.collection<LargeTestModel>("quicktest")
        val lower = LargeTestModel(instant = Instant.fromEpochMilliseconds(0L))
        val higher = LargeTestModel(instant = Instant.fromEpochMilliseconds(15000L))
        val manualList = listOf(lower, higher)
        collection.insertOne(lower)
        collection.insertOne(higher)
        val condition = path<LargeTestModel>().instant eq higher.instant
        val all = collection.find(Condition.Always).toList()
        println("All: $all")
        val results = collection.find(condition).toList()
        assertContains(results, higher)
        assertTrue(lower !in results)
        assertEquals(manualList.filter { condition(it) }.sortedBy { it._id }, results.sortedBy { it._id })
        Unit
    }

    object StarWarsFilms : IntIdTable() {
        val sequelId = integer("sequel_id").uniqueIndex()
        val name = varchar("name", 50)
        val director = varchar("director", 50)
        val timestamp = timestamp("timestamp")
    }
    @Test fun directBullshit() = runBlocking {
        val db = (database as PostgresDatabase).db
        suspend fun <T> t(action: suspend Transaction.() -> T): T =
            newSuspendedTransaction(Dispatchers.IO, db = db, transactionIsolation = TRANSACTION_READ_COMMITTED, statement = {
                addLogger(StdOutSqlLogger)
                action()
            })
        t { SchemaUtils.create(StarWarsFilms) }
        val instant = java.time.Instant.ofEpochMilli(123456L)
        t {
            StarWarsFilms.insert {
                it[name] = "The Last Jedi"
                it[sequelId] = 8
                it[director] = "Rian Johnson"
                it[timestamp] = instant
            }
        }
        t {
            StarWarsFilms.selectAll()
                .where { StarWarsFilms.timestamp eq instant }
                .toList()
                .also { assertEquals(1, it.size) }
                .forEach { println("RESULT: $it") }
            println("kdone")
        }
    }
}


class PostgresAggregationsTest : AggregationsTest() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.services.database.Database by lazy {
        PostgresDatabase("test", TestSettingContext(ClientModule)){Database.connect(postgres.embeddedPostgres.postgresDatabase)}
    }
}

class PostgresConditionTests : ConditionTests() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.services.database.Database by lazy {
        PostgresDatabase("test", TestSettingContext(ClientModule)){Database.connect(postgres.embeddedPostgres.postgresDatabase)}
    }

    override fun test_geodistance_1() {
        println("Suppressed until this is supported")
    }

    override fun test_geodistance_2() {
        println("Suppressed until this is supported")
    }
}

class PostgresModificationTests : ModificationTests() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.services.database.Database by lazy {
        PostgresDatabase("test", TestSettingContext(ClientModule)) { Database.connect(postgres.embeddedPostgres.postgresDatabase) }
    }
    override fun test_Map_modifyField() {
        // TODO: Make it work
    }

    override fun test_Map_setField() {
        // TODO: Make it work
    }

    override fun test_Map_unsetField() {
        // TODO: Make it work
    }
}

class PostgresSortTest : SortTest() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.services.database.Database by lazy {
        PostgresDatabase("test", TestSettingContext(ClientModule)){Database.connect(postgres.embeddedPostgres.postgresDatabase)}
    }
}
