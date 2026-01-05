package com.lightningkite.services.database.cassandra

import com.lightningkite.services.database.Condition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConditionNormalizerTest {

    @Test
    fun testDoubleNegationElimination() {
        // Not(Not(x)) -> x
        val original = Condition.Not(Condition.Not(Condition.Equal(5)))
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.Equal<Int>>(normalized)
        assertEquals(5, (normalized as Condition.Equal).value)
    }

    @Test
    fun testNotEqualToNotEqual() {
        // Not(Equal(x)) -> NotEqual(x)
        val original: Condition<Int> = Condition.Not(Condition.Equal(5))
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.NotEqual<Int>>(normalized)
        assertEquals(5, (normalized as Condition.NotEqual).value)
    }

    @Test
    fun testNotNotEqualToEqual() {
        // Not(NotEqual(x)) -> Equal(x)
        val original: Condition<Int> = Condition.Not(Condition.NotEqual(5))
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.Equal<Int>>(normalized)
        assertEquals(5, (normalized as Condition.Equal).value)
    }

    @Test
    fun testNotGreaterThanToLessThanOrEqual() {
        // Not(GreaterThan(x)) -> LessThanOrEqual(x)
        val original: Condition<Int> = Condition.Not(Condition.GreaterThan(5))
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.LessThanOrEqual<Int>>(normalized)
        assertEquals(5, (normalized as Condition.LessThanOrEqual).value)
    }

    @Test
    fun testNotLessThanToGreaterThanOrEqual() {
        // Not(LessThan(x)) -> GreaterThanOrEqual(x)
        val original: Condition<Int> = Condition.Not(Condition.LessThan(5))
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.GreaterThanOrEqual<Int>>(normalized)
        assertEquals(5, (normalized as Condition.GreaterThanOrEqual).value)
    }

    @Test
    fun testDeMorganAndToOr() {
        // Not(And(a, b)) -> Or(Not(a), Not(b))
        val original: Condition<Int> = Condition.Not(
            Condition.And(Condition.Equal(1), Condition.Equal(2))
        )
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.Or<Int>>(normalized)
        val or = normalized as Condition.Or<Int>
        assertEquals(2, or.conditions.size)
        assertIs<Condition.NotEqual<Int>>(or.conditions[0])
        assertIs<Condition.NotEqual<Int>>(or.conditions[1])
    }

    @Test
    fun testDeMorganOrToAnd() {
        // Not(Or(a, b)) -> And(Not(a), Not(b))
        val original: Condition<Int> = Condition.Not(
            Condition.Or(Condition.Equal(1), Condition.Equal(2))
        )
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.And<Int>>(normalized)
        val and = normalized as Condition.And<Int>
        assertEquals(2, and.conditions.size)
        assertIs<Condition.NotEqual<Int>>(and.conditions[0])
        assertIs<Condition.NotEqual<Int>>(and.conditions[1])
    }

    @Test
    fun testNotAlwaysToNever() {
        val original: Condition<Int> = Condition.Not(Condition.Always)
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.Never>(normalized)
    }

    @Test
    fun testNotNeverToAlways() {
        val original: Condition<Int> = Condition.Not(Condition.Never)
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.Always>(normalized)
    }

    @Test
    fun testNotInsideToNotInside() {
        val original: Condition<Int> = Condition.Not(Condition.Inside(listOf(1, 2, 3)))
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.NotInside<Int>>(normalized)
        assertEquals(listOf(1, 2, 3), (normalized as Condition.NotInside).values)
    }

    @Test
    fun testNotNotInsideToInside() {
        val original: Condition<Int> = Condition.Not(Condition.NotInside(listOf(1, 2, 3)))
        val normalized = ConditionNormalizer.normalize(original)
        assertIs<Condition.Inside<Int>>(normalized)
        assertEquals(listOf(1, 2, 3), (normalized as Condition.Inside).values)
    }

    @Test
    fun testPassthroughForAlreadyNormalized() {
        val original: Condition<Int> = Condition.And(
            Condition.Equal(1),
            Condition.GreaterThan(0)
        )
        val normalized = ConditionNormalizer.normalize(original)
        // Should return structurally equivalent condition
        assertIs<Condition.And<Int>>(normalized)
    }
}
