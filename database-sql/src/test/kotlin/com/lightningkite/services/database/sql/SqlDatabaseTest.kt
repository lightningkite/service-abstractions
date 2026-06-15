package com.lightningkite.services.database.sql

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.sql.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ===== Models for compound key test (no KSP path generation needed) =====

@Serializable
data class CompoundId(val a: String = "", val b: Int = 0) : Comparable<CompoundId> {
    override fun compareTo(other: CompoundId): Int =
        compareValuesBy(this, other, { it.a }, { it.b })
}

@Serializable
data class CompoundKeyModel(
    override val _id: CompoundId = CompoundId(),
    val name: String = "",
    val tags: List<String> = emptyList(),
) : HasId<CompoundId> {
    companion object
}

// ===== Shared test suites =====

class SqlConditionTests : ConditionTests() {
    override val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect("jdbc:h2:mem:conditionTests;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
        }
    }

    // Unsupported in generic SQL
    override fun test_geodistance_1() {}
    override fun test_geodistance_2() {}
}

class SqlModificationTests : ModificationTests() {
    override val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect("jdbc:h2:mem:modificationTests;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
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
            PooledDatabase(Database.connect("jdbc:h2:mem:sortTests;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
        }
    }
}

class SqlAggregationsTest : AggregationsTest() {
    override val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect("jdbc:h2:mem:aggregationTests;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
        }
    }
}

class SqlOperationsTests : OperationsTests() {
    override val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect("jdbc:h2:mem:operationsTests;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
        }
    }
}

// ===== Basic smoke test =====

class SqlBasicTest {
    private val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect("jdbc:h2:mem:basicTest;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
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
    fun setChildTableHasNoIdxColumn() {
        // Sets are unordered, so their child table must not carry an `idx` column,
        // while ordered collections (lists, maps) must.
        val schema = SqlSchema("SchemaCheck", EmptySerializersModule(), LargeTestModel.serializer().descriptor)

        val setDef = schema.childTables["set"]!!
        assertEquals(false, setDef.ordered)
        assertNull(setDef.table.idxColumn)
        assertTrue(setDef.table.columns.none { it.name == "idx" }, "set child table should have no idx column")

        val setEmbeddedDef = schema.childTables["setEmbedded"]!!
        assertEquals(false, setEmbeddedDef.ordered)
        assertNull(setEmbeddedDef.table.idxColumn)
        assertTrue(setEmbeddedDef.table.columns.none { it.name == "idx" })

        val listDef = schema.childTables["list"]!!
        assertEquals(true, listDef.ordered)
        assertNotNull(listDef.table.idxColumn)
        assertTrue(listDef.table.columns.any { it.name == "idx" }, "list child table should keep its idx column")

        val mapDef = schema.childTables["map"]!!
        assertEquals(true, mapDef.ordered)
        assertNotNull(mapDef.table.idxColumn)
    }

    @Test
    fun setFieldRoundTrip() = runTest {
        val collection = database.collection<LargeTestModel>("setFieldRoundTrip")
        val model = LargeTestModel(
            set = setOf(3, 1, 2),
            setEmbedded = setOf(
                ClassUsedForEmbedding("Alice", 1),
                ClassUsedForEmbedding("Bob", 2),
            ),
        )
        collection.insertOne(model)

        val found = collection.find(Condition.Always).firstOrNull()
        assertNotNull(found)
        assertEquals(setOf(1, 2, 3), found.set)
        assertEquals(2, found.setEmbedded.size)
        assertTrue(found.setEmbedded.any { it.value1 == "Alice" })
        assertTrue(found.setEmbedded.any { it.value1 == "Bob" })
    }

    @Test
    fun setMembershipQuery() = runTest {
        // Membership queries against a set child table must still work without idx.
        val collection = database.collection<LargeTestModel>("setMembershipQuery")
        val match = LargeTestModel(set = setOf(10, 20))
        val noMatch = LargeTestModel(set = setOf(30, 40))
        collection.insertMany(listOf(match, noMatch))

        val results = collection.find(condition { it.set.any { e -> e eq 20 } }).toList()
        assertEquals(1, results.size)
        assertEquals(match._id, results[0]._id)
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

// ===== Compound primary key + child tables test =====

class SqlCompoundKeyTest {
    private val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect("jdbc:h2:mem:compoundKeyTest;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
        }
    }

    @Test
    fun insertAndReadListField() = runTest {
        val collection = database.collection<CompoundKeyModel>("insertAndReadListField")
        val model = CompoundKeyModel(
            _id = CompoundId("x", 1),
            name = "test",
            tags = listOf("alpha", "beta", "gamma"),
        )
        collection.insertOne(model)

        val found = collection.find(Condition.Always).firstOrNull()
        assertNotNull(found)
        assertEquals(CompoundId("x", 1), found._id)
        assertEquals("test", found.name)
        assertEquals(listOf("alpha", "beta", "gamma"), found.tags)
    }

    @Test
    fun updateListField() = runTest {
        val collection = database.collection<CompoundKeyModel>("updateListField")
        val model = CompoundKeyModel(
            _id = CompoundId("y", 2),
            name = "update-test",
            tags = listOf("one", "two"),
        )
        collection.insertOne(model)

        val updated = collection.updateOne(
            Condition.Always,
            Modification.Assign(model.copy(tags = listOf("three", "four", "five"))),
        )
        assertNotNull(updated.new)
        assertEquals(listOf("three", "four", "five"), updated.new!!.tags)

        val found = collection.find(Condition.Always).firstOrNull()
        assertNotNull(found)
        assertEquals(listOf("three", "four", "five"), found.tags)
    }

    @Test
    fun deleteRemovesChildRows() = runTest {
        val collection = database.collection<CompoundKeyModel>("deleteRemovesChildRows")
        val model = CompoundKeyModel(
            _id = CompoundId("z", 3),
            name = "delete-test",
            tags = listOf("a", "b", "c"),
        )
        collection.insertOne(model)
        assertEquals(1, collection.count(Condition.Always))

        val deleted = collection.deleteOne(Condition.Always)
        assertNotNull(deleted)
        assertEquals(CompoundId("z", 3), deleted._id)
        assertEquals(0, collection.count(Condition.Always))

        // Re-insert to confirm child rows were cleaned up (no FK violation or stale data)
        collection.insertOne(model)
        val reFound = collection.find(Condition.Always).firstOrNull()
        assertNotNull(reFound)
        assertEquals(listOf("a", "b", "c"), reFound.tags)
    }
}
