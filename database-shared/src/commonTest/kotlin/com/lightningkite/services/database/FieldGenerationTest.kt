package com.lightningkite.services.database

import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@GenerateDataClassPaths
@Serializable
data class Sample(
    val x: Int,
    val y: String? = null,
    val z: List<String> = listOf(),
) {
    @GenerateDataClassPaths
    @Serializable
    data class Nested(val value: Int) {
        @Serializable
        @GenerateDataClassPaths
        data class DoubleNested(val value: Int)
    }
}

@GenerateDataClassPaths
@Serializable
data class SampleGeneric<A, B : Comparable<B>>(
    val x: A,
    val y: B,
    val z: List<String> = listOf(),
) {
    @GenerateDataClassPaths
    @Serializable
    data class Nested(val value: Int) {
        @Serializable
        @GenerateDataClassPaths
        data class DoubleNested(val value: Int)
    }
}

@Serializable
@GenerateDataClassPaths
sealed interface Polymorphic {
    @Serializable
    @GenerateDataClassPaths
    data class Foo(val name: String) : Polymorphic

    @Serializable
    @GenerateDataClassPaths
    data class Bar(val id: Int) : Polymorphic
}

class FieldGenerationTest {
    @Test
    fun ifSyntaxWorksWereOk() {
        condition<Sample> { it.x gt 4 }
        condition<SampleGeneric<Int, String>> { it.x gt 4 }
        condition<Sample.Nested> { it.value eq 0 }
        condition<SampleGeneric.Nested> { it.value eq 0 }
        condition<Sample.Nested.DoubleNested> { it.value eq 0 }
        condition<SampleGeneric.Nested.DoubleNested> { it.value eq 0 }
    }

    @Test
    fun sealedVariantPaths() {
        val isFooNamedA = condition<Polymorphic> { it.asFoo.name eq "a" }
        condition<Polymorphic> { it isType Polymorphic.Foo.serializer() }
        assertTrue(isFooNamedA(Polymorphic.Foo("a")))
        assertFalse(isFooNamedA(Polymorphic.Foo("b")))
        assertFalse(isFooNamedA(Polymorphic.Bar(1)))

        val bumpBar = modification<Polymorphic> { it.asBar.id += 1 }
        assertEquals(Polymorphic.Bar(2), bumpBar(Polymorphic.Bar(1)))
        assertEquals(Polymorphic.Foo("a"), bumpBar(Polymorphic.Foo("a")))
    }

    @Test
    fun sealedVariantChecks() {
        val isFoo = condition<Polymorphic> { it.isFoo() }
        assertEquals(condition<Polymorphic> { it isType Polymorphic.Foo.serializer() }, isFoo)
        assertTrue(isFoo(Polymorphic.Foo("a")))
        assertFalse(isFoo(Polymorphic.Bar(1)))
        assertTrue(condition<Polymorphic> { it.isBar() }(Polymorphic.Bar(1)))
    }

    @Test
    fun sealedVariantSetOnlyReplacesMatchingVariant() {
        val asFoo = Polymorphic.path.asFoo
        assertEquals(Polymorphic.Foo("b"), asFoo.set(Polymorphic.Foo("a"), Polymorphic.Foo("b")))
        assertEquals(Polymorphic.Bar(1), asFoo.set(Polymorphic.Bar(1), Polymorphic.Foo("b")))
        assertEquals(Polymorphic.Bar(1), Polymorphic.path.asFoo.name.set(Polymorphic.Bar(1), "b"))
    }
}
