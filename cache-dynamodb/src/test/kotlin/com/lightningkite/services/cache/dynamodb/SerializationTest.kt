// by Claude
package com.lightningkite.services.cache.dynamodb

import com.lightningkite.services.TestSettingContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.*
import org.junit.Test
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the DynamoDB serialization utilities in serialization.kt.
 *
 * These tests verify that Kotlin types can be correctly round-tripped through
 * DynamoDB's AttributeValue format via JSON intermediate representation.
 *
 * Test categories:
 * - Primitive types (Int, Long, Double, Float, Boolean, String, Char)
 * - Collection types (List, Set, Map)
 * - Nullable types
 * - Nested data classes
 * - Edge cases (empty collections, special numbers)
 */
class SerializationTest {

    private val context = TestSettingContext()

    // ===== Primitive Type Tests =====

    @Test
    fun `round trip Int`() {
        val serializer = Int.serializer()
        val value = 42
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip negative Int`() {
        val serializer = Int.serializer()
        val value = -123
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip Int max and min values`() {
        val serializer = Int.serializer()
        val maxResult = serializer.fromDynamo(serializer.toDynamo(Int.MAX_VALUE, context), context)
        assertEquals(Int.MAX_VALUE, maxResult)

        val minResult = serializer.fromDynamo(serializer.toDynamo(Int.MIN_VALUE, context), context)
        assertEquals(Int.MIN_VALUE, minResult)
    }

    @Test
    fun `round trip Long`() {
        val serializer = Long.serializer()
        val value = 9_223_372_036_854_775_000L // Near Long.MAX_VALUE
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip Double`() {
        val serializer = Double.serializer()
        val value = 3.14159265358979
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result, 0.0000000001)
    }

    @Test
    fun `round trip Float`() {
        val serializer = Float.serializer()
        val value = 2.71828f
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result, 0.0001f)
    }

    @Test
    fun `round trip Boolean true`() {
        val serializer = Boolean.serializer()
        val result = serializer.fromDynamo(serializer.toDynamo(true, context), context)
        assertEquals(true, result)
    }

    @Test
    fun `round trip Boolean false`() {
        val serializer = Boolean.serializer()
        val result = serializer.fromDynamo(serializer.toDynamo(false, context), context)
        assertEquals(false, result)
    }

    @Test
    fun `round trip String`() {
        val serializer = String.serializer()
        val value = "Hello, DynamoDB!"
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip empty String`() {
        val serializer = String.serializer()
        val value = ""
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip String with special characters`() {
        val serializer = String.serializer()
        val value = "Hello\nWorld\t\"quoted\" 日本語"
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip Char`() {
        val serializer = Char.serializer()
        val value = 'A'
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    // ===== Collection Type Tests =====

    @Test
    fun `round trip List of Strings`() {
        val serializer = ListSerializer(String.serializer())
        val value = listOf("apple", "banana", "cherry")
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip empty List`() {
        val serializer = ListSerializer(String.serializer())
        val value = emptyList<String>()
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip Set of Strings`() {
        val serializer = SetSerializer(String.serializer())
        val value = setOf("asdf", "fdsa")
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip Set of Ints`() {
        val serializer = SetSerializer(Int.serializer())
        val value = setOf(1, 2, 3, 4, 5)
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip Map of String to Int`() {
        val serializer = MapSerializer(String.serializer(), Int.serializer())
        val value = mapOf("one" to 1, "two" to 2, "three" to 3)
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip empty Map`() {
        val serializer = MapSerializer(String.serializer(), Int.serializer())
        val value = emptyMap<String, Int>()
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip nested List`() {
        val serializer = ListSerializer(ListSerializer(Int.serializer()))
        val value = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    // ===== Nullable Type Tests =====

    @Test
    fun `round trip nullable Int with value`() {
        val serializer = Int.serializer().nullable
        val value: Int? = 42
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip nullable Int with null`() {
        val serializer = Int.serializer().nullable
        val value: Int? = null
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip nullable String with value`() {
        val serializer = String.serializer().nullable
        val value: String? = "not null"
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip nullable String with null`() {
        val serializer = String.serializer().nullable
        val value: String? = null
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    // ===== Data Class Tests =====

    @Serializable
    data class SimpleData(
        val name: String,
        val count: Int,
        val active: Boolean
    )

    @Test
    fun `round trip simple data class`() {
        val serializer = SimpleData.serializer()
        val value = SimpleData("test", 42, true)
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Serializable
    data class DataWithDefaults(
        val required: String,
        val optional: Int = 10,
        val nullable: String? = null
    )

    @Test
    fun `round trip data class with defaults`() {
        val serializer = DataWithDefaults.serializer()
        val value = DataWithDefaults("required", 20, "present")
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip data class with defaults using default values`() {
        val serializer = DataWithDefaults.serializer()
        val value = DataWithDefaults("required")
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Serializable
    data class NestedData(
        val inner: SimpleData,
        val items: List<SimpleData>
    )

    @Test
    fun `round trip nested data class`() {
        val serializer = NestedData.serializer()
        val value = NestedData(
            inner = SimpleData("inner", 1, true),
            items = listOf(
                SimpleData("item1", 10, false),
                SimpleData("item2", 20, true)
            )
        )
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Serializable
    data class DataWithMap(
        val properties: Map<String, String>,
        val counts: Map<String, Int>
    )

    @Test
    fun `round trip data class with maps`() {
        val serializer = DataWithMap.serializer()
        val value = DataWithMap(
            properties = mapOf("key1" to "value1", "key2" to "value2"),
            counts = mapOf("a" to 1, "b" to 2)
        )
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    // ===== Edge Cases =====

    @Test
    fun `round trip zero`() {
        val serializer = Int.serializer()
        val result = serializer.fromDynamo(serializer.toDynamo(0, context), context)
        assertEquals(0, result)
    }

    @Test
    fun `round trip negative zero Double`() {
        val serializer = Double.serializer()
        val value = -0.0
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        // Note: -0.0 and 0.0 are equal in Kotlin/Java
        assertEquals(0.0, result, 0.0)
    }

    @Test
    fun `round trip very small Double`() {
        val serializer = Double.serializer()
        val value = Double.MIN_VALUE
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result, 0.0)
    }

    @Test
    fun `round trip Byte`() {
        val serializer = Byte.serializer()
        val value: Byte = 127
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    @Test
    fun `round trip Short`() {
        val serializer = Short.serializer()
        val value: Short = 32000
        val result = serializer.fromDynamo(serializer.toDynamo(value, context), context)
        assertEquals(value, result)
    }

    // ===== DynamoDB AttributeValue Type Verification =====

    @Test
    fun `Int serializes to number type`() {
        val serializer = Int.serializer()
        val dynamo = serializer.toDynamo(42, context)
        assertEquals(AttributeValue.Type.N, dynamo.type())
    }

    @Test
    fun `String serializes to string type`() {
        val serializer = String.serializer()
        val dynamo = serializer.toDynamo("test", context)
        assertEquals(AttributeValue.Type.S, dynamo.type())
    }

    @Test
    fun `Boolean serializes to bool type`() {
        val serializer = Boolean.serializer()
        val dynamo = serializer.toDynamo(true, context)
        assertEquals(AttributeValue.Type.BOOL, dynamo.type())
    }

    @Test
    fun `List serializes to list type`() {
        val serializer = ListSerializer(Int.serializer())
        val dynamo = serializer.toDynamo(listOf(1, 2, 3), context)
        assertEquals(AttributeValue.Type.L, dynamo.type())
    }

    @Test
    fun `Map serializes to map type`() {
        val serializer = MapSerializer(String.serializer(), Int.serializer())
        val dynamo = serializer.toDynamo(mapOf("a" to 1), context)
        assertEquals(AttributeValue.Type.M, dynamo.type())
    }

    @Test
    fun `data class serializes to map type`() {
        val serializer = SimpleData.serializer()
        val dynamo = serializer.toDynamo(SimpleData("test", 1, true), context)
        assertEquals(AttributeValue.Type.M, dynamo.type())
    }

    @Test
    fun `null serializes to null type`() {
        val serializer = Int.serializer().nullable
        val dynamo = serializer.toDynamo(null, context)
        assertEquals(AttributeValue.Type.NUL, dynamo.type())
    }

    // ===== Error Cases =====

    @Test
    fun `fromDynamo throws SerializationException for mismatched type`() {
        val serializer = Int.serializer()
        // Create a DynamoDB AttributeValue of type S (String) when we expect N (Number)
        val stringValue = AttributeValue.fromS("not a number")

        assertFailsWith<kotlinx.serialization.SerializationException> {
            serializer.fromDynamo(stringValue, context)
        }
    }
}
