package com.lightningkite.services.database.cassandra

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.table
import com.lightningkite.services.database.test.AggregationsTest
import com.lightningkite.services.database.test.ConditionTests
import com.lightningkite.services.database.test.LargeTestModel
import com.lightningkite.services.database.test.MetaTest
import com.lightningkite.services.database.test.ModificationTests
import com.lightningkite.services.database.test.OperationsTests
import com.lightningkite.services.database.test.SortTest
import com.lightningkite.services.database.test.VectorSearchTests
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/**
 * Test database using TestContainers.
 *
 * This automatically starts a Cassandra Docker container if Docker is available.
 * The container is shared across all test classes and stopped when tests complete.
 */
object TestDatabase {
    val isAvailable: Boolean by lazy {
        try {
            // Try to start the container
            cassandraClient
            true
        } catch (e: Exception) {
            System.err.println("Cassandra test container not available: ${e.message}")
            false
        }
    }

    val cassandraClient: CassandraDatabase by lazy {
        embeddedCassandra(
            name = "test",
            context = TestSettingContext()
        )
    }
}

fun db(): Database {
    if (!TestDatabase.isAvailable) {
        throw IllegalStateException("Cassandra not available - Docker required for tests")
    }
    return TestDatabase.cassandraClient
}

class CassandraUltrabasicTest {
    val database: Database get() = db()

    @Test fun testBasic() = runTest {
        val collection = database.table<LargeTestModel>("testBasic")
        // Clean up any existing data first
        collection.deleteManyIgnoringOld(Condition.Always)

        val entry = LargeTestModel()
        collection.insertOne(entry)
        val results = collection.find(Condition.Always).toList()
        assertEquals(entry, results.single())
    }
}

class CassandraAggregationsTest : AggregationsTest() {
    override val database: Database get() = db()
}

class CassandraConditionTests : ConditionTests() {
    override val database: Database get() = db()
}

class CassandraModificationTests : ModificationTests() {
    override val database: Database get() = db()
}

class CassandraOperationsTests : OperationsTests() {
    override val database: Database get() = db()
}

class CassandraSortTest : SortTest() {
    override val database: Database get() = db()
}

class CassandraMetaTest : MetaTest() {
    override val database: Database get() = db()
}

class CassandraVectorSearchTests : VectorSearchTests() {
    override val database: Database get() = db()

    // Cassandra doesn't natively support vector search
    override val supportsVectorSearch: Boolean = false
    override val supportsSparseVectorSearch: Boolean = false
    override val supportsQueryTimeMetrics: Boolean = false
    override val vectorSearchIndexSyncDelay: Duration = Duration.ZERO
}

// by Claude - test for Set<String> serialization issue
@kotlinx.serialization.Serializable
data class StringSetTestModel(
    override val _id: kotlin.uuid.Uuid = kotlin.uuid.Uuid.random(),
    val tags: Set<String> = setOf(),
    val names: List<String> = listOf(),
) : com.lightningkite.services.database.HasId<kotlin.uuid.Uuid>

// by Claude - inline value class to test the escaping bug
@kotlin.jvm.JvmInline
@kotlinx.serialization.Serializable
value class TestScope(val asString: String)

@kotlinx.serialization.Serializable
data class InlineValueClassSetModel(
    override val _id: kotlin.uuid.Uuid = kotlin.uuid.Uuid.random(),
    val scopes: Set<TestScope> = setOf(),
) : com.lightningkite.services.database.HasId<kotlin.uuid.Uuid>

class CassandraStringSetTest {
    val database: Database get() = db()

