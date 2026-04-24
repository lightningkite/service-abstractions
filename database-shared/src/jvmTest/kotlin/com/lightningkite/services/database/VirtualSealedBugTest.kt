package com.lightningkite.services.database

import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.*

/**
 * Tests for VirtualSealed registration and round-trip serialization compatibility.
 *
 * "round-trip" means: data written by the real kotlinx.serialization sealed-class
 * serializer can be read by VirtualSealed.Concrete, and vice versa.  This is
 * required for the admin system to read and write DB records containing sealed fields.
 *
 * Run with: ./gradlew :database-shared:jvmTest --tests "*.VirtualSealedBugTest"
 */
@OptIn(ExperimentalUnsignedTypes::class)
class VirtualSealedBugTest {

    // ── Test models ────────────────────────────────────────────────────────────

    @Serializable
    sealed class Shape {
        @Serializable
        @SerialName("BugTest.Circle")
        data class Circle(val radius: Double) : Shape()
        @Serializable
        @SerialName("BugTest.Square")
        data class Square(val side: Double) : Shape()
    }

    // Generic sealed class to exercise type-parameter propagation
    @Serializable
    sealed class Box<T> {
        @Serializable
        @SerialName("BugTest.Box.Full")
        data class Full<T>(val value: T) : Box<T>()
        @Serializable
        @SerialName("BugTest.Box.Empty")
        class Empty<T> : Box<T>()
    }

    private val json = Json

    // ── Registration ───────────────────────────────────────────────────────────

    @Test
    fun `sealed class registers as VirtualSealed`() {
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(serializer<Shape>())

        val vt = registry.virtualTypes[serializer<Shape>().descriptor.serialName]
        assertNotNull(vt, "VirtualSealed should be registered")
        assertTrue(vt is VirtualSealed)
    }

    @Test
    fun `subclasses register as separate VirtualStructs`() {
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(serializer<Shape>())

        assertNotNull(registry.virtualTypes["BugTest.Circle"], "Circle should be registered")
        assertNotNull(registry.virtualTypes["BugTest.Square"], "Square should be registered")
        assertTrue(registry.virtualTypes["BugTest.Circle"] is VirtualStruct)
        assertTrue(registry.virtualTypes["BugTest.Square"] is VirtualStruct)
    }

    @Test
    fun `VirtualSealed options reference subclass types by name`() {
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(serializer<Shape>())

        val vs = registry.virtualTypes[serializer<Shape>().descriptor.serialName] as VirtualSealed
        val names = vs.options.map { it.name }.toSet()
        assertEquals(setOf("BugTest.Circle", "BugTest.Square"), names)

        vs.options.forEach { opt ->
            assertEquals(
                opt.name, opt.type.serialName,
                "Option type should reference the subclass by its serialName"
            )
        }
    }

    // ── serializableOptions extraction ─────────────────────────────────────────

    @Test
    fun `serializableOptions extracts options from real sealed class serializer`() {
        val ser = serializer<Shape>()
        val opts = ser.serializableOptions
        assertNotNull(opts, "serializableOptions should not be null for sealed class")
        assertEquals(2, opts.size)
        val names = opts.map { it.name }.toSet()
        assertEquals(setOf("BugTest.Circle", "BugTest.Square"), names)
    }

    @Test
    fun `serializableOptions returns null for non-sealed serializer`() {
        assertNull(Int.serializer().serializableOptions)
        assertNull(String.serializer().serializableOptions)
    }

    // ── Round-trip: standard Kotlin sealed class ───────────────────────────────
    //
    // VirtualSealed.Concrete must interoperate with the kotlinx.serialization
    // sealed-class format in both directions.

    @Test
    fun `roundtrip - real sealed JSON can be decoded by VirtualSealed Concrete`() {
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(serializer<Shape>())
        val concrete = registry[serializer<Shape>().descriptor.serialName, arrayOf()] as VirtualSealed.Concrete

        val realJson = json.encodeToString(serializer<Shape>(), Shape.Circle(3.0))
        // kotlinx.serialization produces: {"type":"BugTest.Circle","radius":3.0}

        val vi = json.decodeFromString(concrete, realJson)
        assertEquals("BugTest.Circle", vi.option.name)
        val circleInstance = vi.value as VirtualInstance
        assertEquals(3.0, circleInstance.values[0])
    }

    @Test
    fun `roundtrip - VirtualSealed Concrete output can be decoded by real sealed serializer`() {
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(serializer<Shape>())
        val concrete = registry[serializer<Shape>().descriptor.serialName, arrayOf()] as VirtualSealed.Concrete

        // Build a VirtualSealedInstance for Circle(3.0)
        val circleVt = registry.virtualTypes["BugTest.Circle"] as VirtualStruct
        val circleConcrete = circleVt.Concrete(registry, arrayOf())
        val circleInstance = circleConcrete(mapOf(circleVt.fields.first() to 3.0))
        val circleOption = concrete.sealed.options.first { it.name == "BugTest.Circle" }
        val vi = VirtualSealedInstance(circleOption, circleInstance)

        val virtualJson = json.encodeToString(concrete, vi)

        val decoded = json.decodeFromString(serializer<Shape>(), virtualJson)
        assertEquals(Shape.Circle(3.0), decoded)
    }

    @Test
    fun `roundtrip - full round-trip through VirtualSealed Concrete preserves value`() {
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(serializer<Shape>())
        val concrete = registry[serializer<Shape>().descriptor.serialName, arrayOf()] as VirtualSealed.Concrete

        listOf(Shape.Circle(3.0), Shape.Square(5.0)).forEach { original ->
            val realJson = json.encodeToString(serializer<Shape>(), original)
            val vi = json.decodeFromString(concrete, realJson)
            val reEncoded = json.encodeToString(concrete, vi)
            val decoded = json.decodeFromString(serializer<Shape>(), reEncoded)
            assertEquals(original, decoded, "Full round-trip must preserve $original")
        }
    }

    // ── Remaining open bug ─────────────────────────────────────────────────────

    /**
     * VirtualSealed.Concrete implements KSerializer<VirtualSealedInstance> but not
     * KSerializerWithDefault, unlike VirtualStruct.Concrete which does.
     * Code that calls .default() via KSerializerWithDefault will fail for sealed types.
     * This test currently FAILS, proving the gap remains.
     */
    @Test
    fun `bug - VirtualSealed Concrete should implement KSerializerWithDefault`() {
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(serializer<Shape>())
        val concrete = registry[serializer<Shape>().descriptor.serialName, arrayOf()] as VirtualSealed.Concrete

        // Sanity: VirtualStruct.Concrete does implement it
        val circleVt = registry.virtualTypes["BugTest.Circle"] as VirtualStruct
        assertTrue(
            circleVt.Concrete(registry, arrayOf()) is KSerializerWithDefault<*>,
            "VirtualStruct.Concrete should implement KSerializerWithDefault (sanity check)"
        )

        // VirtualSealed.Concrete should too — currently FAILS
        assertTrue(
            concrete is KSerializerWithDefault<*>,
            "VirtualSealed.Concrete should implement KSerializerWithDefault"
        )
    }
}
