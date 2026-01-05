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
