@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.serializers

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Pet(
    val name: String,
    @WireId(2) val age: Int = 0,
    val nick: String? = "Buddy",
    val tags: List<String?> = emptyList(),
)

/** Like `PartialSerializer`: some of [Pet]'s fields, written in the map's order, with Pet's element annotations. */
object PartialPetSerializer : KSerializer<Map<String, Any?>> {
    private val pet = Pet.serializer().descriptor
    private val fieldSerializers = listOf(
        String.serializer(),
        Int.serializer(),
        String.serializer().nullable,
        ListSerializer(String.serializer().nullable),
    )
    override val descriptor = buildClassSerialDescriptor("PartialPet") {
        for (i in 0 until pet.elementsCount) {
            element(pet.getElementName(i), pet.getElementDescriptor(i), pet.getElementAnnotations(i), isOptional = true)
        }
    }

    override fun serialize(encoder: Encoder, value: Map<String, Any?>) = encoder.encodeStructure(descriptor) {
        for ((name, part) in value) {
            val index = descriptor.getElementIndex(name)
            @Suppress("UNCHECKED_CAST")
            encodeSerializableElement(descriptor, index, fieldSerializers[index] as KSerializer<Any?>, part)
        }
    }

    override fun deserialize(decoder: Decoder): Map<String, Any?> = decoder.decodeStructure(descriptor) {
        val out = HashMap<String, Any?>()
        while (true) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            out[descriptor.getElementName(index)] = decodeSerializableElement(descriptor, index, fieldSerializers[index])
        }
        out
    }
}

class KotlinWireFormatTest {
    @Serializable
    data class SmallPet(
        @WireId(1) val name: String,
        @WireId(2) val age: Int = 0,
        @WireId(3) val nick: String? = "Buddy",
        @WireId(4) val tags: List<String?> = emptyList(),
    )

    @Serializable
    enum class Color { Red, Green, @SerialName("blue") Blue }

    @Serializable
    sealed interface Shape

    @Serializable
    @SerialName("Circle")
    data class Circle(val radius: Double) : Shape

    @Serializable
    @SerialName("Square")
    data class Square(val side: Int, val color: Color = Color.Red) : Shape

    @Serializable
    @SerialName("Nothing")
    data object NoShape : Shape

    interface Animal

    @Serializable
    @SerialName("Dog")
    data class Dog(val name: String) : Animal

    @Serializable
    @SerialName("Cat")
    data class Cat(val lives: Int) : Animal

    @Serializable
    @JvmInline
    value class Meters(val value: Int)

    data class Money(val cents: Long)

