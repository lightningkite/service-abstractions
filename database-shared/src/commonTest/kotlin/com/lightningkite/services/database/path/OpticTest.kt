package com.lightningkite.services.database.path

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.*
import com.lightningkite.services.database.path.legacy.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.*

@Queryable
data class OpticOuter(val middle: OpticMiddle = OpticMiddle())

@Queryable
data class OpticMiddle(val inner: OpticInner = OpticInner())

@Queryable
data class OpticInner(val value: String = "")

@Serializable
data class OpticHolder(val path: Optic<OpticOuter, String>)

@Serializable
data class OpticOnHolder(val path: OpticOn<OpticOuter>)

class OpticTest {
    // Claude: stands in for a real prism, so composing a lens with a non-lens optic can be tested.
    private class NotNullForTest<V : Any>(override val serializer: KSerializer<V>) : Optic<V?, V> {
        override fun get(source: V?): V? = source
        override fun getOrThrow(source: V?): V = source!!
        override fun set(source: V?, value: V): V? = source?.let { value }
        override fun mapCondition(condition: Condition<V>): Condition<V?> = Condition.IfNotNull(condition)
        override fun mapModification(modification: Modification<V>): Modification<V?> = Modification.IfNotNull(modification)
        override val properties: List<SerializableProperty<*, *>> get() = listOf()
        override fun toString(): String = "notNull"
    }

