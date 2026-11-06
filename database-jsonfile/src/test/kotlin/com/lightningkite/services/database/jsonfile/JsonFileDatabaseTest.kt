package com.lightningkite.services.database.jsonfile

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import com.lightningkite.services.embedding.EmbeddingModelId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

// Each database gets its own directory. Tables now persist asynchronously, so a shared directory
// wiped by the next test's setup would race with a prior test's in-flight background save
// (manifesting as "Deletion failed"). Unique dirs keep every test's file I/O fully isolated.
// The root is wiped once at class-load - before any database (and thus any background save)
// exists - so leftover files from a previous run can't leak into this run's databases.
private val testRoot = KFile("build/testrun").also { it.deleteRecursively() }
private val dbCounter = java.util.concurrent.atomic.AtomicInteger(0)
private fun db() = JsonFileDatabase(
    "test",
    testRoot.then("${dbCounter.incrementAndGet()}"),
    TestSettingContext()
)

class JsonFileAggregationsTest : AggregationsTest() {
    override val database: Database = db()
}

class JsonFileConditionTests : ConditionTests() {
    override val database: Database = db()
}

class JsonFileModificationTests : ModificationTests() {
    override val database: Database = db()
}

class JsonFileSealedLensTests : SealedLensTests() {
    override val database: Database = db()
}

class JsonFileSealedPathTests : SealedPathTests() {
    override val database: Database = db()
}

class JsonFileOperationsTests : OperationsTests() {
    override val database: Database = db()
}

class JsonFileSortTest : SortTest() {
    override val database: Database = db()
}

class JsonFileMetaTest : MetaTest() {
    override val database: Database = db()
}

class JsonFileInlinesTest : InlinePropertiesTests() {
    override val database: Database = db()

    @Test
    fun start() {
    }
}

class JsonFileIndexTests : IndexTests() {
    override val database: Database = db()
}

class JsonFileSingleRowOperationTests : SingleRowOperationTests() {
    override val database: Database = db()
}

class JsonFileReturnContractTests : ReturnContractTests() {
    override val database: Database = db()
}

class JsonFilePaginationTests : PaginationTests() {
    override val database: Database = db()
}

class JsonFileScaleAndBoundaryTests : ScaleAndBoundaryTests() {
    override val database: Database = db()
}

class JsonFileConcurrencyTests : ConcurrencyTests() {
    override val database: Database = db()
}
class JsonFileTextSearchTests : TextSearchTests() {
    override val database: Database = db()
}

class JsonFileVectorIndexTests : VectorIndexTests() {
    override val database: VectorDatabase = db()
}

class JsonFileVectorIndexPersistenceTest {
    @Test
    fun indexSurvivesReopeningFromUrl() = runTest {
        val folder = testRoot.then("${dbCounter.incrementAndGet()}")
        val definition = VectorIndexDefinition("persisted", String.serializer(), VectorSpace(EmbeddingModelId("test-model"), 3))
        val written = JsonFileDatabase("test", folder, TestSettingContext())
        written.prepare(definition).upsert(listOf(VectorIndexEntry("a", Embedding.of(1f, 0f, 0f))))
        written.disconnect()

        val reopened = VectorDatabase.Settings("json-files://${folder.resolved}")("test", TestSettingContext())
        assertEquals(listOf("a"), reopened.index(definition).search(Embedding.of(1f, 0f, 0f), limit = 5).map { it.model })
    }
}
