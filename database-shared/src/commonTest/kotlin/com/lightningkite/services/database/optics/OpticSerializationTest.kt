package com.lightningkite.services.database.optics

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.*
import com.lightningkite.services.database.optics.legacy.*
import com.lightningkite.services.database.optics.legacy.DataClassPath
import com.lightningkite.services.database.optics.legacy.DataClassPathSelf
import com.lightningkite.services.database.optics.legacy.DataClassPathSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.*

@Queryable
data class OpticNullableOuter(val inner: OpticNullableInner = OpticNullableInner())

@Queryable
data class OpticNullableInner(val value: String? = null)

class OpticSerializationTest {
    private val serializer = AnyOpticSerializer(LargeTestModel.serializer())
    private val oldSerializer = DataClassPathSerializer(LargeTestModel.serializer())
    private val root = DataClassPathSelf(LargeTestModel.serializer())

    private fun encode(optic: AnyOptic<LargeTestModel>): String = Json.encodeToString(serializer, optic).removeSurrounding("\"")
    private fun decode(path: String): Optic<LargeTestModel, *> = Json.decodeFromString(serializer, "\"$path\"").coerceToOptic()

    // Claude: each optic paired with the DataClassPath it replaces.
    private val equivalents: List<Pair<DataClassPath<LargeTestModel, *>, Optic<LargeTestModel, *>>> = listOf(
        root to IdentityLens(LargeTestModel.serializer()),
        root[LargeTestModel.int] to LargeTestModel.int,
        root[LargeTestModel.embedded][ClassUsedForEmbedding.value1] to LargeTestModel.embedded + ClassUsedForEmbedding.value1,
        root[LargeTestModel.embeddedNullable].notNull to LargeTestModel.embeddedNullable.notNull,
        root[LargeTestModel.embeddedNullable].notNull[ClassUsedForEmbedding.value1] to
            LargeTestModel.embeddedNullable.notNull + ClassUsedForEmbedding.value1,
        root[LargeTestModel.list].elements to LargeTestModel.list.elements,
        root[LargeTestModel.listEmbedded].elements[ClassUsedForEmbedding.value1] to
            LargeTestModel.listEmbedded.elements + ClassUsedForEmbedding.value1,
        root[LargeTestModel.setEmbedded].elements[ClassUsedForEmbedding.value2] to
            LargeTestModel.setEmbedded.elements + ClassUsedForEmbedding.value2,
        root[LargeTestModel.listNullable].notNull.elements to LargeTestModel.listNullable.notNull.elements,
    )

    @Test
    fun opticsSerializeLikeDataClassPaths() {
        for ((old, new) in equivalents) assertEquals(old.toString(), encode(new))
    }

    @Test
    fun dataClassPathStringsDeserializeToOptics() {
        for ((old, new) in equivalents) {
            val decoded = decode(old.toString())
            assertEquals(new, decoded)
            assertEquals(old.properties, decoded.fields)
        }
    }

    @Test
    fun serializedOpticsDeserializeAsDataClassPaths() {
        // Claude: compared as strings, since DataClassPathSerializer reads every '*' as a list, even on a set.
        for ((old, new) in equivalents) assertEquals(old.toString(), oldSerializer.fromString(encode(new)).toString())
    }

    @Test
    fun wrappersAfterNestedFieldsWrapTheWholePath() {
        val serializer = AnyOpticSerializer(OpticNullableOuter.serializer())
        val optic = (OpticNullableOuter.inner + OpticNullableInner.value).notNull
        val string = Json.encodeToString(serializer, optic)
        assertEquals("\"inner.value?\"", string)
        assertEquals(optic, Json.decodeFromString(serializer, string))
    }

    @Test
    fun questionMarkBeforeNextStepIsOptional() {
        assertEquals(LargeTestModel.embeddedNullable.notNull + ClassUsedForEmbedding.value1, decode("embeddedNullable.value1"))
        assertEquals(LargeTestModel.listNullable.notNull.elements, decode("listNullable.*"))
        assertEquals(LargeTestModel.embeddedNullable, decode("embeddedNullable"))
    }

    @Test
    fun elementsOfSetIsSetOptic() {
        assertIs<SetElementsOptic<*, *>>(decode("set.*"))
        assertIs<ListElementsOptic<*, *>>(decode("list.*"))
    }

    @Test
    fun invalidPathsFailToDeserialize() {
        assertFailsWith<SerializationException> { decode("int?") }
        assertFailsWith<SerializationException> { decode("int.*") }
        assertFailsWith<SerializationException> { decode("embedded.<Foo>") }
        assertFailsWith<SerializationException> { decode("missing") }
    }

