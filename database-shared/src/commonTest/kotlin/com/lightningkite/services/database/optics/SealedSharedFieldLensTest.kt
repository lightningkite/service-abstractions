package com.lightningkite.services.database.optics

import com.lightningkite.services.database.*
import kotlin.test.*

class SealedSharedFieldLensTest {
    private val square = Shape.Square("sq", 4)
    private val triangle = Shape.Triangle("tri", 3, 2)
    private val circle = Shape.Circle("circ")

    @Test
    fun getReadsFromWhicheverVariantTheValueIs() {
        assertEquals("sq", Shape.label<Shape>().get(square))
        assertEquals("tri", Shape.label<Shape>().get(triangle))
        assertEquals("circ", Shape.label<Shape>().get(circle))
    }

    @Test
    fun setKeepsTheVariantAndOtherFields() {
        assertEquals(Shape.Square("new", 4), Shape.label<Shape>().set(square, "new"))
        assertEquals(Shape.Triangle("new", 3, 2), Shape.label<Shape>().set(triangle, "new"))
        assertEquals(Shape.Circle("new"), Shape.label<Shape>().set(circle, "new"))
    }

    @Test
    fun nestedSealedTypeSharesWhatItsOwnVariantsShare() {
        assertEquals(4, Shape.Polygon.size<Shape.Polygon>().get(square))
        assertEquals(2, Shape.Polygon.size<Shape.Polygon>().get(triangle))
        assertEquals(Shape.Square("sq", 9), Shape.Polygon.size<Shape.Polygon>().set(square, 9))
    }

    @Test
    fun conditionMatchesAcrossVariants() {
        val condition = Shape.path.label.mapCondition(Condition.Equal("tri"))
        assertFalse(condition(square))
        assertTrue(condition(triangle))
        assertFalse(condition(circle))
        assertTrue(Shape.path.label.mapCondition(Condition.Equal("circ"))(circle))
    }

    @Test
    fun modificationAppliesToEveryVariant() {
        val modification = Shape.path.label.mapModification(Modification.Assign("x"))
        assertEquals(Shape.Square("x", 4), modification(square))
        assertEquals(Shape.Triangle("x", 3, 2), modification(triangle))
        assertEquals(Shape.Circle("x"), modification(circle))
    }

    @Test
    fun equalityComparesParentAndName() {
        assertEquals(Shape.label<Shape>(), SealedSharedFieldLens<Shape, String>(Shape.serializer(), "label"))
        assertEquals(Shape.label<Shape>().hashCode(), SealedSharedFieldLens<Shape, String>(Shape.serializer(), "label").hashCode())
        assertNotEquals<Any>(Shape.label<Shape>(), SealedSharedFieldLens<Shape, String>(Shape.serializer(), "other"))
        assertNotEquals<Any>(Shape.Polygon.size<Shape.Polygon>(), SealedSharedFieldLens<Shape.Polygon, Int>(Shape.Polygon.serializer(), "label"))
    }

    @Test
    fun missingFieldFailsWhenUsed() {
        // Square has `size` but Circle doesn't, so a lens for it on Shape can't be resolved.
        val lens = SealedSharedFieldLens<Shape, Int>(Shape.serializer(), "size")
        assertFailsWith<IllegalArgumentException> { lens.get(square) }
    }

    @Test
    fun nonSealedParentFailsWhenUsed() {
        val lens = SealedSharedFieldLens<Shape.Square, String>(Shape.Square.serializer(), "label")
        assertFailsWith<IllegalArgumentException> { lens.get(square) }
    }

    @Test
    fun toStringIsTheName() = assertEquals("label", Shape.label<Shape>().toString())

    @Test
    fun composesAfterALensOnAVariant() {
        val lens: Lens<Shape.Circle, String> = Lens<Shape.Circle>() + Shape.label()
        assertEquals("circ", lens.get(circle))
        assertEquals(Shape.Circle("new"), lens.set(circle, "new"))
    }

    @Test
    fun pathPropertyWorksOnAVariant() {
        val lens: Lens<Shape.Circle, String> = Lens<Shape.Circle>().label
        assertEquals(Shape.Circle("new"), lens.set(circle, "new"))
        assertEquals(Shape.Square("new", 4), Lens<Shape.Square>().label.set(square, "new"))
    }

    // Claude: the same overload shape as MutableReactive.lens in lightning-server-kiteui.
    private class Box<O>(var value: O)
    private fun <O, T> Box<O>.lens(optic: Optic<O, T>): String = "optic ${optic.get(value)}"
    private fun <O, T> Box<O>.lens(lens: Lens<O, T>): T = lens.get(value)

    @Test
    fun usableWhereTheReceiverIsAVariantOfTheSealedType() {
        val box = Box(circle)
        assertEquals("circ", box.lens(Shape.label()))
        assertEquals("circ", box.lens(Lens<Shape.Circle>().label))
        val polygons = Box<Shape.Square>(square)
        assertEquals(4, polygons.lens(Shape.Polygon.size()))
    }
}
