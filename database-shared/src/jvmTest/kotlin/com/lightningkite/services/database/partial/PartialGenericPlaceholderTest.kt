@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.path.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test

// Reproduces the crash where registering a generic model that carries a Partial of its own
// type parameter fails because the generic placeholder has no fields.
@Serializable
data class PartialGenericContent(val a: String, val b: Int)

@Serializable
data class GenericWithPartial<A>(
    val value: A,
    val partial: Partial<A>? = null,
)

class PartialGenericPlaceholderTest {
    @Test
    fun registersGenericModelWithPartialOfTypeParameter() {
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(GenericWithPartial.serializer(PartialGenericContent.serializer()))

        println("Virtual types:")
        registry.virtualTypes.values.forEach { println(it) }
    }
}
