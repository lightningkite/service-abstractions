package com.lightningkite.services.ai

import com.lightningkite.services.data.Description
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolSchemaTest {

    @Serializable
    data class WeatherArgs(
        @Description("City name") val city: String,
        val units: Units = Units.Celsius,
        val hours: Int? = null,
    )

    @Serializable
    enum class Units { Celsius, Fahrenheit }

    @Serializable
    data class NestedArgs(
        val weather: WeatherArgs,
        val notes: List<String> = emptyList(),
        val metadata: Map<String, Int> = emptyMap(),
    )

    @Serializable
    @MutuallyExclusive
    data class OneOfArgs(
        val asText: String? = null,
        val asNumber: Int? = null,
    )

    @Serializable
    sealed class Shape {
        @Serializable data class Circle(val radius: Double) : Shape()
        @Serializable data class Rect(val width: Double, val height: Double) : Shape()
    }

    @Serializable
    data class HiddenFieldArgs(
        val visible: String,
        @HideFromLlm val internal: String = "",
        @LlmReadOnly val computed: Int = 0,
    )

    private val pretty = Json { prettyPrint = true }
    private fun dump(label: String, schema: JsonObject) {
        println("--- $label ---\n${pretty.encodeToString(JsonObject.serializer(), schema)}")
    }

    @Test fun flatClassWithPrimitives() {
        val schema = serializer<WeatherArgs>().toJsonSchema()
        dump("WeatherArgs", schema)
        assertEquals("object", (schema["type"] as JsonPrimitive).content)
        val props = schema["properties"] as JsonObject
        assertEquals("string", ((props["city"] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("City name", ((props["city"] as JsonObject)["description"] as JsonPrimitive).content)
        // Units is an enum → string with enum list
        val units = props["units"] as JsonObject
        assertEquals("string", (units["type"] as JsonPrimitive).content)
        val enumValues = (units["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("Celsius", "Fahrenheit"), enumValues)
        // Nullable Int → anyOf(null, integer)
        val hours = props["hours"] as JsonObject
        val anyOf = hours["anyOf"] as JsonArray
        assertEquals(2, anyOf.size)
        // required excludes optional fields
        val required = (schema["required"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("city"), required)
    }

    @Test fun nestedClassListMap() {
        val schema = serializer<NestedArgs>().toJsonSchema()
        dump("NestedArgs", schema)
        val props = schema["properties"] as JsonObject
        val weather = props["weather"] as JsonObject
        assertEquals("object", (weather["type"] as JsonPrimitive).content)
        val notes = props["notes"] as JsonObject
        assertEquals("array", (notes["type"] as JsonPrimitive).content)
        assertNotNull(notes["items"])
        val metadata = props["metadata"] as JsonObject
        assertEquals("object", (metadata["type"] as JsonPrimitive).content)
        val addl = metadata["additionalProperties"] as JsonObject
        assertEquals("integer", (addl["type"] as JsonPrimitive).content)
    }

    @Test fun mutuallyExclusiveEmitsAnyOf() {
        val schema = serializer<OneOfArgs>().toJsonSchema()
        dump("OneOfArgs", schema)
        val anyOf = schema["anyOf"] as JsonArray
        assertEquals(2, anyOf.size)
        // Each branch is a single-property object
        val branch0 = anyOf[0] as JsonObject
        assertEquals("object", (branch0["type"] as JsonPrimitive).content)
        val branchProps = branch0["properties"] as JsonObject
        assertEquals(1, branchProps.size)
    }

    @Test fun sealedEmitsOneOfVariants() {
        // Common behavior: sealed hierarchies produce a oneOf list. The per-variant
        // shape is reflection-dependent (JVM pulls field information; non-JVM falls back
        // to a name-only stub), so the full-shape assertion lives in jvmTest.
        val schema = serializer<Shape>().toJsonSchema()
        dump("Shape", schema)
        val oneOf = schema["oneOf"] as JsonArray
        assertTrue(oneOf.isNotEmpty(), "sealed should produce oneOf variants")
    }

    @Test fun hiddenAndReadOnlyStripped() {
        val schema = serializer<HiddenFieldArgs>().toJsonSchema()
        dump("HiddenFieldArgs", schema)
        val props = schema["properties"] as JsonObject
        assertTrue("visible" in props.keys)
        assertTrue("internal" !in props.keys, "@HideFromLlm should be stripped")
        assertTrue("computed" !in props.keys, "@LlmReadOnly should be stripped")
    }

    @Test fun descriptorWrapper() {
        val descriptor = LlmToolDescriptor(
            name = "get_weather",
            description = "Fetch current weather for a city",
            type = serializer<WeatherArgs>(),
        )
        val schema = descriptor.toJsonSchema()
        dump("LlmToolDescriptor.toJsonSchema", schema)
        assertEquals("object", (schema["type"] as JsonPrimitive).content)
    }
}
