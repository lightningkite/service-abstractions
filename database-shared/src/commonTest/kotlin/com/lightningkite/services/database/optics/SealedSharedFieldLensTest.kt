package com.lightningkite.services.database.optics

import com.lightningkite.services.database.*
import kotlin.test.*

class SealedSharedFieldLensTest {
    private val square = Shape.Square("sq", 4)
    private val triangle = Shape.Triangle("tri", 3, 2)
    private val circle = Shape.Circle("circ")

    @Test
    fun getReadsFromWhicheverVariantTheValueIs() {
        assertEquals("sq", Shape.label.get(square))
        assertEquals("tri", Shape.label.get(triangle))
        assertEquals("circ", Shape.label.get(circle))
    }

    @Test
    fun setKeepsTheVariantAndOtherFields() {
        assertEquals(Shape.Square("new", 4), Shape.label.set(square, "new"))
        assertEquals(Shape.Triangle("new", 3, 2), Shape.label.set(triangle, "new"))
        assertEquals(Shape.Circle("new"), Shape.label.set(circle, "new"))
    }

    @Test
    fun nestedSealedTypeSharesWhatItsOwnVariantsShare() {
        assertEquals(4, Shape.Polygon.size.get(square))
        assertEquals(2, Shape.Polygon.size.get(triangle))
        assertEquals(Shape.Square("sq", 9), Shape.Polygon.size.set(square, 9))
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
        assertEquals(Shape.label, SealedSharedFieldLens<Shape, String>(Shape.serializer(), "label"))
        assertEquals(Shape.label.hashCode(), SealedSharedFieldLens<Shape, String>(Shape.serializer(), "label").hashCode())
        assertNotEquals<Any>(Shape.label, SealedSharedFieldLens<Shape, String>(Shape.serializer(), "other"))
        assertNotEquals<Any>(Shape.Polygon.size, SealedSharedFieldLens<Shape.Polygon, Int>(Shape.Polygon.serializer(), "label"))
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
    fun toStringIsTheName() = assertEquals("label", Shape.label.toString())
}
