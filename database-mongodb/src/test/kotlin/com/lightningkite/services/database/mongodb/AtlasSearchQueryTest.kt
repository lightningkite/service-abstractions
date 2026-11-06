package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Turning on atlasSearch must not change what ordinary queries match. */
class AtlasSearchQueryTest {
    private val atlasDb = MongoDatabase(
        "atlas",
        clientSettings = TestDatabase.settings,
        databaseName = "atlasSearchQueryTest",
        atlasSearch = true,
        context = TestSettingContext(),
    )

    @Test
    fun anyElementsMatchesBothFieldsOnOneElement() = runTest {
        val collection = atlasDb.prepare(DatabaseTableDefinition<SimpleLargeTestModel>("anyElements"))
        collection.deleteMany(Condition.Always)
        // Each element satisfies one half of the condition, but no single element satisfies both.
        val split = SimpleLargeTestModel(listEmbedded = listOf(ClassUsedForEmbedding("a", 2), ClassUsedForEmbedding("b", 1)))
        val together = SimpleLargeTestModel(listEmbedded = listOf(ClassUsedForEmbedding("a", 1)))
        collection.insert(listOf(split, together))
        val condition = Condition<SimpleLargeTestModel> { it.listEmbedded.any { (it.value1 eq "a") and (it.value2 eq 1) } }

        assertEquals(listOf(together._id), collection.find(condition).toList().map { it._id })
        assertEquals(1, collection.count(condition))
        assertEquals(listOf(together._id), collection.deleteMany(condition).map { it._id })
        assertEquals(listOf(split._id), collection.find(Condition.Always).toList().map { it._id })
    }
}
