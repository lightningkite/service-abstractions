package com.lightningkite.services.database

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer
import kotlin.test.*

/**
 * Regression tests proving bugs in VirtualSealed identified in code review.
 *
 * Tests that assert EXPECTED correct behavior will currently FAIL (the bug exists).
 * Tests that demonstrate BROKEN behavior will currently PASS (confirming the bug).
 *
 * Run with: ./gradlew :database-shared:jvmTest --tests "*.VirtualSealedBugTest"
 */
class VirtualSealedBugTest {

    // ── Shared test models ─────────────────────────────────────────────────────

    abstract class Pet

    @Serializable
    @SerialName("BugTest.Cat")
    data class Cat(val name: String) : Pet()

    // Standard Kotlin sealed class — uses PolymorphicKind.SEALED, not MySealedClassSerializer.
    // Subclass serialNames are explicit so the discriminator value is predictable.
    @Serializable
    sealed class Shape {
        @Serializable @SerialName("BugTest.Circle") data class Circle(val radius: Double) : Shape()
        @Serializable @SerialName("BugTest.Square") data class Square(val side: Double) : Shape()
    }

    @Serializable
    @SerialName("BugTest.Dog")
    data class Dog(val breed: String) : Pet()

    /**
     * Canine deliberately uses serialName ≠ baseName.
     * MySealedClassSerializer.Option will use baseName="Canine" while
     * serialName stays as this @SerialName value.
     */
    @Serializable
    @SerialName("BugTest.FullyQualifiedCanine")
    data class Canine(val breed: String) : Pet()

    private val json = Json { }

    // ── Bug 1: Wire format mismatch ────────────────────────────────────────────
    //
    // MySealedClassSerializer produces a single-key CLASS format:
    //   {"Cat":{"name":"Whiskers"}}
    //
    // VirtualSealed.Concrete produces a discriminator SEALED format:
    //   {"type":"BugTest.Cat","name":"Whiskers"}
    //
    // These are incompatible. If the admin system reads data stored by
    // MySealedClassSerializer using VirtualSealed.Concrete, it will fail.

    @Test
    fun `bug1 - MySealedClassSerializer encodes without a type discriminator key`() {
        val petSer = MySealedClassSerializer<Pet>(
            "BugTest.Pet",
            {
                listOf(
                    MySealedClassSerializer.Option(Cat.serializer(), "Cat", priority = 0) { it is Cat },
                    MySealedClassSerializer.Option(Dog.serializer(), "Dog", priority = 0) { it is Dog },
                )
            }
        )

        val encoded = json.encodeToString(petSer, Cat("Whiskers"))

        // MySealedClassSerializer wraps the value under the baseName key — no "type" field
        assertEquals("""{"Cat":{"name":"Whiskers"}}""", encoded,
            "MySealedClassSerializer should encode as single-key CLASS format")
        assertFalse(encoded.contains("\"type\""),
            "MySealedClassSerializer must NOT produce a 'type' discriminator")
    }

    @Test
    fun `bug1 - VirtualSealed Concrete cannot read MySealedClassSerializer output`() {
        val petSer = MySealedClassSerializer<Pet>(
            "BugTest.Pet2",
            {
                listOf(
                    MySealedClassSerializer.Option(Cat.serializer(), "Cat", priority = 0) { it is Cat },
                    MySealedClassSerializer.Option(Dog.serializer(), "Dog", priority = 0) { it is Dog },
                )
            }
        )

        // Real wire format from MySealedClassSerializer
        val mySealedJson = json.encodeToString(petSer, Cat("Whiskers"))
        // {"Cat":{"name":"Whiskers"}} — no "type" key

        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(petSer)
        val concrete = registry["BugTest.Pet2", arrayOf()] as VirtualSealed.Concrete

        // VirtualSealed.Concrete looks for {"type":"..."} — will throw SerializationException
        // because the MySealedClassSerializer output has no "type" key.
        assertFailsWith<SerializationException>(
            "VirtualSealed.Concrete must fail on MySealedClassSerializer format (no 'type' key)"
        ) {
            json.decodeFromString(concrete, mySealedJson)
        }
    }

    @Test
    fun `bug1 - VirtualSealed Concrete encodes differently from MySealedClassSerializer`() {
        val petSer = MySealedClassSerializer<Pet>(
            "BugTest.Pet3",
            {
                listOf(
                    MySealedClassSerializer.Option(Cat.serializer(), "BugTest.Cat", priority = 0) { it is Cat },
                )
            }
        )

        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(petSer)
        val concrete = registry["BugTest.Pet3", arrayOf()] as VirtualSealed.Concrete

        // Build a VirtualInstance representing Cat("Whiskers")
        val catStruct = concrete.optionStructs.first()
        val catConcrete = catStruct.Concrete(registry, arrayOf())
        val catInstance = catConcrete(mapOf(catStruct.fields.first() to "Whiskers"))

        val virtualJson = json.encodeToString(concrete, catInstance)
        val realJson = json.encodeToString(petSer, Cat("Whiskers"))

        // Prove formats differ — this is the root of the incompatibility
        assertNotEquals(virtualJson, realJson,
            "VirtualSealed.Concrete and MySealedClassSerializer must produce compatible JSON " +
            "but they produce:\n  Virtual: $virtualJson\n  Real: $realJson")
    }

