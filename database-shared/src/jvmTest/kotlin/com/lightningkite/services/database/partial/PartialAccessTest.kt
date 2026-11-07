@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartialAccessTest {
    private val model = LargeTestModel(
        int = 3,
        intNullable = null,
        embedded = ClassUsedForEmbedding("e", 2),
        embeddedNullable = ClassUsedForEmbedding("n", 4),
        listEmbedded = listOf(ClassUsedForEmbedding("a", 1), ClassUsedForEmbedding("b", 2)),
    )
    private val m = Lens<LargeTestModel>()

    @Test
    fun readsIncludedTargets() {
        val partial = partialOf(model, listOf(m.int, m.intNullable, m.embedded, m.embeddedNullable.notNull.value1, m.listEmbedded))
        assertEquals(3, partial.getValue(m.int))
        assertNull(partial.getValue(m.intNullable))
        assertEquals(model.embedded, partial.getValue(m.embedded))
        assertEquals(2, partial.getValue(m.embedded.value2))
        assertEquals("n", partial.getValue(m.embeddedNullable.notNull.value1))
        assertEquals(model.listEmbedded, partial.getValue(m.listEmbedded))
        assertEquals(model.listEmbedded.first(), partial.getValue(m.listEmbedded.elements))
        assertEquals("a", partial[m.listEmbedded.elements.value1])
    }

    @Test
    fun missingTargetsAreAbsent() {
        val partial = partialOf(model, listOf(m.embedded.value1, m.embeddedNullable.notNull.value1))
        assertTrue(m.embedded.value1 in partial)
        assertFalse(m.int in partial)
        assertFalse(m.embedded in partial, "embedded is only partly included")
        assertFalse(m.embeddedNullable.notNull.value2 in partial)
        assertNull(partial[m.int])
        assertNull(partial[m.embedded])
        assertFailsWith<NoSuchElementException> { partial.getValue(m.int) }
        assertFailsWith<NoSuchElementException> { partial.getValue(m.embeddedNullable.notNull.value2) }
    }

    @Test
    fun nullAlongTheWayIsIncluded() {
        val partial = partialOf(model.copy(embeddedNullable = null), listOf(m.embeddedNullable.notNull.value1))
        assertTrue(m.embeddedNullable.notNull.value2 in partial)
        assertNull(partial.getValue(m.embeddedNullable.notNull.value2))
        assertNull(partial.getValue(m.embeddedNullable))
    }

    @Test
    fun collectionPathsStoreTheWholeCollection() {
        val partial = partialOf(model, listOf(m.listEmbedded.elements.value1))
        assertEquals(model.listEmbedded, partial.parts[LargeTestModel.listEmbedded])
        val serializer = Partial.serializer(LargeTestModel.serializer())
        assertEquals(partial, Json.decodeFromString(serializer, Json.encodeToString(serializer, partial)))
    }

    @Test
    fun totalNeedsNestedFieldsToo() {
        val serializer = LargeTestModel.serializer()
        val everythingButOneNestedField = serializer.fields!!.filter { it.name != "embedded" } + m.embedded.value1
        assertNull(partialOf(model, everythingButOneNestedField).total(serializer))
        assertEquals(model, partialOf(model, serializer.fields!!).total(serializer))
    }
}
