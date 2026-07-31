package com.lightningkite.services.database

import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
@GenerateDataClassPaths
data class GuaranteedAfterTestModel(
    val a: Int = 0,
    val b: Int = 0,
)

/**
 * Regression tests for [Condition.guaranteedAfter]: a top-level [Condition.And]/[Condition.Or] used to be
 * evaluated against a non-[Modification.Assign] modification by casting `this` straight to [Condition.OnField],
 * which always failed and fell back to `true` -- silently treating any restriction built from an AND-merge (as
 * the [UpdateRestriction] DSL does) as unconditionally satisfied.
 */
class GuaranteedAfterTest {
    @Test
    fun `And condition catches a violated same-field assignment`() {
        val cond = condition<GuaranteedAfterTestModel> { (it.a eq 5) and (it.b eq 5) }
        val mod = modification<GuaranteedAfterTestModel> { it.a assign 999 }
        // `a` is being overwritten to 999, which violates the `a == 5` branch of the And.
        assertFalse(cond.guaranteedAfter(mod))
    }

    @Test
    fun `And condition passes when every branch's assignment matches`() {
        val cond = condition<GuaranteedAfterTestModel> { (it.a eq 5) and (it.b gte 0) }
        val mod = modification<GuaranteedAfterTestModel> {
            it.a assign 5
            it.b assign 10
        }
        assertTrue(cond.guaranteedAfter(mod))
    }

    @Test
    fun `Or condition is guaranteed if at least one branch's assignment matches`() {
        val cond = condition<GuaranteedAfterTestModel> { (it.a eq 5) or (it.a eq 999) }
        val mod = modification<GuaranteedAfterTestModel> { it.a assign 999 }
        assertTrue(cond.guaranteedAfter(mod))
    }

    @Test
    fun `Or condition is not guaranteed when the assignment matches neither branch`() {
        val cond = condition<GuaranteedAfterTestModel> { (it.a eq 5) or (it.a eq 6) }
        val mod = modification<GuaranteedAfterTestModel> { it.a assign 999 }
        assertFalse(cond.guaranteedAfter(mod))
    }

    @Test
    fun `nested And within Or is evaluated recursively rather than defaulting to permissive`() {
        val cond = condition<GuaranteedAfterTestModel> { ((it.a eq 5) and (it.b eq 5)) or (it.a eq 999) }
        val mod = modification<GuaranteedAfterTestModel> { it.a assign 999 }
        // The Or's second branch (a == 999) matches the assignment, so the whole thing is guaranteed.
        assertTrue(cond.guaranteedAfter(mod))
    }
}