    // ── Bug 2: baseName dropped ────────────────────────────────────────────────
    //
    // MySealedClassSerializer.Option.baseName is the human-readable key used
    // in the wire format AND in nameToIndex for lookups.
    //
    // When converting to VirtualSealedOption, only serializer.descriptor.serialName
    // is stored as option.name. If baseName ≠ serialName, the baseName is lost
    // and VirtualSealed.Concrete cannot look up values keyed by baseName.

    @Test
    fun `bug2 - VirtualSealedOption stores serialName not baseName`() {
        // Canine.serializer().descriptor.serialName = "BugTest.FullyQualifiedCanine"
        // baseName passed to Option = "Canine"  ← this is what MySealedClassSerializer uses
        val serializer = MySealedClassSerializer<Pet>(
            "BugTest.AnimalBox",
            {
                listOf(
                    MySealedClassSerializer.Option(Canine.serializer(), "Canine", priority = 0) { it is Canine }
                )
            }
        )

        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(serializer)

        val virtualType = registry.virtualTypes["BugTest.AnimalBox"]
        assertNotNull(virtualType, "VirtualSealed should be registered")
        assertTrue(virtualType is VirtualSealed, "Should be VirtualSealed")
        val option = (virtualType as VirtualSealed).options.first()

        // EXPECTED (correct): option.name == "Canine" (the baseName MySealedClassSerializer uses)
        // ACTUAL (buggy):     option.name == "BugTest.FullyQualifiedCanine" (serialName)
        //
        // This test currently FAILS, proving the bug.
        assertEquals(
            "Canine",
            option.name,
            "VirtualSealedOption.name should be the baseName 'Canine' " +
            "that MySealedClassSerializer uses, not serialName '${option.name}'"
        )
    }

    @Test
    fun `bug2 - VirtualSealed Concrete cannot decode when type key uses baseName`() {
        // Even if wire formats matched, baseName wouldn't be in nameToIndex
        val serializer = MySealedClassSerializer<Pet>(
            "BugTest.AnimalBox2",
            {
                listOf(
                    MySealedClassSerializer.Option(Canine.serializer(), "Canine", priority = 0) { it is Canine }
                )
            }
        )

        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(serializer)
        val concrete = registry["BugTest.AnimalBox2", arrayOf()] as VirtualSealed.Concrete

        // Try discriminator format using the baseName "Canine"
        // Should work since "Canine" is the name MySealedClassSerializer uses for this option.
        // Currently FAILS with "Unknown sealed subtype 'Canine'" because baseName is not in nameToIndex.
        val result = json.decodeFromString(concrete, """{"type":"Canine","breed":"Labrador"}""")
        assertNotNull(result, "Should decode successfully when type key uses baseName 'Canine'")
    }

    // ── Bug 3: Generic type arguments not propagated to option serializers ─────
    //
    // VirtualSealed.Concrete.optionStructs creates VirtualStruct with parameters=listOf()
    // and calls it.Concrete(registry, emptyArray()).
    // typeArguments is therefore empty, so type parameters like "T" cannot be resolved.
    //
    // For sealed class Box<T> { data class Full<T>(val value: T) },
    // accessing Box<String>.optionSerializers[0].descriptor fails because "T" is unresolved.

    @Test
    fun `bug3 - generic type argument T is lost in option serializers`() {
        val registry = SerializationRegistry(EmptySerializersModule())

        // Represents: sealed class Box<T> { data class Full<T>(val value: T) : Box<T>() }
        val virtualSealed = VirtualSealed(
            serialName = "BugTest.Box",
            annotations = listOf(),
            fields = listOf(),
            options = listOf(
                VirtualSealedOption(
                    name = "BugTest.Box.Full",
                    fields = listOf(
                        VirtualField(
                            index = 0,
                            name = "value",
                            type = VirtualTypeReference(
                                serialName = "T",          // type parameter reference
                                arguments = listOf(),
                                isNullable = false
                            ),
                            optional = false,
                            annotations = listOf()
                        )
                    ),
                    index = 0
                )
            ),
            parameters = listOf(VirtualTypeParameter("T"))
        )
        registry.register(virtualSealed)

        // Concrete with T=String — typeArguments should propagate "T" -> String.serializer()
        val concrete = registry["BugTest.Box", arrayOf(String.serializer())] as VirtualSealed.Concrete

        // EXPECTED: accessing descriptor resolves "T" to String — works fine
        // ACTUAL:   optionStructs creates VirtualStruct(parameters=listOf()) + Concrete(emptyArray())
        //           → typeArguments={} → "T" lookup fails → Exception thrown
        //
        // This test currently FAILS with an exception when accessing concrete.descriptor,
        // proving the type argument propagation bug.
        val descriptor = concrete.descriptor
        assertNotNull(descriptor, "Descriptor should be accessible when T=String is provided")

        // The Full option should resolve its "value" field to a String serializer
        val fullSerializer = concrete.optionSerializers.first()
        assertEquals(
            "kotlin.String",
            fullSerializer.descriptor.getElementDescriptor(0).serialName,
            "Field 'value: T' with T=String should resolve to kotlin.String, not fail"
        )
    }

