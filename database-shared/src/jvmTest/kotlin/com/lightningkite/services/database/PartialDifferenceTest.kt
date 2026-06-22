
package com.lightningkite.services.database

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartialDifferenceTest {

    private val props = LargeTestModel.serializer().serializableProperties!!
    private fun prop(name: String) = props.first { it.name == name }
    private val cufeProps = ClassUsedForEmbedding.serializer().serializableProperties!!
    private fun cufeProp(name: String) = cufeProps.first { it.name == name }

    @Test
    fun noDifferenceIsEmpty() {
        val m = LargeTestModel(int = 5)
        assertEquals(0, partialOfDifference(m, m)?.parts?.size ?: -1)
    }

    @Test
    fun leafDifferenceOnlyChangedField() {
        val old = LargeTestModel(int = 1, string = "a")
        val new = old.copy(int = 2)
        val diff = partialOfDifference(old, new)!!
        assertEquals(setOf(prop("int")), diff.parts.keys, "only changed field should be present")
        assertEquals(2, diff.parts[prop("int")])
    }

    @Test
    fun nestedCompositeStoredAsPartialWithOnlyChangedSubField() {
        val old = LargeTestModel(embedded = ClassUsedForEmbedding(value1 = "a", value2 = 1))
        val new = old.copy(embedded = ClassUsedForEmbedding(value1 = "b", value2 = 1))
        val diff = partialOfDifference(old, new)!!
        val stored = diff.parts[prop("embedded")]
        assertTrue(stored is Partial<*>, "composite diff must be a nested Partial, got ${stored?.let { it::class }}")
        @Suppress("UNCHECKED_CAST")
        val inner = stored as Partial<ClassUsedForEmbedding>
        assertEquals(setOf(cufeProp("value1")), inner.parts.keys, "nested diff should hold only the changed sub-field")
        assertEquals("b", inner.parts[cufeProp("value1")])
    }

    @Test
    fun oldNullProducesFullPartialWithCompositeWrapped() {
        val new = LargeTestModel(int = 9, embedded = ClassUsedForEmbedding(value1 = "z"))
        val diff = partialOfDifference(LargeTestModel.serializer(), old = null, new = new)!!
        assertTrue(diff.parts[prop("embedded")] is Partial<*>, "composite field must be wrapped as Partial")
        assertEquals(9, diff.parts[prop("int")])
    }

    @Test
    fun nullableCompositeBothNonNull() {
        val old = LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value1 = "a"))
        val new = old.copy(embeddedNullable = ClassUsedForEmbedding(value1 = "b"))
        val diff = partialOfDifference(old, new)!!
        val stored = diff.parts[prop("embeddedNullable")]
        assertTrue(stored is Partial<*>, "nullable composite diff must be a nested Partial, got ${stored?.let { it::class }}")
    }

    @Test
    fun nullableCompositeNullToNonNull() {
        val old = LargeTestModel(embeddedNullable = null)
        val new = old.copy(embeddedNullable = ClassUsedForEmbedding(value1 = "b"))
        val diff = partialOfDifference(old, new)!!
        val stored = diff.parts[prop("embeddedNullable")]
        assertTrue(stored is Partial<*>, "nullable composite diff (null->value) must be a nested Partial")
    }

    @Test
    fun nullableCompositeNonNullToNull() {
        val old = LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value1 = "a"))
        val new = old.copy(embeddedNullable = null)
        val diff = partialOfDifference(old, new)!!
        assertTrue(diff.parts.containsKey(prop("embeddedNullable")), "field cleared to null should be recorded")
        assertNull(diff.parts[prop("embeddedNullable")], "value should be null")
    }

    @Test
    fun differenceRoundTrips() {
        val old = LargeTestModel(embedded = ClassUsedForEmbedding(value1 = "a", value2 = 1), int = 1)
        val new = old.copy(embedded = ClassUsedForEmbedding(value1 = "b", value2 = 1), int = 2)
        val diff = partialOfDifference(old, new)!!
        val ser = Partial.serializer(LargeTestModel.serializer())
        val json = Json.encodeToString(ser, diff)
        println("diff json: $json")
        val back = Json.decodeFromString(ser, json)
        assertTrue(back.parts[prop("embedded")] is Partial<*>)
        assertEquals(2, back.parts[prop("int")])
    }
}