    @Test
    fun testStringSetRoundTrip() = runTest {
        val collection = database.table<StringSetTestModel>("StringSetTestModel")
        collection.deleteManyIgnoringOld(Condition.Always)

        val original = StringSetTestModel(
            tags = setOf("tag1", "tag2", "tag3"),
            names = listOf("Alice", "Bob", "Charlie")
        )
        collection.insertOne(original)

        val retrieved = collection.find(Condition.Always).toList().single()

        println("Original tags: ${original.tags}")
        println("Retrieved tags: ${retrieved.tags}")
        println("Original names: ${original.names}")
        println("Retrieved names: ${retrieved.names}")

        // Check that strings don't have extra quotes
        retrieved.tags.forEach { tag ->
            println("Tag value: '$tag', length: ${tag.length}")
            assert(!tag.startsWith("\"")) { "Tag should not start with quote: $tag" }
            assert(!tag.endsWith("\"")) { "Tag should not end with quote: $tag" }
        }
        retrieved.names.forEach { name ->
            println("Name value: '$name', length: ${name.length}")
            assert(!name.startsWith("\"")) { "Name should not start with quote: $name" }
            assert(!name.endsWith("\"")) { "Name should not end with quote: $name" }
        }

        assertEquals(original.tags, retrieved.tags)
        assertEquals(original.names, retrieved.names)
        assertEquals(original, retrieved)
    }

    @Test
    fun testStringSetMultipleRoundTrips() = runTest {
        val collection = database.table<StringSetTestModel>("StringSetTestModelMulti")
        collection.deleteManyIgnoringOld(Condition.Always)

        val original = StringSetTestModel(
            tags = setOf("hello", "world"),
            names = listOf("test")
        )
        collection.insertOne(original)

        // Read it back multiple times to check for progressive escaping
        repeat(3) { i ->
            val retrieved = collection.find(Condition.Always).toList().single()
            println("Round $i - tags: ${retrieved.tags}")

            // Update it back
            collection.deleteManyIgnoringOld(Condition.Always)
            collection.insertOne(retrieved)
        }

        val final = collection.find(Condition.Always).toList().single()
        println("Final tags: ${final.tags}")
        assertEquals(original.tags, final.tags)
    }

    // by Claude - test for inline value class in Set (reproduces GrantedScope bug)
    @Test
    fun testInlineValueClassSetRoundTrip() = runTest {
        val collection = database.table<InlineValueClassSetModel>("InlineValueClassSetModel")
        collection.deleteManyIgnoringOld(Condition.Always)

        val original = InlineValueClassSetModel(
            scopes = setOf(TestScope("*"), TestScope("admin"), TestScope("user:read"))
        )
        collection.insertOne(original)

        val retrieved = collection.find(Condition.Always).toList().single()

        println("Original scopes: ${original.scopes.map { it.asString }}")
        println("Retrieved scopes: ${retrieved.scopes.map { it.asString }}")

        // Check that strings don't have extra quotes
        retrieved.scopes.forEach { scope ->
            println("Scope value: '${scope.asString}', length: ${scope.asString.length}")
            assert(!scope.asString.startsWith("\"")) { "Scope should not start with quote: ${scope.asString}" }
            assert(!scope.asString.endsWith("\"")) { "Scope should not end with quote: ${scope.asString}" }
        }

        assertEquals(original.scopes, retrieved.scopes)
    }

    @Test
    fun testInlineValueClassSetMultipleRoundTrips() = runTest {
        val collection = database.table<InlineValueClassSetModel>("InlineValueClassSetModelMulti")
        collection.deleteManyIgnoringOld(Condition.Always)

        val original = InlineValueClassSetModel(
            scopes = setOf(TestScope("*"))
        )
        collection.insertOne(original)

        // Read it back multiple times to check for progressive escaping
        repeat(3) { i ->
            val retrieved = collection.find(Condition.Always).toList().single()
            println("Round $i - scopes: ${retrieved.scopes.map { it.asString }}")

            // Update it back
            collection.deleteManyIgnoringOld(Condition.Always)
            collection.insertOne(retrieved)
        }

        val final = collection.find(Condition.Always).toList().single()
        println("Final scopes: ${final.scopes.map { it.asString }}")

        // The bug would show progressive escaping: "*" -> "\"*\"" -> "\"\\\"*\\\"\""
        assertEquals(setOf(TestScope("*")), final.scopes)
        assertEquals("*", final.scopes.first().asString)
    }
}
