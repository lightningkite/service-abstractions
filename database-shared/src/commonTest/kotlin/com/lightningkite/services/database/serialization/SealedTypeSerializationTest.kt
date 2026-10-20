@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.path.*

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SealedTypeSerializationTest {
    private val conditionSerializer = Condition.serializer(Polymorphic.serializer())
    private val modificationSerializer = Modification.serializer(Polymorphic.serializer())
    private val pathSerializer = OpticOnSerializer(Polymorphic.serializer())

    @Test
    fun conditionRoundTrips() {
        val condition = condition<Polymorphic> { it.asFoo.name eq "a" }
        val encoded = Json.encodeToString(conditionSerializer, condition)
        assertEquals(condition, Json.decodeFromString(conditionSerializer, encoded))
    }

    @Test
    fun modificationRoundTrips() {
        val modification = modification<Polymorphic> { it.asBar.id += 1 }
        val encoded = Json.encodeToString(modificationSerializer, modification)
        assertEquals(modification, Json.decodeFromString(modificationSerializer, encoded))
    }

    @Test
    fun shortVariantNamesDecode() {
        assertEquals(
            condition<Polymorphic> { it.asFoo.name eq "a" },
            Json.decodeFromString(conditionSerializer, """{"Foo":{"name":{"Equal":"a"}}}""")
        )
        assertEquals(
            modification<Polymorphic> { it.asBar.id += 1 },
            Json.decodeFromString(modificationSerializer, """{"Bar":{"id":{"Increment":1}}}""")
        )
    }

    @Test
    fun pathsRoundTrip() {
        val variant = Polymorphic.path.asFoo
        val field = Polymorphic.path.asFoo.name
        assertEquals("<Foo>", pathSerializer.asString(variant))
        assertEquals("<Foo>.name", pathSerializer.asString(field))
        assertEquals(variant, pathSerializer.fromString("<Foo>"))
        assertEquals(field, pathSerializer.fromString("<Foo>.name"))
        assertEquals(field, pathSerializer.fromString("<com.lightningkite.services.database.Polymorphic.Foo>.name"))
        assertEquals(listOf("name"), field.properties.map { it.name })
    }
}
