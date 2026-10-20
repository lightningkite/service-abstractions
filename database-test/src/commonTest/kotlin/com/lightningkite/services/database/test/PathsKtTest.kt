package com.lightningkite.services.database.test

import com.lightningkite.services.database.path.*

import com.lightningkite.services.database.*
import kotlin.test.*

class PathsKtTest {

    @Test
    fun testModificationField() {
        (path<LargeTestModel>().int).let { modification ->
            assertTrue(modification<LargeTestModel> { it.int assign 3 }.affects(modification))
            assertTrue(modification<LargeTestModel> { it.int += 3 }.affects(modification))
            assertFalse(modification<LargeTestModel> { it.short assign 3 }.affects(modification))
        }
        (path<LargeTestModel>().intNullable).let { modification ->
            assertTrue(modification<LargeTestModel> { it.intNullable assign 3 }.affects(modification))
            assertTrue(modification<LargeTestModel>() { it.intNullable.notNull += 3 }.affects(modification))
            assertFalse(modification<LargeTestModel> { it.short assign 3 }.affects(modification))
        }
    }

    @Test
    fun testConditionModification() {
        modification<LargeTestModel>() { it.int assign 2 }.let { modification ->
            assertTrue((path<LargeTestModel>().int eq 3).readsResultOf(modification))
            assertTrue((path<LargeTestModel>().int gt 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().short eq 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().always).readsResultOf(modification))
        }
        modification<LargeTestModel>() { it.intNullable assign 2 }.let { modification ->
            assertTrue((path<LargeTestModel>().intNullable eq 3).readsResultOf(modification))
            assertTrue((path<LargeTestModel>().intNullable.notNull gt 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().short eq 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().always).readsResultOf(modification))
        }
        (path<LargeTestModel>().listEmbedded.elements.value2 gt 2).let { condition ->
            assertFalse(condition.readsResultOf(modification { it.intNullable assign 2 }))
            assertTrue(condition.readsResultOf(modification { it.listEmbedded.forEach { it.value2 assign 2 } }))
            assertTrue(condition.readsResultOf(modification { it.listEmbedded.forEach { it.value2 assign 3 } }))
            assertTrue(condition.readsResultOf(modification { it.listEmbedded.forEach { it.value2.plusAssign(1) } }))
        }
    }

    @Test
    fun readsResultOfStructuredMasks() {
        val embedded = modification<LargeTestModel> { it.embedded assign ClassUsedForEmbedding() }
        assertTrue(condition<LargeTestModel> { it.embedded.value2 gt 3 }.readsResultOf(embedded))
        assertTrue(condition<LargeTestModel> { it.embedded eq ClassUsedForEmbedding() }.readsResultOf(embedded))
        assertFalse(condition<LargeTestModel> { it.int gt 3 }.readsResultOf(embedded))

        val nullable = modification<LargeTestModel> { it.embeddedNullable assign null }
        assertTrue(condition<LargeTestModel> { it.embeddedNullable.notNull.value1 eq "x" }.readsResultOf(nullable))
        assertTrue(condition<LargeTestModel> { it.embeddedNullable.notNull.mapCondition(Condition.Always) }.readsResultOf(nullable))

        val list = modification<LargeTestModel> { it.list assign listOf() }
        assertTrue(condition<LargeTestModel> { it.list.any { it eq 7 } }.readsResultOf(list))
        assertTrue(condition<LargeTestModel> { it.list.any { it.always } }.readsResultOf(list))
        assertTrue(condition<LargeTestModel> { it.set.any { it eq 7 } }.readsResultOf(modification { it.set assign setOf() }))

        val perElement = modification<LargeTestModel> { it.listEmbedded.forEach { it.value1 assign "" } }
        assertTrue(condition<LargeTestModel> { it.listEmbedded.any { it.value1 eq "x" } }.readsResultOf(perElement))
        assertFalse(condition<LargeTestModel> { it.listEmbedded.any { it.value2 eq 3 } }.readsResultOf(perElement))
    }

    @Test
    fun partialOrWithNoPassingBranchIsFalse() {
        val partial = partialOf<LargeTestModel> { it.byte assign 0.toByte(); it.short assign 0.toShort() }
        assertEquals(false, condition<LargeTestModel> { (it.byte eq 1) or (it.short eq 1) }(partial))
    }

    @Test
    fun testConditionField() {
        (path<LargeTestModel>().int).let { modification ->
            assertTrue((path<LargeTestModel>().int eq 3).reads(modification))
            assertTrue((path<LargeTestModel>().int gt 3).reads(modification))
            assertFalse((path<LargeTestModel>().short eq 3).reads(modification))
        }
        (path<LargeTestModel>().intNullable).let { modification ->
            assertTrue((path<LargeTestModel>().intNullable eq 3).reads(modification))
            assertTrue((path<LargeTestModel>().intNullable.notNull gt 3).reads(modification))
            assertFalse((path<LargeTestModel>().short eq 3).reads(modification))
        }
    }

    @Test
    fun testGuaranteedAfter() {
        (path<LargeTestModel>().int gt 2).let { condition ->
            assertFalse(condition.guaranteedAfter(modification { it.int assign 2 }))
            assertTrue(condition.guaranteedAfter(modification { it.int assign 3 }))
            assertFalse(condition.guaranteedAfter(modification { it.int += 1 }))
            assertTrue(condition.guaranteedAfter(modification { it.short += 1 }))
        }
        (path<LargeTestModel>().intNullable.notNull gt 2).let { condition ->
            assertFalse(condition.guaranteedAfter(modification { it.intNullable assign 2 }))
            assertFalse(condition.guaranteedAfter(modification { it.intNullable assign null }))
            assertTrue(condition.guaranteedAfter(modification { it.intNullable assign 3 }))
            assertFalse(condition.guaranteedAfter(modification { it.intNullable.notNull assign 2 }))
            assertTrue(condition.guaranteedAfter(modification { it.intNullable.notNull assign 3 }))
            assertFalse(condition.guaranteedAfter(modification { it.intNullable.notNull plusAssign 1 }))
        }
        (path<LargeTestModel>().listEmbedded.elements.value2 gt 2).let { condition ->
            assertFalse(condition.guaranteedAfter(modification { it.listEmbedded.forEach { it.value2 assign 2 } }))
            assertTrue(condition.guaranteedAfter(modification { it.listEmbedded.forEach { it.value2 assign 3 } }))
            assertFalse(condition.guaranteedAfter(modification { it.listEmbedded.forEach { it.value2.plusAssign(1) } }))
        }
    }
}