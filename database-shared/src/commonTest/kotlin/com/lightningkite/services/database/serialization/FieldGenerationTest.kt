@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.optics.Lens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Queryable
data class Sample(
    val x: Int,
    val y: String? = null,
    val z: List<String> = listOf(),
) {
    @Queryable
    data class Nested(val value: Int) {
        @Queryable
        data class DoubleNested(val value: Int)
    }
}

@Queryable
data class SampleGeneric<A, B : Comparable<B>>(
    val x: A,
    val y: B,
    val z: List<String> = listOf(),
) {
    @Queryable
    data class Nested(val value: Int) {
        @Queryable
        data class DoubleNested(val value: Int)
    }
}

@Queryable
sealed interface Polymorphic {
    @Queryable
    data class Foo(val name: String) : Polymorphic

    @Queryable
    data class Bar(val id: Int) : Polymorphic
}

@Queryable
sealed interface Shape {
    val label: String

    @Queryable
    sealed interface Polygon : Shape {
        val size: Int
        val sides: Int
    }

    @Queryable
    data class Square(override val label: String, override val size: Int, val other: String = "hello world") : Polygon {
        override val sides: Int get() = 4
    }

    @Queryable
    data class Triangle(override val label: String, override val sides: Int = 3, override val size: Int) : Polygon

    @Queryable
    data class Circle(override val label: String) : Shape
}

@Queryable
sealed class PolymorphicClass {
    @Queryable
    data class Foo(val name: String) : PolymorphicClass()

    @Queryable
    data object Empty : PolymorphicClass()
}

@Queryable
data class ContainsPolymorphic(
    val inter: Polymorphic,
    val clazz: PolymorphicClass
)

/** Declared outside [PolymorphicClass], so its serial name isn't nested under the sealed class's. */
@Queryable
data class PolymorphicClassTopLevel(val id: Int) : PolymorphicClass()

class FieldGenerationTest {
    @Test
    fun ifSyntaxWorksWereOk() {
        Condition<Sample> { it.x gt 4 }
        Condition<SampleGeneric<Int, String>> { it.x gt 4 }
        Condition<Sample.Nested> { it.value eq 0 }
        Condition<SampleGeneric.Nested> { it.value eq 0 }
        Condition<Sample.Nested.DoubleNested> { it.value eq 0 }
        Condition<SampleGeneric.Nested.DoubleNested> { it.value eq 0 }

        Sample.x
        Sample.fields
    }

    @Test
    fun sealedVariantPaths() {
        val isFooNamedA = Condition<Polymorphic> { it.asFoo.name eq "a" }
        Condition<Polymorphic> { it isType Polymorphic.Foo.serializer() }
        assertTrue(isFooNamedA(Polymorphic.Foo("a")))
        assertFalse(isFooNamedA(Polymorphic.Foo("b")))
        assertFalse(isFooNamedA(Polymorphic.Bar(1)))

        val bumpBar = Modification<Polymorphic> { it.asBar.id += 1 }
        assertEquals(Polymorphic.Bar(2), bumpBar(Polymorphic.Bar(1)))
        assertEquals(Polymorphic.Foo("a"), bumpBar(Polymorphic.Foo("a")))
    }

    @Test
    fun sealedVariantChecks() {
        val isFoo = Condition<Polymorphic> { it isType Polymorphic.Foo.serializer() }
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
        val isFooNamedA = Condition<PolymorphicClass> { it.asFoo.name eq "a" }
        assertTrue(isFooNamedA(PolymorphicClass.Foo("a")))
        assertFalse(isFooNamedA(PolymorphicClass.Foo("b")))
        assertFalse(isFooNamedA(PolymorphicClassTopLevel(1)))
        assertFalse(isFooNamedA(PolymorphicClass.Empty))

        val bumpTopLevel = Modification<PolymorphicClass> { it.asPolymorphicClassTopLevel.id += 1 }
        assertEquals(PolymorphicClassTopLevel(2), bumpTopLevel(PolymorphicClassTopLevel(1)))
        assertEquals(PolymorphicClass.Foo("a"), bumpTopLevel(PolymorphicClass.Foo("a")))
    }

    @Test
    fun sealedClassVariantChecks() {
        val isEmpty = Condition<PolymorphicClass> { it isType PolymorphicClass.Empty.serializer() }
        assertTrue(isEmpty(PolymorphicClass.Empty))
        assertFalse(isEmpty(PolymorphicClass.Foo("a")))
        assertFalse(isEmpty(PolymorphicClassTopLevel(1)))

        val isEmptyViaPath = Condition<PolymorphicClass> { it.asEmpty isType PolymorphicClass.Empty.serializer() }
        assertTrue(isEmptyViaPath(PolymorphicClass.Empty))
        assertFalse(isEmptyViaPath(PolymorphicClass.Foo("a")))
        Lens<Shape.Circle>() + Shape.label()
    }

    @Test
    fun sealedClassVariantSetOnlyReplacesMatchingVariant() {
        val asTopLevel = Lens<PolymorphicClass>().asPolymorphicClassTopLevel
        assertEquals(PolymorphicClassTopLevel(5), asTopLevel.set(PolymorphicClassTopLevel(1), PolymorphicClassTopLevel(5)))
        assertEquals(PolymorphicClass.Empty, asTopLevel.set(PolymorphicClass.Empty, PolymorphicClassTopLevel(5)))
        assertEquals(PolymorphicClass.Foo("a"), asTopLevel.id.set(PolymorphicClass.Foo("a"), 5))
    }

    @Test
    fun sealedClassVariantPathStrings() {
        val serializer = AnyOpticSerializer(PolymorphicClass.serializer())
        val nested = PolymorphicClass.path.asFoo.name
        val topLevel = PolymorphicClass.path.asPolymorphicClassTopLevel.id
        assertEquals("<Foo>.name", serializer.asString(nested))
        // Not nested under the sealed class's serial name, so there is no short name to use.
        assertEquals("<${PolymorphicClassTopLevel.serializer().descriptor.serialName}>.id", serializer.asString(topLevel))
        assertEquals(nested, serializer.fromString(serializer.asString(nested)))
        assertEquals(topLevel, serializer.fromString(serializer.asString(topLevel)))
    }

    @Test
    fun sealedInterfaceSharedProperties() {
        val label = Shape.label<Shape>()
        assertEquals("a", label.get(Shape.Square("a", 1)))
        assertEquals(Shape.Triangle("b", 3, 1), label.set(Shape.Triangle("a", 3, 1), "b"))
        val condition = Shape.path.label eq "a"
        assertTrue(condition(Shape.Square("a", 1)))
        assertTrue(condition(Shape.Triangle("a", 3, 1)))
        assertFalse(condition(Shape.Triangle("b", 3, 1)))
        assertEquals(Shape.Square("z", 1), Shape.path.label.mapModification(Modification.Assign("z"))(Shape.Square("a", 1)))
        // Square's `sides` is a getter, so it gets no accessor: Shape.sides would not compile.
    }
}