    // ── Bug 4: VirtualSealed.Concrete does not implement KSerializerWithDefault ─
    //
    // VirtualStruct.Concrete implements KSerializerWithDefault<VirtualInstance>.
    // VirtualSealed.Concrete only implements KSerializer<Any?>.
    // This inconsistency means code that casts to KSerializerWithDefault to call
    // .default() will fail for sealed types.

    @Test
    fun `bug4 - VirtualSealed Concrete should implement KSerializerWithDefault like VirtualStruct Concrete`() {
        val registry = SerializationRegistry(EmptySerializersModule())

        val virtualSealed = VirtualSealed(
            serialName = "BugTest.SimpleSealed",
            annotations = listOf(),
            fields = listOf(),
            options = listOf(
                VirtualSealedOption(name = "BugTest.SimpleSealed.A", fields = listOf(), index = 0)
            ),
            parameters = listOf()
        )
        registry.register(virtualSealed)

        val sealedConcrete = registry["BugTest.SimpleSealed", arrayOf()] as VirtualSealed.Concrete

        // Sanity check: VirtualStruct.Concrete implements KSerializerWithDefault
        val structConcrete = VirtualStruct(
            serialName = "BugTest.SanityStruct",
            annotations = listOf(),
            fields = listOf(),
            parameters = listOf()
        ).Concrete(registry, arrayOf())
        assertTrue(
            structConcrete is KSerializerWithDefault<*>,
            "VirtualStruct.Concrete should implement KSerializerWithDefault (sanity check)"
        )

        // EXPECTED: VirtualSealed.Concrete also implements KSerializerWithDefault
        // ACTUAL:   it does not — this assertion currently FAILS, proving the bug
        assertTrue(
            sealedConcrete is KSerializerWithDefault<*>,
            "VirtualSealed.Concrete should implement KSerializerWithDefault " +
            "for consistency with VirtualStruct.Concrete"
        )
    }

    // ── Required: cross-serializer round-trip compatibility ────────────────────
    //
    // VirtualSealed.Concrete must interoperate with the original serializer in
    // both directions, because the admin system needs to:
    //
    //   READ:  DB row (serialized by real serializer) → VirtualInstance (via Concrete)
    //   WRITE: VirtualInstance (via Concrete) → DB row (read back by real serializer)
    //
    // Tests in this section assert the correct behavior and currently FAIL where
    // the bug causes incompatibility.

    // ─ MySealedClassSerializer direction ──────────────────────────────────────

    private fun buildPetSerializer(serialName: String) = MySealedClassSerializer<Pet>(
        serialName,
        {
            listOf(
                MySealedClassSerializer.Option(Cat.serializer(), "Cat", priority = 0) { it is Cat },
                MySealedClassSerializer.Option(Dog.serializer(), "Dog", priority = 0) { it is Dog },
            )
        }
    )

    /**
     * Real serializer writes a DB row → VirtualSealed.Concrete must be able to read it.
     * Currently FAILS (Bug 1 + Bug 2): no "type" key in MySealedClassSerializer output.
     */
    @Test
    fun `roundtrip - MySealedClassSerializer output can be read by VirtualSealed Concrete`() {
        val petSer = buildPetSerializer("BugTest.PetRT1")
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(petSer)
        val concrete = registry["BugTest.PetRT1", arrayOf()] as VirtualSealed.Concrete

        val originalJson = json.encodeToString(petSer, Cat("Whiskers"))
        // e.g. {"Cat":{"name":"Whiskers"}}

        val vi = json.decodeFromString(concrete, originalJson) as VirtualInstance
        assertEquals("Cat", vi.type.serialName.substringAfterLast('.'),
            "Deserialized VirtualInstance should be the Cat option")
        assertEquals("Whiskers", vi.values[0],
            "Cat.name field should round-trip correctly")
    }