    private val present = LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value1 = "a"))
    private val absent = LargeTestModel(embeddedNullable = null)

    @Test
    fun lensPlusLensIsLens() {
        val lens: Lens<LargeTestModel, String> = LargeTestModel.embedded + ClassUsedForEmbedding.value1
        val model = LargeTestModel(embedded = ClassUsedForEmbedding(value1 = "a"))
        assertEquals("a", lens.get(model))
        assertEquals("b", lens.set(model, "b").embedded.value1)
    }

    @Test
    fun lensPlusOpticIsOptic() {
        val optic: Optic<LargeTestModel, String> =
            LargeTestModel.embeddedNullable + NotNullForTest(ClassUsedForEmbedding.serializer()) + ClassUsedForEmbedding.value1
        assertIsNot<Lens<*, *>>(optic)
        assertEquals("a", optic.get(present))
        assertNull(optic.get(absent))
        assertEquals("b", optic.set(present, "b").embeddedNullable?.value1)
        assertEquals(absent, optic.set(absent, "b"))
    }

    @Test
    fun lensesTypedAsOpticsComposeToLens() {
        val outer: Optic<LargeTestModel, ClassUsedForEmbedding> = LargeTestModel.embedded
        assertIs<Lens<*, *>>(outer + ClassUsedForEmbedding.value1)
    }

    @Test
    fun groupingAndIdentityDoNotAffectEquality() {
        val identity = IdentityLens(LargeTestModel.serializer())
        val embedded = LargeTestModel.embedded
        val value1 = ClassUsedForEmbedding.value1
        assertEquals(embedded, identity + embedded)
        assertEquals(embedded, embedded + IdentityLens(ClassUsedForEmbedding.serializer()))
        val inner = ClassUsedForEmbedding.serializer().let { IdentityLens(it) + value1 }
        assertEquals((identity + embedded) + value1, identity + (embedded + inner))
    }

    @Test
    fun mapConditionMatchesDataClassPath() {
        val condition = Condition.Equal("x")
        val old = DataClassPathSelf(LargeTestModel.serializer())[LargeTestModel.embedded][ClassUsedForEmbedding.value1]
        val new = LargeTestModel.embedded + ClassUsedForEmbedding.value1
        assertEquals(old.mapCondition(condition), new.mapCondition(condition))
    }

    @Test
    fun serializationRoundTripKeepsLens() {
        val lens = LargeTestModel.embedded + ClassUsedForEmbedding.value1
        val serializer = OpticSerializer(LargeTestModel.serializer(), String.serializer())
        val string = Json.encodeToString(serializer, lens)
        assertEquals("\"embedded.value1\"", string)
        val decoded = Json.decodeFromString(serializer, string)
        assertEquals(lens, decoded)
        assertIs<Lens<*, *>>(decoded)
    }

    @Test
    fun identitySerializesAsThis() {
        val identity = IdentityLens(LargeTestModel.serializer())
        val serializer = OpticSerializer(LargeTestModel.serializer(), LargeTestModel.serializer())
        assertEquals("\"this\"", Json.encodeToString(serializer, identity))
        assertEquals(identity, Json.decodeFromString(serializer, "\"this\""))
    }

    @Test
    fun unknownOpticDoesNotSerialize() {
        val optic = LargeTestModel.embeddedNullable + NotNullForTest(ClassUsedForEmbedding.serializer())
        val serializer = OpticSerializer(LargeTestModel.serializer(), ClassUsedForEmbedding.serializer())
        assertFailsWith<SerializationException> { Json.encodeToString(serializer, optic) }
    }

    @Test
    fun separatelyBuiltPathsSerializeAsOnePath() {
        val relative = OpticMiddle.inner + OpticInner.value
        val full = OpticOuter.middle + relative
        assertEquals("\"inner.value\"", Json.encodeToString(OpticSerializer(OpticMiddle.serializer(), String.serializer()), relative))

        val outerSerializer = OpticSerializer(OpticOuter.serializer(), String.serializer())
        val string = Json.encodeToString(outerSerializer, full)
        assertEquals("\"middle.inner.value\"", string)
        val decoded = Json.decodeFromString(outerSerializer, string)
        assertEquals(full, decoded)
        assertEquals(OpticOuter.middle + OpticMiddle.inner + OpticInner.value, decoded)
        assertIs<Lens<*, *>>(decoded)
        assertEquals("x", (full.set(OpticOuter(), "x")).middle.inner.value)
    }

    @Test
    fun opticPropertySerializesInsideSerializableClass() {
        val holder = OpticHolder(OpticOuter.middle + OpticMiddle.inner + OpticInner.value)
        val string = Json.encodeToString(OpticHolder.serializer(), holder)
        assertEquals("""{"path":"middle.inner.value"}""", string)
        assertEquals(holder, Json.decodeFromString(OpticHolder.serializer(), string))
    }

    @Test
    fun pathWithWrongTargetTypeFailsToDeserialize() {
        val serializer = OpticSerializer(OpticOuter.serializer(), Int.serializer())
        assertFailsWith<SerializationException> { Json.decodeFromString(serializer, "\"middle.inner.value\"") }
    }

    @Test
    fun identityWithWrongTargetTypeFailsToDeserialize() {
        val serializer = OpticSerializer(OpticOuter.serializer(), String.serializer())
        assertFailsWith<SerializationException> { Json.decodeFromString(serializer, "\"this\"") }
    }

    @Test
    fun anySerializerReadsPathsToAnyType() {
        val serializer = OpticOnSerializer(OpticOuter.serializer())
        assertEquals(OpticOuter.middle + OpticMiddle.inner, Json.decodeFromString(serializer, "\"middle.inner\""))
        assertEquals(OpticOuter.middle + OpticMiddle.inner + OpticInner.value, Json.decodeFromString(serializer, "\"middle.inner.value\""))
    }

    @Test
    fun opticOnPropertyReadsPathsToAnyType() {
        for (holder in listOf(
            OpticOnHolder(OpticOuter.middle + OpticMiddle.inner),
            OpticOnHolder(OpticOuter.middle + OpticMiddle.inner + OpticInner.value),
            OpticOnHolder(IdentityLens(OpticOuter.serializer())),
        )) {
            val string = Json.encodeToString(OpticOnHolder.serializer(), holder)
            assertEquals(holder, Json.decodeFromString(OpticOnHolder.serializer(), string))
        }
    }

    @Test
    fun asOpticReturnsTheSameOptic() {
        val optic = OpticOuter.middle + OpticMiddle.inner
        val any: OpticOn<OpticOuter> = optic
        assertSame(optic, any.coerceToOptic())
    }

    @Test
    fun propertiesListFieldsFromOutermostToInnermost() {
        assertEquals(listOf(), IdentityLens(OpticOuter.serializer()).properties)
        assertEquals(listOf(OpticOuter.middle), OpticOuter.middle.properties)
        assertEquals(
            listOf(OpticOuter.middle, OpticMiddle.inner, OpticInner.value),
            (OpticOuter.middle + (OpticMiddle.inner + OpticInner.value)).properties,
        )
    }

    @Test
    fun propertiesSkipNonFieldSteps() {
        val optic = NotNullOptic(LargeTestModel.embeddedNullable) + ClassUsedForEmbedding.value1
        assertEquals(listOf(LargeTestModel.embeddedNullable, ClassUsedForEmbedding.value1), optic.properties)
    }

    @Test
    fun propertiesMatchDataClassPath() {
        val old = DataClassPathSelf(LargeTestModel.serializer())[LargeTestModel.embeddedNullable].notNull[ClassUsedForEmbedding.value1]
        val new = NotNullOptic(LargeTestModel.embeddedNullable) + ClassUsedForEmbedding.value1
        assertEquals(old.properties, new.properties)
    }

    @Test
    fun testStringForms() {
        val cases = listOf(
            LargeTestModel._id,
            LargeTestModel.embedded.value1,
            LargeTestModel.embeddedNullable.notNull.value1,
            UnreasonablyComplex.string,
            UnreasonablyComplex.nested.notNull.string,
            UnreasonablyComplex.listNested.elements.nested.notNull.int,
            Polymorphic.path.asFoo,
            Polymorphic.path.asFoo.name,
            Polymorphic.path.asBar.id,
            ContainsPolymorphic.clazz.asFoo.name,
            ContainsPolymorphic.clazz.asEmpty,
            ContainsPolymorphic.clazz.asPolymorphicClassTopLevel.id,
        )

        for (case in cases) println(case.toString())
    }
}