    @Test
    fun typedSerializerChecksTargetThroughWrappers() {
        val typed = OpticSerializer(LargeTestModel.serializer(), String.serializer())
        val optic = LargeTestModel.embeddedNullable.notNull + ClassUsedForEmbedding.value1
        assertEquals(optic, Json.decodeFromString(typed, Json.encodeToString(typed, optic)))
        assertFailsWith<SerializationException> {
            Json.decodeFromString(OpticSerializer(LargeTestModel.serializer(), Int.serializer()), "\"listEmbedded.*.value1\"")
        }
    }

    @Test
    fun variantsSerializeInAngleBrackets() {
        val polymorphic = AnyOpticSerializer(Polymorphic.serializer())
        val foo = IdentityLens(Polymorphic.serializer()).asType(Polymorphic.Foo.serializer())
        val fooName = foo + Polymorphic.Foo.name
        assertEquals("\"<Foo>\"", Json.encodeToString(polymorphic, foo))
        assertEquals("\"<Foo>.name\"", Json.encodeToString(polymorphic, fooName))
        assertEquals(foo, Json.decodeFromString(polymorphic, "\"<Foo>\""))
        assertEquals(fooName, Json.decodeFromString(polymorphic, "\"<Foo>.name\""))
        assertEquals(fooName, Json.decodeFromString(polymorphic, "\"<com.lightningkite.services.database.Polymorphic.Foo>.name\""))
        assertEquals(listOf(Polymorphic.Foo.name), Json.decodeFromString(polymorphic, "\"<Foo>.name\"").fields)
    }

    @Test
    fun variantsWithoutShortNamesUseSerialName() {
        val serializer = AnyOpticSerializer(PolymorphicClass.serializer())
        val topLevel = IdentityLens(PolymorphicClass.serializer()).asType(PolymorphicClassTopLevel.serializer()) + PolymorphicClassTopLevel.id
        val string = Json.encodeToString(serializer, topLevel)
        assertEquals("\"<${PolymorphicClassTopLevel.serializer().descriptor.serialName}>.id\"", string)
        assertEquals(topLevel, Json.decodeFromString(serializer, string))
    }

    @Test
    fun squareBracketVariantsNoLongerDeserialize() {
        assertFailsWith<SerializationException> { Json.decodeFromString(AnyOpticSerializer(Polymorphic.serializer()), "\"[Foo].name\"") }
    }

    @Test
    fun sealedTypeSharedPropertiesRoundTrip() {
        val serializer = AnyOpticSerializer(Shape.serializer())
        assertEquals("label", serializer.asString(Shape.label))
        assertEquals(Shape.label, serializer.fromString("label"))
        val json = Json.encodeToString(serializer, Shape.path.label)
        assertEquals("\"label\"", json)
        assertEquals(Shape.label, Json.decodeFromString(serializer, json))
    }

    @Test
    fun sealedTypeSharedPropertyDecodedBehavesLikeGenerated() {
        val decoded = AnyOpticSerializer(Shape.serializer()).fromString("label").coerceToOptic() as Optic<Shape, String>
        assertEquals("a", decoded.get(Shape.Square("a", 1)))
        assertEquals(Shape.Triangle("b", 3, 1), decoded.set(Shape.Triangle("a", 3, 1), "b"))
    }

    @Test
    fun sealedTypePropertiesNotSharedByEveryVariantDoNotDeserialize() {
        val serializer = AnyOpticSerializer(Shape.serializer())
        assertFailsWith<SerializationException> { serializer.fromString("size") }
        // Square has no field `sides`; it is a getter.
        assertFailsWith<SerializationException> { serializer.fromString("sides") }
    }

    @Test
    fun nestedSealedTypeSharedPropertyRoundTrips() {
        val serializer = AnyOpticSerializer(Shape.Polygon.serializer())
        assertEquals("size", serializer.asString(Shape.Polygon.size))
        assertEquals(Shape.Polygon.size, serializer.fromString("size"))
        assertEquals(Shape.Polygon.size, Json.decodeFromString(serializer, Json.encodeToString(serializer, Shape.Polygon.size)))
        // Circle is a Shape but not a Polygon, so only the Polygon lens can reach `size`.
        assertFailsWith<SerializationException> { AnyOpticSerializer(Shape.serializer()).fromString("size") }
    }

    @Test
    fun sealedTypeSharedPropertyInsideVariantPath() {
        val serializer = AnyOpticSerializer(Shape.Polygon.serializer())
        val optic = Shape.Polygon.path.asSquare.label
        assertEquals(optic, serializer.fromString(serializer.asString(optic)))
    }
}
