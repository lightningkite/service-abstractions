package com.lightningkite.services.database

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

// Verifies the invariant: a Partial<T> never stores a complete composite object as a field entry;
// composite fields must hold a nested Partial instead. Leaf/inline fields hold the raw value.
class PartialCompositeInvariantTest {

    private val embeddedProp = LargeTestModel.serializer().serializableProperties!!
        .first { it.name == "embedded" }

    @Test
    fun partialOfProperties_wrapsCompositeAsPartial() {
        val model = LargeTestModel(embedded = ClassUsedForEmbedding(value1 = "x", value2 = 7))
        val partial = partialOf(model, arrayOf(embeddedProp))
        val stored = partial.parts[embeddedProp]
        println("partialOf(properties) stored for embedded: ${stored?.let { it::class }} = $stored")
        assertTrue(stored is Partial<*>, "composite field should be wrapped in a Partial, got ${stored?.let { it::class }}")
    }

    @Test
    fun partialBuilderAssign_compositeValue() {
        val partial = partialOf<LargeTestModel> {
            it.embedded assign ClassUsedForEmbedding(value1 = "x", value2 = 7)
        }
        val stored = partial.parts[embeddedProp]
        println("PartialBuilder assign(composite) stored for embedded: ${stored?.let { it::class }} = $stored")
        assertFalse(
            stored is ClassUsedForEmbedding,
            "INVARIANT VIOLATION: PartialBuilder.assign stored a complete composite object instead of a Partial",
        )
        assertTrue(stored is Partial<*>, "composite field should be wrapped in a Partial")
        @Suppress("UNCHECKED_CAST")
        val inner = stored as Partial<ClassUsedForEmbedding>
        val value1Prop = ClassUsedForEmbedding.serializer().serializableProperties!!.first { it.name == "value1" }
        assertEquals("x", inner.parts[value1Prop], "nested partial should carry the assigned leaf values")
    }

    @Test
    fun partialBuilderAssign_compositeValue_roundTrips() {
        val partial = partialOf<LargeTestModel> {
            it.embedded assign ClassUsedForEmbedding(value1 = "x", value2 = 7)
        }
        val json = Json.encodeToString(Partial.serializer(LargeTestModel.serializer()), partial)
        println("Serialized: $json")
        val back = Json.decodeFromString(Partial.serializer(LargeTestModel.serializer()), json)
        val stored = back.parts[embeddedProp]
        println("After round-trip embedded: ${stored?.let { it::class }} = $stored")
        assertTrue(stored is Partial<*>, "round-tripped composite field must be a Partial")
    }
}