    object MoneySerializer : KSerializer<Money> {
        override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.LONG)
        override fun serialize(encoder: Encoder, value: Money) = encoder.encodeLong(value.cents)
        override fun deserialize(decoder: Decoder) = Money(decoder.decodeLong())
    }

    @Serializable
    data object Singleton

    @Serializable
    data class Everything(
        val byte: Byte,
        val short: Short,
        val int: Int,
        val long: Long,
        val float: Float,
        val double: Double,
        val char: Char,
        val boolean: Boolean,
        val string: String,
        val color: Color,
        val shape: Shape,
        val shapes: List<Shape?>,
        val animal: Animal,
        val meters: Meters,
        val maybeMeters: Meters?,
        @Contextual val price: Money,
        val singleton: Singleton,
        val nested: List<List<Int>>,
        val petKeys: Map<Pet, Int?>,
        val nullableKeys: Map<String?, String>,
        val enumKeys: Map<Color, List<Shape>>,
    )

    @Serializable
    data class Nullables(
        val int: Int?,
        val string: String?,
        val list: List<Int?>?,
        val map: Map<String?, Int?>?,
        val pet: Pet?,
        val lists: List<List<Int?>?>,
        val color: Color?,
        val shape: Shape?,
    )

    @Serializable
    class Bytes(val bytes: ByteArray, val maybe: ByteArray?)

    val module = SerializersModule {
        contextual(Money::class, MoneySerializer)
        polymorphic(Animal::class) {
            subclass(Dog::class)
            subclass(Cat::class)
        }
    }
    val format = KotlinWireFormat(module)

    private inline fun <reified T> roundTrip(value: T): ByteArray {
        val serializer = module.serializer<T>()
        val bytes = format.encodeToByteArray(serializer, value)
        assertEquals(value, format.decodeFromByteArray(serializer, bytes))
        return bytes
    }

    private fun hex(text: String) = text.replace(" ", "").hexToByteArray()

    // The spec's example (plans/kotlin-bytes-format-v2.md).  If this changes, the wire format changed.
    @Test
    fun goldenPet() {
        val bytes = roundTrip(Pet(name = "Rex", age = 3, nick = null, tags = listOf("a", null)))
        assertContentEquals(hex("b2deb72e 03526578 10 06 f3b6b52c 82badd25 04 01016100"), bytes)
    }

    @Test
    fun goldenSmallPet() {
        val bytes = roundTrip(SmallPet(name = "Rex", age = 3, nick = null, tags = listOf("a", null)))
        assertContentEquals(hex("0a 03526578 10 06 1b 22 04 01016100"), bytes)
    }

    @Test
    fun sizes() {
        val pet = Pet(name = "Rex", age = 3, nick = null, tags = listOf("a", null))
        val old = OldKotlinBytesFormat(EmptySerializersModule()).encodeToByteArray(Pet.serializer(), pet)
        assertContentEquals(hex("00 03526578 01 00000003 02 00 03 00000002 01 00 0161 00"), old)
        assertEquals(22, old.size)
        assertEquals(23, format.encodeToByteArray(Pet.serializer(), pet).size)
        assertEquals(14, format.encodeToByteArray(SmallPet.serializer(), SmallPet("Rex", 3, null, listOf("a", null))).size)
    }

    @Serializable
    data class KeySizes(@WireId(1) val one: Int, @WireId(15) val fifteen: Int, @WireId(16) val sixteen: Int, @WireId(2047) val max2: Int)

    @Test
    fun wireIdKeysAreOneOrTwoBytes() {
        val bytes = roundTrip(KeySizes(0, 0, 0, 0))
        assertContentEquals(hex("08 00 78 00 8001 00 f87f 00"), bytes)
    }

    @Serializable
    data class Named(val a: Int, val longerName: Int, val x: Int, @SerialName("com.example.Very.long.serial_name") val y: Int)

    @Test
    fun hashedKeysAreFourBytes() {
        val bytes = roundTrip(Named(0, 0, 0, 0))
        assertEquals(4 * (4 + 1), bytes.size)
    }

    @Test
    fun zigzagIntegers() {
        assertContentEquals(hex("00"), roundTrip(0))
        assertContentEquals(hex("01"), roundTrip(-1))
        assertContentEquals(hex("02"), roundTrip(1))
        assertContentEquals(hex("ffffffff0f"), roundTrip(Int.MIN_VALUE))
        assertContentEquals(hex("ffffffffffffffffff01"), roundTrip(Long.MIN_VALUE))
        // Elements of a byte list are raw bytes, and the top-level list has no length.
        assertContentEquals(hex("80ff7f"), roundTrip(listOf<Byte>(-128, -1, 127)))
        assertContentEquals(hex("03 80ff7f"), roundTrip(listOf(listOf<Byte>(-128, -1, 127))))
    }

    @Test
    fun primitives() {
        for (value in listOf(Byte.MIN_VALUE, -1, 0, 1, Byte.MAX_VALUE)) roundTrip(value)
        for (value in listOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)) roundTrip(value)
        for (value in listOf(Int.MIN_VALUE, -300, -1, 0, 1, 300, Int.MAX_VALUE)) roundTrip(value)
        for (value in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) roundTrip(value)
        for (value in listOf(Float.NaN, -0f, 0f, Float.MIN_VALUE, Float.MAX_VALUE, Float.NEGATIVE_INFINITY)) roundTrip(value)
        for (value in listOf(Double.NaN, -0.0, 0.0, Double.MIN_VALUE, Double.MAX_VALUE, Double.POSITIVE_INFINITY)) roundTrip(value)
        for (value in listOf('\u0000', 'a', 'é', '￿')) roundTrip(value)
        roundTrip(true)
        roundTrip(false)
        for (value in listOf("", "a", "héllo", "INF–03–026 C", "😀 emoji")) roundTrip(value)
        roundTrip("x".repeat(200_000))
    }

    @Test
    fun everything() {
        val value = Everything(
            byte = -5, short = -300, int = -70000, long = Long.MIN_VALUE, float = 1.5f, double = -2.25,
            char = 'é', boolean = true, string = "s", color = Color.Blue,
            shape = Circle(1.0), shapes = listOf(Square(2, Color.Green), null, NoShape),
            animal = Dog("Rex"), meters = Meters(-3), maybeMeters = Meters(4), price = Money(1999),
            singleton = Singleton, nested = listOf(listOf(), listOf(1, -2), listOf(3)),
            petKeys = mapOf(Pet("a") to 1, Pet("b", tags = listOf(null)) to null),
            nullableKeys = mapOf(null to "n", "k" to "v"),
            enumKeys = mapOf(Color.Red to listOf(NoShape), Color.Blue to listOf()),
        )
        roundTrip(value)
        roundTrip(value.copy(maybeMeters = null, animal = Cat(9)))
        roundTrip(Singleton)
        assertContentEquals(hex(""), format.encodeToByteArray(Singleton.serializer(), Singleton))
        roundTrip<Shape>(NoShape)
        val animal = PolymorphicSerializer(Animal::class)
        assertEquals<Animal>(Cat(3), format.decodeFromByteArray(animal, format.encodeToByteArray(animal, Cat(3))))
    }

    @Test
    fun nullableEverywhere() {
        roundTrip(Nullables(null, null, null, null, null, listOf(null), null, null))
        roundTrip(
            Nullables(
                int = 0, string = "", list = listOf(null, 1, null), map = mapOf(null to null, "a" to 1, "b" to null),
                pet = Pet("p", nick = null), lists = listOf(null, listOf(null), listOf()), color = Color.Green,
                shape = Square(1),
            )
        )
        roundTrip<Pet?>(null)
        roundTrip<Pet?>(Pet("x"))
        roundTrip<List<String?>?>(listOf(null))
    }

    @Test
    fun byteArrays() {
        val value = Bytes(byteArrayOf(-128, 0, 127), null)
        val bytes = format.encodeToByteArray(Bytes.serializer(), value)
        val decoded = format.decodeFromByteArray(Bytes.serializer(), bytes)
        assertContentEquals(value.bytes, decoded.bytes)
        assertEquals(null, decoded.maybe)
        val big = ByteArray(100_000) { it.toByte() }
        val decodedBig = format.decodeFromByteArray(Bytes.serializer(), format.encodeToByteArray(Bytes.serializer(), Bytes(big, big)))
        assertContentEquals(big, decodedBig.bytes)
        assertContentEquals(big, decodedBig.maybe)
    }

    @Test
    fun partialWithOutOfOrderParts() {
        val parts: Map<String, Any?> = linkedMapOf("tags" to listOf("a", null), "nick" to null, "age" to 3, "name" to "Rex")
        val bytes = format.encodeToByteArray(PartialPetSerializer, parts)
        assertEquals(parts, format.decodeFromByteArray(PartialPetSerializer, bytes))
        // The parts carry Pet's ids (including @WireId), so a whole Pet reads them in any order.
        assertEquals(Pet("Rex", 3, null, listOf("a", null)), format.decodeFromByteArray(Pet.serializer(), bytes))
        val some: Map<String, Any?> = linkedMapOf("nick" to null)
        assertEquals(some, format.decodeFromByteArray(PartialPetSerializer, format.encodeToByteArray(PartialPetSerializer, some)))
    }

    @Test
    fun nullIsNotAbsent() {
        roundTrip(Pet("Rex", nick = null))
        val defaulted = roundTrip(Pet("Rex"))
        assertEquals(Pet("Rex", nick = "Buddy"), format.decodeFromByteArray(Pet.serializer(), defaulted))
        assertContentEquals(hex("b2deb72e 03526578"), defaulted)
    }

    @Serializable
    data class Defaulted(val v: Int = 5)

    @Serializable
    data class EncodesDefault(@EncodeDefault val v: Int = 5)

    @Test
    fun defaultsAreOmittedUnlessEncodeDefault() {
        assertContentEquals(hex(""), roundTrip(Defaulted()))
        assertEquals(5, roundTrip(EncodesDefault()).size)
    }

    // Evolution: classes sharing a name are versions of one schema.
    @Serializable
    data class InnerV1(val a: Int = 0)

    @Serializable
    data class InnerV2(
        val a: Int = 0,
        val b: Double = 0.0,
        val c: Map<String, Int> = mapOf(),
        val d: String? = "d",
        val f: Float = 0f,
        val n: Long = 0,
        val pets: List<Pet> = listOf(),
    )

    @Serializable
    data class DocV1(val title: String, val inner: InnerV1 = InnerV1(), val after: Int = 0)

    @Serializable
    data class DocV2(
        val added: String = "x",
        val title: String,
        val inner: InnerV2 = InnerV2(),
        val extra: List<Pet> = listOf(),
        val after: Int = 0,
    )

    @Serializable
    data class DocReordered(val after: Int = 0, val inner: InnerV1 = InnerV1(), val title: String)

    @Serializable
    data class DocRenamedKeepingSerialName(@SerialName("title") val heading: String, val after: Int = 0)

    @Serializable
    data class DocRenamed(val heading: String = "none", val after: Int = 0)

    @Serializable
    data class DocTitleInt(val title: Int)

    @Serializable
    data class DocNullableTitle(val title: String?, val after: Int? = 0)

    private inline fun <reified A, reified B> reencode(value: A): B =
        format.decodeFromByteArray(module.serializer<B>(), format.encodeToByteArray(module.serializer<A>(), value))

    @Test
    fun oldReaderSkipsNewFieldsIncludingNested() {
        val new = DocV2(
            added = "y", title = "t",
            inner = InnerV2(a = 1, b = 2.5, c = mapOf("k" to 1), d = null, f = 1f, n = -9, pets = listOf(Pet("p"))),
            extra = listOf(Pet("Rex", tags = listOf(null))), after = 7,
        )
        assertEquals(DocV1("t", InnerV1(1), 7), reencode<DocV2, DocV1>(new))
    }

    @Test
    fun newReaderUsesDefaultsForOldData() {
        assertEquals(DocV2(title = "t", inner = InnerV2(a = 1), after = 7), reencode<DocV1, DocV2>(DocV1("t", InnerV1(1), 7)))
    }

    @Test
    fun reorderedFields() {
        assertEquals(DocReordered(7, InnerV1(1), "t"), reencode<DocV1, DocReordered>(DocV1("t", InnerV1(1), 7)))
        assertEquals(DocV1("t", InnerV1(1), 7), reencode<DocReordered, DocV1>(DocReordered(7, InnerV1(1), "t")))
    }

    @Test
    fun renames() {
        assertEquals(DocRenamedKeepingSerialName("t", 7), reencode<DocV1, DocRenamedKeepingSerialName>(DocV1("t", after = 7)))
        // Without @SerialName a rename is a removal and an addition.
        assertEquals(DocRenamed("none", 7), reencode<DocV1, DocRenamed>(DocV1("t", after = 7)))
    }

    @Test
    fun nullabilityAndTypeChanges() {
        assertEquals(DocNullableTitle("t", 7), reencode<DocV1, DocNullableTitle>(DocV1("t", after = 7)))
        assertEquals(DocV1("t"), reencode<DocNullableTitle, DocV1>(DocNullableTitle("t")))
        assertFailsWith<SerializationException> { reencode<DocNullableTitle, DocV1>(DocNullableTitle(null)) }
        assertFailsWith<SerializationException> { reencode<DocV1, DocTitleInt>(DocV1("t")) }
        assertFailsWith<MissingFieldException> { reencode<DocRenamed, DocV1>(DocRenamed("h")) }
    }

    @Serializable
    data class Wide(val v: Long, val c: Color)

    @Serializable
    data class Narrow(val v: Int, val c: Color)

    @Test
    fun integerWidthChanges() {
        assertEquals(Wide(-5, Color.Red), reencode<Narrow, Wide>(Narrow(-5, Color.Red)))
        assertEquals(Narrow(-5, Color.Red), reencode<Wide, Narrow>(Wide(-5, Color.Red)))
        assertFailsWith<SerializationException> { reencode<Wide, Narrow>(Wide(Long.MAX_VALUE, Color.Red)) }
    }

    @Serializable
    data class Duplicate(@WireId(1) val a: Int, @WireId(1) val b: Int)

    @Serializable
    data class ZeroId(@WireId(0) val a: Int)

    @Test
    fun invalidWireIdsThrow() {
        assertFailsWith<SerializationException> { format.encodeToByteArray(Duplicate.serializer(), Duplicate(1, 2)) }
        assertFailsWith<SerializationException> { format.decodeFromByteArray(Duplicate.serializer(), hex("00")) }
        assertFailsWith<SerializationException> { format.encodeToByteArray(ZeroId.serializer(), ZeroId(1)) }
    }

    @Test
    fun malformedInputThrows() {
        val golden = format.encodeToByteArray(Pet.serializer(), Pet(name = "Rex", age = 3, nick = null, tags = listOf("a", null)))
        // The top-level class has no length, so a cut between its fields reads as those fields being absent.
        val decodable = golden.indices.filter { size ->
            try {
                format.decodeFromByteArray(Pet.serializer(), golden.copyOf(size))
                true
            } catch (e: SerializationException) {
                false
            }
        }
        assertEquals(listOf(8, 10, 14), decodable)
        val nested = format.encodeToByteArray(ListSerializer(Pet.serializer()), listOf(Pet("Rex", 3, null, listOf("a", null))))
        // Nested values have lengths, so any cut inside one is caught.
        for (size in 1 until nested.size) {
            assertFailsWith<SerializationException>("truncated to $size") {
                format.decodeFromByteArray(ListSerializer(Pet.serializer()), nested.copyOf(size))
            }
        }
        assertFailsWith<SerializationException> { format.decodeFromByteArray(Pet.serializer(), golden + 0) }
        // A huge length is rejected before anything is allocated.
        assertFailsWith<SerializationException> { format.decodeFromByteArray(String.serializer(), hex("ffffffff0f 41")) }
        assertFailsWith<SerializationException> { format.decodeFromByteArray(String.serializer(), hex("ffffffffffffffffff01")) }
        assertFailsWith<SerializationException> { format.decodeFromByteArray(Long.serializer(), hex("8080808080808080808000")) }
        assertFailsWith<SerializationException> { format.decodeFromByteArray(Boolean.serializer(), hex("02")) }
        assertFailsWith<SerializationException> { format.decodeFromByteArray(String.serializer().nullable, hex("02 00")) }
        // The same field twice.
        assertFailsWith<SerializationException> {
            format.decodeFromByteArray(Pet.serializer(), hex("b2deb72e 03526578 b2deb72e 03526578"))
        }
        // NULL for a non-null field.
        assertFailsWith<SerializationException> { format.decodeFromByteArray(Pet.serializer(), hex("b3deb72e")) }
        assertFailsWith<SerializationException> { format.decodeFromByteArray(Color.serializer(), hex("06 507572706c65")) }
        val animal = PolymorphicSerializer(Animal::class)
        val cat = format.encodeToByteArray(animal, Cat(1))
        assertFailsWith<SerializationException> { KotlinWireFormat(EmptySerializersModule()).decodeFromByteArray(animal, cat) }
    }

    @Serializable
    @JvmInline
    value class UserId(val raw: Uuid)

    object UuidAsStringSerializer : KSerializer<Uuid> {
        override val descriptor = PrimitiveSerialDescriptor("test.UuidAsString", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: Uuid) = encoder.encodeString(value.toString())
        override fun deserialize(decoder: Decoder) = Uuid.parse(decoder.decodeString())
    }

    @Serializable
    data class Session(
        val id: Uuid,
        val at: Instant,
        @Contextual val contextualId: Uuid = Uuid.NIL,
        val maybeId: Uuid? = null,
        val maybeAt: Instant? = null,
        val ids: List<Uuid?> = listOf(),
        val byTime: Map<Instant, Uuid?> = mapOf(),
        val byId: Map<Uuid, Instant?> = mapOf(),
        val user: UserId? = null,
        @Serializable(UuidAsStringSerializer::class) val stringId: Uuid? = null,
    )

    @Serializable
    data class StringSession(val id: String, val at: String)

    private val uuid = Uuid.parse("01234567-89ab-cdef-0123-456789abcdef")
    private val now = Instant.fromEpochSeconds(1_763_000_000, 123_456_789)

    @Test
    fun goldenUuidAndInstant() {
        assertContentEquals(hex("10 0123456789abcdef0123456789abcdef"), roundTrip(uuid))
        assertContentEquals(hex("02 02 05"), roundTrip(Instant.fromEpochSeconds(1, 5)))
        assertContentEquals(hex("02 01 00"), roundTrip(Instant.fromEpochSeconds(-1)))
        assertContentEquals(hex("09 80fba9910d 959aef3a"), roundTrip(now))
    }

    @Test
    fun uuidAndInstantRoundTrip() {
        val instants = listOf(
            Instant.DISTANT_PAST, Instant.DISTANT_FUTURE, Instant.fromEpochSeconds(Long.MIN_VALUE),
            Instant.fromEpochSeconds(Long.MAX_VALUE), Instant.fromEpochSeconds(0, 999_999_999), now,
        )
        for (instant in instants) roundTrip(instant)
        for (id in listOf(Uuid.NIL, uuid, Uuid.random())) roundTrip(id)
        roundTrip(Session(uuid, now))
        roundTrip(
            Session(
                id = uuid, at = Instant.DISTANT_PAST, contextualId = Uuid.random(), maybeId = Uuid.NIL,
                maybeAt = Instant.DISTANT_FUTURE, ids = listOf(null, uuid),
                byTime = mapOf(now to null, Instant.DISTANT_PAST to uuid), byId = mapOf(uuid to null, Uuid.NIL to now),
                user = UserId(uuid), stringId = uuid,
            )
        )
        roundTrip<Instant?>(null)
        roundTrip<List<Instant?>>(listOf(null, now))
    }

    @Test
    fun onlyTheDefaultSerializersAreCompact() {
        assertEquals(37, format.encodeToByteArray(UuidAsStringSerializer, uuid).size)
        val withContextual = format.encodeToByteArray(Session.serializer(), Session(uuid, now, contextualId = uuid))
        val without = format.encodeToByteArray(Session.serializer(), Session(uuid, now))
        // @Contextual with nothing registered falls back to Uuid's default serializer.
        assertEquals(4 + 1 + 16, withContextual.size - without.size)
    }

    @Test
    fun malformedUuidAndInstantThrow() {
        assertFailsWith<SerializationException> { format.decodeFromByteArray(Uuid.serializer(), hex("0f 0123456789abcdef0123456789abcd")) }
        assertFailsWith<SerializationException> { format.decodeFromByteArray(Instant.serializer(), hex("06 02 8094ebdc03")) }
        assertFailsWith<SerializationException> { format.decodeFromByteArray(Instant.serializer(), hex("03 02 05 00")) }
        assertFailsWith<SerializationException> { format.decodeFromByteArray(Instant.serializer(), hex("0b feffffffffffffffff01 00")) }
    }

    @Test
    fun uuidAndInstantSizes() {
        val wire = format.encodeToByteArray(Session.serializer(), Session(uuid, now)).size
        val asStrings = format.encodeToByteArray(StringSession.serializer(), StringSession(uuid.toString(), now.toString())).size
        val old = OldKotlinBytesFormat(module).encodeToByteArray(Session.serializer(), Session(uuid, now)).size
        println("Session(id, at): wire $wire, wire with string Uuid/Instant $asStrings, old $old")
        assertEquals(4 + 1 + 16 + 4 + 1 + 9, wire)
        assertTrue(asStrings > wire + 30)
    }

    @Test
    fun mutatedInputDecodesOrThrowsSerializationException() {
        val golden = format.encodeToByteArray(Pet.serializer(), Pet(name = "Rex", age = 3, nick = null, tags = listOf("a", null)))
        for (index in golden.indices) {
            for (replacement in 0..255) {
                val mutated = golden.copyOf().also { it[index] = replacement.toByte() }
                try {
                    format.decodeFromByteArray(Pet.serializer(), mutated)
                } catch (e: SerializationException) {
                    assertTrue(e.message != null)
                }
            }
        }
    }
}
