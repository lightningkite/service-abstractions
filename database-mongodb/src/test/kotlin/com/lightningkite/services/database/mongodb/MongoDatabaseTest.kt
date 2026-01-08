package com.lightningkite.services.database.mongodb

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.mongodb.TestDatabase.mongoClient
import com.lightningkite.services.database.test.AggregationsTest
import com.lightningkite.services.database.test.ConditionTests
import com.lightningkite.services.database.test.IndexTests
import com.lightningkite.services.database.test.MetaTest
import com.lightningkite.services.database.test.ModificationTests
import com.lightningkite.services.database.test.OperationsTests
import com.lightningkite.services.database.test.SortTest
import com.lightningkite.services.database.test.VectorSearchTests
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


object TestDatabase {
    val settings = testMongo()
    val mongoClient = MongoDatabase("default", clientSettings = settings, databaseName = "test", context = TestSettingContext())
}
fun db() = mongoClient

object UniqueIndexTests {

}

/**
 * Test database configured for vector search testing.
 *
 * This automatically starts a MongoDB Atlas Local Docker container if Docker is available.
 * The container is shared across all test classes and stopped when tests complete.
 *
 * You can also manually set MONGO_VECTOR_TEST_URL environment variable to use a different
 * MongoDB instance (e.g., a real MongoDB Atlas cluster).
 */
object VectorTestDatabase {
    // Allow override via environment variable for CI/CD or custom setups
    private val manualUrl = System.getenv("MONGO_VECTOR_TEST_URL")

    val isAvailable: Boolean by lazy {
        // If manual URL is set, use that
        if (manualUrl != null) return@lazy true

        // Otherwise, try to start Docker container
        MongoDockerContainer.ensureStarted()
    }

    private val connectionUrl: String by lazy {
        manualUrl ?: MongoDockerContainer.connectionUrl
    }

    val mongoClient: MongoDatabase? by lazy {
        if (!isAvailable) return@lazy null

        val url = connectionUrl
        // Extract database name from URL - handles both mongodb:// and mongodb+srv://
        val databaseName = Regex("""mongodb(?:\+srv)?://[^/]*/([^?]+).*""")
            .matchEntire(url)?.groupValues?.getOrNull(1) ?: "test"

        MongoDatabase(
            name = "vector-test",
            databaseName = databaseName,
            clientSettings = com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(com.mongodb.ConnectionString(url))
                .build(),
            atlasSearch = true,
            context = TestSettingContext()
        )
    }
}
fun vectorDb() = VectorTestDatabase.mongoClient ?: db()

class MongodbAggregationsTest: AggregationsTest() {
    override val database: Database = db()
}
class MongodbConditionTests: ConditionTests() {
    override val database: Database = db()
}
class MongodbModificationTests: ModificationTests() {
    override val database: Database = db()
}
class MongodbOperationsTests: OperationsTests() {
    override val database: Database = db()
}
class MongodbSortTest: SortTest() {
    override val database: Database = db()
}
class MongodbMetaTest: MetaTest() {
    override val database: Database = db()
}
class MongodbIndexTest: IndexTests() {
    override val database: Database = db()

    @Test
    fun start() {}
}

class MongodbVectorSearchTests: VectorSearchTests() {
    override val database: Database = vectorDb()
    // Vector search requires MongoDB Atlas or MongoDB 8.2+ with mongot
    // Tests will run if MONGO_VECTOR_TEST_URL environment variable is set
    override val supportsVectorSearch: Boolean = VectorTestDatabase.isAvailable
    override val supportsSparseVectorSearch: Boolean = false // MongoDB doesn't support sparse vectors
    // MongoDB Atlas requires similarity metric at index creation time, not query time
    override val supportsQueryTimeMetrics: Boolean = false

    // mongot syncs documents via Change Streams which is eventually consistent.
    // We need to wait for documents to appear in the search index after insertion.
    override val vectorSearchIndexSyncDelay: Duration = 3.seconds
}