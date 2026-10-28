package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The parts of vector filter declarations that don't need a running Atlas. */
class VectorSearchFilterDeclarationTest {
    @Test
    fun indexDeclaresNestedAndIndexedFilters() {
        assertEquals(setOf("category", "tags", "review.approved"), VectorTestModel.serializer().vectorSearchFilterPaths("embedding"))
    }

    @Test
    fun undeclaredFilterFailsBeforeQuerying() = runTest {
        val table = MongoDatabase(
            "atlas",
            clientSettings = TestDatabase.settings,
            databaseName = "vectorSearchFilterDeclarationTest",
            atlasSearch = true,
            context = TestSettingContext(),
        ).table(DatabaseTableDefinition<VectorTestModel>("undeclared"))
        val e = assertFailsWith<IllegalArgumentException> {
            table.findSimilar(
                VectorTestModel.path.embedding,
                VectorSearchParams(queryVector = Embedding.of(1f, 0f, 0f), limit = 1),
                VectorTestModel.path.review.notNull.reviewer eq "someone",
            )
        }
        assertContains(e.message!!, "'review.reviewer'")
    }
}
