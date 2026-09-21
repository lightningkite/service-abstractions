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

@Serializable
@GenerateDataClassPaths
sealed class PolymorphicClass {
    @Serializable
    @GenerateDataClassPaths
    data class Foo(val name: String) : PolymorphicClass()

    @Serializable
    @GenerateDataClassPaths
    data object Empty : PolymorphicClass()
}

/** Declared outside [PolymorphicClass], so its serial name isn't nested under the sealed class's. */
@Serializable
@GenerateDataClassPaths
data class PolymorphicClassTopLevel(val id: Int) : PolymorphicClass()

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
        val isFoo = condition<Polymorphic> { it isType Polymorphic.Foo.serializer() }
        assertTrue(isFoo(Polymorphic.Foo("a")))
        assertFalse(isFoo(Polymorphic.Bar(1)))
    }

    @Test
    fun sealedVariantSetOnlyReplacesMatchingVariant() {
        val asFoo = Polymorphic.path.asFoo
        assertEquals(Polymorphic.Foo("b"), asFoo.set(Polymorphic.Foo("a"), Polymorphic.Foo("b")))
        assertEquals(Polymorphic.Bar(1), asFoo.set(Polymorphic.Bar(1), Polymorphic.Foo("b")))
        assertEquals(Polymorphic.Bar(1), Polymorphic.path.asFoo.name.set(Polymorphic.Bar(1), "b"))
    }

    @Test
    fun sealedClassVariantPaths() {
        val isFooNamedA = condition<PolymorphicClass> { it.asFoo.name eq "a" }
        assertTrue(isFooNamedA(PolymorphicClass.Foo("a")))
        assertFalse(isFooNamedA(PolymorphicClass.Foo("b")))
        assertFalse(isFooNamedA(PolymorphicClassTopLevel(1)))
        assertFalse(isFooNamedA(PolymorphicClass.Empty))

        val bumpTopLevel = modification<PolymorphicClass> { it.asPolymorphicClassTopLevel.id += 1 }
        assertEquals(PolymorphicClassTopLevel(2), bumpTopLevel(PolymorphicClassTopLevel(1)))
        assertEquals(PolymorphicClass.Foo("a"), bumpTopLevel(PolymorphicClass.Foo("a")))
    }

    @Test
    fun sealedClassVariantChecks() {
        val isEmpty = condition<PolymorphicClass> { it isType PolymorphicClass.Empty.serializer() }
        assertTrue(isEmpty(PolymorphicClass.Empty))
        assertFalse(isEmpty(PolymorphicClass.Foo("a")))
        assertFalse(isEmpty(PolymorphicClassTopLevel(1)))

        val isEmptyViaPath = condition<PolymorphicClass> { it.asEmpty isType PolymorphicClass.Empty.serializer() }
        assertTrue(isEmptyViaPath(PolymorphicClass.Empty))
        assertFalse(isEmptyViaPath(PolymorphicClass.Foo("a")))
    }

    @Test
    fun sealedClassVariantSetOnlyReplacesMatchingVariant() {
        val asTopLevel = PolymorphicClass.path.asPolymorphicClassTopLevel
        assertEquals(PolymorphicClassTopLevel(5), asTopLevel.set(PolymorphicClassTopLevel(1), PolymorphicClassTopLevel(5)))
        assertEquals(PolymorphicClass.Empty, asTopLevel.set(PolymorphicClass.Empty, PolymorphicClassTopLevel(5)))
        assertEquals(PolymorphicClass.Foo("a"), asTopLevel.id.set(PolymorphicClass.Foo("a"), 5))
    }

    @Test
    fun sealedClassVariantPathStrings() {
        val serializer = DataClassPathSerializer(PolymorphicClass.serializer())
        val nested = PolymorphicClass.path.asFoo.name
        val topLevel = PolymorphicClass.path.asPolymorphicClassTopLevel.id
        assertEquals("[Foo].name", nested.toString())
        // Not nested under the sealed class's serial name, so there is no short name to use.
        assertEquals("[${PolymorphicClassTopLevel.serializer().descriptor.serialName}].id", topLevel.toString())
        assertEquals(nested, serializer.fromString(nested.toString()))
        assertEquals(topLevel, serializer.fromString(topLevel.toString()))
    }
}
