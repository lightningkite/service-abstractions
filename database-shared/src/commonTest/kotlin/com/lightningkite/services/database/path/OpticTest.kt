package com.lightningkite.services.database.path

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.*

@Queryable
data class OpticOuter(val middle: OpticMiddle = OpticMiddle())

@Queryable
data class OpticMiddle(val inner: OpticInner = OpticInner())

@Queryable
data class OpticInner(val value: String = "")

class OpticTest {
    // Claude: stands in for a real prism, so composing a lens with a non-lens optic can be tested.
    private class NotNullForTest<V : Any>(override val serializer: KSerializer<V>) : Optic<V?, V> {
        override fun get(key: V?): V? = key
        override fun getOrThrow(key: V?): V = key!!
        override fun set(key: V?, value: V): V? = key?.let { value }
        override fun mapCondition(condition: Condition<V>): Condition<V?> = Condition.IfNotNull(condition)
        override fun mapModification(modification: Modification<V>): Modification<V?> = Modification.IfNotNull(modification)
    }

    private val serializer = OpticSerializer(LargeTestModel.serializer())
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
        val old = path(LargeTestModel.serializer())[LargeTestModel.embedded][ClassUsedForEmbedding.value1]
        val new = LargeTestModel.embedded + ClassUsedForEmbedding.value1
        assertEquals(old.mapCondition(condition), new.mapCondition(condition))
    }

    @Test
    fun serializationRoundTripKeepsLens() {
        val lens = LargeTestModel.embedded + ClassUsedForEmbedding.value1
        val string = Json.encodeToString(serializer, lens)
        assertEquals("\"embedded.value1\"", string)
        val decoded = Json.decodeFromString(serializer, string)
        assertEquals(lens, decoded)
        assertIs<Lens<*, *>>(decoded)
    }

    @Test
    fun identitySerializesAsThis() {
        val identity = IdentityLens(LargeTestModel.serializer())
        assertEquals("\"this\"", Json.encodeToString(serializer, identity))
        assertEquals(identity, Json.decodeFromString(serializer, "\"this\""))
    }

    @Test
    fun unknownOpticDoesNotSerialize() {
        val optic = LargeTestModel.embeddedNullable + NotNullForTest(ClassUsedForEmbedding.serializer())
        assertFailsWith<SerializationException> { Json.encodeToString(serializer, optic) }
    }

    @Test
    fun separatelyBuiltPathsSerializeAsOnePath() {
        val relative = OpticMiddle.inner + OpticInner.value
        val full = OpticOuter.middle + relative
        assertEquals("\"inner.value\"", Json.encodeToString(OpticSerializer(OpticMiddle.serializer()), relative))

        val outerSerializer = OpticSerializer(OpticOuter.serializer())
        val string = Json.encodeToString(outerSerializer, full)
        assertEquals("\"middle.inner.value\"", string)
        val decoded = Json.decodeFromString(outerSerializer, string)
        assertEquals(full, decoded)
        assertEquals(OpticOuter.middle + OpticMiddle.inner + OpticInner.value, decoded)
        assertIs<Lens<*, *>>(decoded)
        assertEquals("x", (full.set(OpticOuter(), "x")).middle.inner.value)
    }
}
