package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.mongodb.TestDatabase.mongoClient
import com.lightningkite.services.database.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.firstOrNull
import com.mongodb.MongoCommandException
import org.bson.Document
import org.junit.Assume.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


object TestDatabase {
    val settings = testMongo()
    val mongoClient =
        MongoDatabase("default", clientSettings = settings, databaseName = "test", context = TestSettingContext())
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

class MongodbAggregationsTest : AggregationsTest() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MongodbConditionTests : ConditionTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

open class MongodbModificationTests : ModificationTests() {
    override val database: Database get() = db()

    @Test
    fun start() {
    }

    // MongoDB puts every notNull/asType check in the update's filter, so a row failing any check isn't written or
    // matched at all, where memory applies the parts whose checks hold and reports the row matched.

    @Test
    override fun test_notNull_chain() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("LargeTestModel_test_notNull_chain"))
        val failing = LargeTestModel(embeddedNullable = null, intNullable = 5)
        val passing = LargeTestModel(embeddedNullable = ClassUsedForEmbedding("a", 2), intNullable = 5)
        collection.insert(listOf(failing, passing))
        val modification = modification<LargeTestModel> {
            it.int assign 7
            it.embeddedNullable.notNull.value1 assign "changed"
            it.intNullable.notNull coerceAtMost 3
        }
        assertEquals(EntryChange<LargeTestModel>(null, null), collection.updateOneById(failing._id, modification))
        assertEquals(failing, collection.get(failing._id))
        collection.updateOneById(passing._id, modification)
        assertEquals(modification(passing), collection.get(passing._id))
    }

    @Test
    override fun test_notNull_unchangedRowsStillMatch() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("LargeTestModel_test_notNull_unchangedRowsStillMatch"))
        val item = LargeTestModel(intNullable = null)
        collection.insert(listOf(item, LargeTestModel(intNullable = 5)))
        val modification = modification<LargeTestModel> { it.intNullable.notNull += 1 }
        val byId = condition<LargeTestModel> { it._id eq item._id }

        assertEquals(EntryChange<LargeTestModel>(null, null), collection.updateOne(byId, modification))
        assertFalse(collection.updateOneIgnoringResult(byId, modification))
        assertEquals(1, collection.updateManyIgnoringResult(Condition.Always, modification))
        // An upsert still finds the row, so it doesn't insert a duplicate.
        assertEquals(EntryChange(item, item), collection.upsertOne(byId, modification, item))
        assertTrue(collection.upsertOneIgnoringResult(byId, modification, item))
        assertEquals(item, collection.get(item._id))
        assertEquals(2, collection.count(Condition.Always))
    }
}

// Claude: runs DocumentDB's non-overlapping array filters on MongoDB, which can check they give the same result.
class MongodbDisjointArrayFiltersModificationTests : MongodbModificationTests() {
    override val database: Database get() = disjointArrayFiltersDb

    companion object {
        val disjointArrayFiltersDb = MongoDatabase(
            "disjoint-array-filters",
            clientSettings = TestDatabase.settings,
            databaseName = "test-disjoint-array-filters",
            supportsOverlappingArrayFilters = false,
            context = TestSettingContext()
        )
    }
}

class MongodbSealedPathTests : SealedPathTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }

    // MongoDB puts every asType check in the update's filter, so a row failing any check isn't written at all, where
    // memory applies the parts whose checks hold.
    @Test
    override fun test_asType_chain_otherVariant() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<SealedPathTestModel>("SealedPathTestModel_test_asType_chain_otherVariant"))
        val item = SealedPathTestModel(shape = Shape.Square(2, "a"), payment = Payment.Card(10, "1111"))
        collection.insertOne(item)
        val change = collection.updateOneById(item._id, modification {
            it.shape.asCircle.radius += 1
            it.shape.asSquare.label assign "changed"
            it.payment.asCard.amount += 5
            it.payment.asCashPayment.currency assign "EUR"
        })
        assertEquals(EntryChange<SealedPathTestModel>(null, null), change)
        assertEquals(item, collection.get(item._id))
    }
}

class MongodbOperationsTests : OperationsTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MongodbSortTest : SortTest() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MongodbMetaTest : MetaTest() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MongodbIndexTest : IndexTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MondodbInlineTests : InlinePropertiesTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MongodbSingleRowOperationTests : SingleRowOperationTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MongodbReturnContractTests : ReturnContractTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MongodbPaginationTests : PaginationTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MongodbScaleAndBoundaryTests : ScaleAndBoundaryTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MongodbConcurrencyTests : ConcurrencyTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class MongodbVectorSearchTests : VectorSearchTests() {
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
class MongodbTextSearchTests : TextSearchTests() {
    override val database: Database = db()

    // Claude: classic $text stems words but has no typo tolerance, and its fixed scoring formula isn't BM25.
    override val supportsTypoTolerance: Boolean = false
    override val supportsBm25Ranking: Boolean = false
}

class MongodbAtlasTextSearchTests : TextSearchTests() {
    override val database: Database = vectorDb()

    // Claude: mongot syncs documents via change streams, so searches lag behind writes.
    override suspend fun waitForIndex(table: Table<*>, count: Int) {
        table as MongoTable<*>
        // Claude: counts rows whose first text field is indexed, so every test row needs a non-empty one.
        val firstTextField = table.indexedTextFields!!.first().mongo
        val countIndexed = Document(
            "\$searchMeta",
            Document("index", "default").append("exists", Document("path", firstTextField)).append("count", Document("type", "total"))
        )
        // Claude: on Dispatchers.Default so runTest's virtual time doesn't skip the polling delays.
        var lastSeen: Any? = null
        val reached = withContext(Dispatchers.Default) {
            withTimeoutOrNull(30.seconds) {
                while (true) {
                    lastSeen = try {
                        // Claude: $searchMeta returns no document when nothing matches.
                        table.access.run { aggregate<Document>(listOf(countIndexed)).firstOrNull() }
                            ?.getEmbedded(listOf("count", "total"), Number::class.java)?.toInt() ?: 0
                    } catch (e: MongoCommandException) {
                        e.errorMessage // Claude: the search index may still be building.
                    }
                    if (lastSeen == count) break
                    delay(100)
                }
            }
        }
        checkNotNull(reached) { "Search index didn't reach $count rows in 30s; last saw: $lastSeen" }
    }

    @BeforeTest
    fun requireAtlas() {
        assumeTrue("Atlas Search needs Docker or MONGO_VECTOR_TEST_URL", VectorTestDatabase.isAvailable)
    }
}

class MongodbMaskedPermissionsTests : MaskedPermissionsTests() {
    override val database: Database = db()
}
