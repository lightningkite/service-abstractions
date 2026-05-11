package com.lightningkite.services.database.sql

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.sql.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ===== Shared test suites =====

class SqlConditionTests : ConditionTests() {
    override val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            Database.connect("jdbc:h2:mem:conditionTests;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        }
    }

    // Unsupported in generic SQL
    override fun test_geodistance_1() {}
    override fun test_geodistance_2() {}
}

class SqlModificationTests : ModificationTests() {
    override val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            Database.connect("jdbc:h2:mem:modificationTests;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        }
    }

    // Map modifications not yet implemented in SQL expressions
    override fun test_Map_modifyField() {}
    override fun test_Map_setField() {}
    override fun test_Map_unsetField() {}
}

class SqlSortTest : SortTest() {
    override val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            Database.connect("jdbc:h2:mem:sortTests;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        }
    }
}

class SqlAggregationsTest : AggregationsTest() {
    override val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            Database.connect("jdbc:h2:mem:aggregationTests;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        }
    }
}

class SqlOperationsTests : OperationsTests() {
    override val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            Database.connect("jdbc:h2:mem:operationsTests;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        }
    }
}

// ===== Basic smoke test =====

class SqlBasicTest {
    private val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            Database.connect("jdbc:h2:mem:basicTest;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        }
    }

    @Test
    fun insertAndFind() = runTest {
        val collection = database.collection<LargeTestModel>("insertAndFind")
        val model = LargeTestModel()
        collection.insertOne(model)
        val found = collection.find(Condition.Always).firstOrNull()
        assertNotNull(found)
        assertEquals(model._id, found._id)
    }

    @Test
    fun insertAndFindByCondition() = runTest {
        val collection = database.collection<LargeTestModel>("insertAndFindByCondition")
        val a = LargeTestModel(int = 10)
        val b = LargeTestModel(int = 20)
        collection.insertMany(listOf(a, b))

        val results = collection.find(condition { it.int eq 10 }).toList()
        assertEquals(1, results.size)
        assertEquals(a._id, results[0]._id)
    }

    @Test
    fun updateOne() = runTest {
        val collection = database.collection<LargeTestModel>("updateOne")
        val model = LargeTestModel(int = 5)
        collection.insertOne(model)

        val change = collection.updateOne(
            Condition.Always,
            modification { it.int assign 42 }
        )
        assertEquals(5, change.old?.int)
        assertEquals(42, change.new?.int)

        val found = collection.find(Condition.Always).firstOrNull()
        assertEquals(42, found?.int)
    }

    @Test
    fun deleteOne() = runTest {
        val collection = database.collection<LargeTestModel>("deleteOne")
        val model = LargeTestModel()
        collection.insertOne(model)
        assertEquals(1, collection.count(Condition.Always))

        val deleted = collection.deleteOne(Condition.Always)
        assertNotNull(deleted)
        assertEquals(model._id, deleted._id)
        assertEquals(0, collection.count(Condition.Always))
    }

    @Test
    fun formatRoundTrip() = runTest {
        // Test MapFormat encode/decode without database
        val format = SqlMapFormat(kotlinx.serialization.modules.EmptySerializersModule())
        val model = LargeTestModel(listEmbedded = listOf(
            ClassUsedForEmbedding("Alice", 1),
            ClassUsedForEmbedding("Bob", 2),
        ))
        val result = format.encode(LargeTestModel.serializer(), model)
        println("Main record keys: ${result.mainRecord.keys}")
        println("Children keys: ${result.children.keys}")
        result.children.forEach { (k, v) -> println("  $k: ${v.size} rows") }

        val decoded = format.decode(LargeTestModel.serializer(), result.mainRecord, result.children)
        assertEquals(2, decoded.listEmbedded.size)
        assertEquals("Alice", decoded.listEmbedded[0].value1)
    }

    @Test
    fun listFieldRoundTrip() = runTest {
        val collection = database.collection<LargeTestModel>("listFieldRoundTrip")
        val model = LargeTestModel(listEmbedded = listOf(
            ClassUsedForEmbedding("Alice", 1),
            ClassUsedForEmbedding("Bob", 2),
        ))
        collection.insertOne(model)

        val found = collection.find(Condition.Always).firstOrNull()
        assertNotNull(found)
        assertEquals(2, found.listEmbedded.size)
        assertEquals("Alice", found.listEmbedded[0].value1)
        assertEquals("Bob", found.listEmbedded[1].value1)
    }
}
