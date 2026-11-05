package com.lightningkite.services.database.mongodb

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.optics.*
import com.lightningkite.services.database.test.*
import com.lightningkite.services.embedding.EmbeddingModelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Needs Atlas: Docker for the atlas-local container, or MONGO_VECTOR_TEST_URL. */
class MongodbVectorIndexTests : VectorIndexTests() {
    override val database: VectorDatabase get() = VectorTestDatabase.mongoClient!!

    @BeforeTest
    fun requireAtlas() {
        assumeTrue("Vector search needs Docker or MONGO_VECTOR_TEST_URL", VectorTestDatabase.isAvailable)
    }

    // mongot follows the collection through change streams, so searches lag behind writes.
    override suspend fun waitForIndex(index: VectorIndex<String>, count: Int) {
        var lastSeen = -1
        val reached = withContext(Dispatchers.Default) {
            withTimeoutOrNull(30.seconds) {
                while (true) {
                    lastSeen = index.search(Embedding.of(1f, 0f, 0f), limit = 1000).size
                    if (lastSeen == count) break
                    delay(200)
                }
            }
        }
        checkNotNull(reached) { "Vector index didn't reach $count entries in 30s; last saw $lastSeen" }
    }
}

/** What runs without Atlas: storage on an embedded MongoDB. */
class MongoVectorIndexStorageTest {
    private val database = MongoDatabase("vectors", clientSettings = TestDatabase.settings, databaseName = "vectorStorage", context = TestSettingContext())
    private val definition = VectorIndexDefinition("storage", String.serializer(), VectorSpace(EmbeddingModelId("test-model"), 3))

    @Test
    fun storesEntriesInOneBulkWrite() = runTest {
        val index = database.index(definition)
        index.delete(index.ids().toList())
        index.upsert(listOf(VectorIndexEntry("a", Embedding.of(1f, 0f, 0f)), VectorIndexEntry("b", Embedding.of(0f, 1f, 0f))))
        index.upsert(listOf(VectorIndexEntry("a", Embedding.of(0f, 0f, 1f))))
        assertEquals(VectorIndexEntry("a", Embedding.of(0f, 0f, 1f)), index.get(listOf("a")).single())
        assertEquals(setOf("a", "b"), index.ids().toList().toSet())
        index.delete(listOf("b"))
        assertEquals(setOf("a"), index.ids().toList().toSet())
    }
}
