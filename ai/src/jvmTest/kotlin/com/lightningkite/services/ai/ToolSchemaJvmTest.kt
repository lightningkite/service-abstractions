package com.lightningkite.services.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM-only sealed-hierarchy assertions. The full variant shape (`properties` with the const
 * `type` discriminator) requires reflection into `SealedClassSerializer.serialName2Serializer`,
 * which [findSealedSerializers] only supplies on JVM. On non-JVM the schema collapses to a
 * name-only stub, so this assertion would fail there.
 */
class ToolSchemaJvmTest {

    @Serializable
    sealed class Shape {
        @Serializable data class Circle(val radius: Double) : Shape()
        @Serializable data class Rect(val width: Double, val height: Double) : Shape()
    }

    @Test fun sealedVariantsCarryDiscriminator() {
        val schema = serializer<Shape>().toJsonSchema()
        val oneOf = schema["oneOf"] as JsonArray
        assertTrue(oneOf.isNotEmpty(), "sealed should produce oneOf variants")
        oneOf.forEach { variant ->
            val obj = variant as JsonObject
            val props = obj["properties"] as JsonObject
            val typeProp = props["type"] as JsonObject
            assertNotNull(typeProp["const"])
            val required = (obj["required"] as JsonArray).map { (it as JsonPrimitive).content }
            assertTrue("type" in required, "discriminator must be required")
        }
    }
}
