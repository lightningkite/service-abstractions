package com.lightningkite.services.database.path

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.*
import com.lightningkite.services.database.path.legacy.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.*

@Queryable
data class OpticNullableOuter(val inner: OpticNullableInner = OpticNullableInner())

@Queryable
data class OpticNullableInner(val value: String? = null)

class OpticSerializationTest {
    private val serializer = OpticOnSerializer(LargeTestModel.serializer())
    private val oldSerializer = DataClassPathSerializer(LargeTestModel.serializer())
    private val root = DataClassPathSelf(LargeTestModel.serializer())

    private fun encode(optic: OpticOn<LargeTestModel>): String = Json.encodeToString(serializer, optic).removeSurrounding("\"")
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
        val serializer = OpticOnSerializer(OpticNullableOuter.serializer())
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
        val polymorphic = OpticOnSerializer(Polymorphic.serializer())
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
        val serializer = OpticOnSerializer(PolymorphicClass.serializer())
        val topLevel = IdentityLens(PolymorphicClass.serializer()).asType(PolymorphicClassTopLevel.serializer()) + PolymorphicClassTopLevel.id
        val string = Json.encodeToString(serializer, topLevel)
        assertEquals("\"<${PolymorphicClassTopLevel.serializer().descriptor.serialName}>.id\"", string)
        assertEquals(topLevel, Json.decodeFromString(serializer, string))
    }

    @Test
    fun squareBracketVariantsNoLongerDeserialize() {
        assertFailsWith<SerializationException> { Json.decodeFromString(OpticOnSerializer(Polymorphic.serializer()), "\"[Foo].name\"") }
    }
}