    /**
     * VirtualSealed.Concrete writes a VirtualInstance → real serializer must be able to read it.
     * Currently FAILS (Bug 1): VirtualSealed.Concrete produces discriminator format,
     * MySealedClassSerializer expects single-key format.
     */
    @Test
    fun `roundtrip - VirtualSealed Concrete output can be read by MySealedClassSerializer`() {
        val petSer = buildPetSerializer("BugTest.PetRT2")
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(petSer)
        val concrete = registry["BugTest.PetRT2", arrayOf()] as VirtualSealed.Concrete

        // Build the Cat VirtualInstance manually
        val catOption = concrete.optionStructs.first { it.serialName.endsWith("Cat") }
        val catConcrete = catOption.Concrete(registry, arrayOf())
        val catInstance = catConcrete(mapOf(catOption.fields.first() to "Whiskers"))

        val virtualJson = json.encodeToString(concrete, catInstance)

        val decoded = json.decodeFromString(petSer, virtualJson)
        assertEquals(Cat("Whiskers"), decoded,
            "MySealedClassSerializer should be able to read VirtualSealed.Concrete output")
    }

    /**
     * Full round-trip: real serializer → VirtualSealed.Concrete → real serializer.
     * Currently FAILS (Bug 1).
     */
    @Test
    fun `roundtrip - MySealedClassSerializer full round-trip through VirtualSealed Concrete`() {
        val petSer = buildPetSerializer("BugTest.PetRT3")
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(petSer)
        val concrete = registry["BugTest.PetRT3", arrayOf()] as VirtualSealed.Concrete

        val original = Dog("Labrador")
        val originalJson = json.encodeToString(petSer, original)

        // Read as VirtualInstance, then write back and re-read as real type
        val vi = json.decodeFromString(concrete, originalJson) as VirtualInstance
        val reEncodedJson = json.encodeToString(concrete, vi)
        val decoded = json.decodeFromString(petSer, reEncodedJson)

        assertEquals(original, decoded,
            "Full round-trip through VirtualSealed.Concrete must preserve value")
    }

    // ─ Standard Kotlin sealed class (PolymorphicKind.SEALED) direction ─────────

    /**
     * Real sealed class serializer writes a DB row → VirtualSealed.Concrete must read it.
     */
    @Test
    fun `roundtrip - standard sealed class output can be read by VirtualSealed Concrete`() {
        val realSerializer = serializer<Shape>()
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(realSerializer)
        val concrete = registry[realSerializer.descriptor.serialName, arrayOf()] as VirtualSealed.Concrete

        val originalJson = json.encodeToString(realSerializer, Shape.Circle(3.0))
        // kotlinx.serialization produces: {"type":"BugTest.Circle","radius":3.0}

        val vi = json.decodeFromString(concrete, originalJson) as VirtualInstance
        assertEquals("BugTest.Circle", vi.type.serialName,
            "Deserialized VirtualInstance should be the Circle option")
        assertEquals(3.0, vi.values[0],
            "Circle.radius field should round-trip correctly")
    }

    /**
     * VirtualSealed.Concrete writes a VirtualInstance → real sealed class serializer must read it.
     */
    @Test
    fun `roundtrip - VirtualSealed Concrete output can be read by standard sealed class serializer`() {
        val realSerializer = serializer<Shape>()
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(realSerializer)
        val concrete = registry[realSerializer.descriptor.serialName, arrayOf()] as VirtualSealed.Concrete

        // Build the Circle VirtualInstance
        val circleOption = concrete.optionStructs.first { it.serialName == "BugTest.Circle" }
        val circleConcrete = circleOption.Concrete(registry, arrayOf())
        val circleInstance = circleConcrete(mapOf(circleOption.fields.first() to 3.0))

        val virtualJson = json.encodeToString(concrete, circleInstance)

        val decoded = json.decodeFromString(realSerializer, virtualJson)
        assertEquals(Shape.Circle(3.0), decoded,
            "Standard sealed class serializer should be able to read VirtualSealed.Concrete output")
    }

    /**
     * Full round-trip: standard sealed class serializer → VirtualSealed.Concrete → real serializer.
     */
    @Test
    fun `roundtrip - standard sealed class full round-trip through VirtualSealed Concrete`() {
        val realSerializer = serializer<Shape>()
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(realSerializer)
        val concrete = registry[realSerializer.descriptor.serialName, arrayOf()] as VirtualSealed.Concrete

        val original = Shape.Square(5.0)
        val originalJson = json.encodeToString(realSerializer, original)

        val vi = json.decodeFromString(concrete, originalJson) as VirtualInstance
        val reEncodedJson = json.encodeToString(concrete, vi)
        val decoded = json.decodeFromString(realSerializer, reEncodedJson)

        assertEquals(original, decoded,
            "Full round-trip through VirtualSealed.Concrete must preserve value")
    }
}
