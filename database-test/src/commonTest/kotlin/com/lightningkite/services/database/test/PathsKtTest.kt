package com.lightningkite.services.database.test

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.database.*
import kotlin.test.*

class PathsKtTest {

    @Test
    fun testModificationField() {
        (path<LargeTestModel>().int).let { modification ->
            assertTrue(Modification<LargeTestModel> { it.int assign 3 }.affects(modification))
            assertTrue(Modification<LargeTestModel> { it.int += 3 }.affects(modification))
            assertFalse(Modification<LargeTestModel> { it.short assign 3 }.affects(modification))
        }
        (path<LargeTestModel>().intNullable).let { modification ->
            assertTrue(Modification<LargeTestModel> { it.intNullable assign 3 }.affects(modification))
            assertTrue(Modification<LargeTestModel>() { it.intNullable.notNull += 3 }.affects(modification))
            assertFalse(Modification<LargeTestModel> { it.short assign 3 }.affects(modification))
        }
    }

    @Test
    fun testConditionModification() {
        Modification<LargeTestModel>() { it.int assign 2 }.let { modification ->
            assertTrue((path<LargeTestModel>().int eq 3).readsResultOf(modification))
            assertTrue((path<LargeTestModel>().int gt 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().short eq 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().always).readsResultOf(modification))
        }
        Modification<LargeTestModel>() { it.intNullable assign 2 }.let { modification ->
            assertTrue((path<LargeTestModel>().intNullable eq 3).readsResultOf(modification))
            assertTrue((path<LargeTestModel>().intNullable.notNull gt 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().short eq 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().always).readsResultOf(modification))
        }
        (path<LargeTestModel>().listEmbedded.elements.value2 gt 2).let { condition ->
            assertFalse(condition.readsResultOf(Modification { it.intNullable assign 2 }))
            assertTrue(condition.readsResultOf(Modification { it.listEmbedded.forEach { it.value2 assign 2 } }))
            assertTrue(condition.readsResultOf(Modification { it.listEmbedded.forEach { it.value2 assign 3 } }))
            assertTrue(condition.readsResultOf(Modification { it.listEmbedded.forEach { it.value2.plusAssign(1) } }))
        }
    }

    @Test
    fun readsResultOfStructuredMasks() {
        val embedded = Modification<LargeTestModel> { it.embedded assign ClassUsedForEmbedding() }
        assertTrue(Condition<LargeTestModel> { it.embedded.value2 gt 3 }.readsResultOf(embedded))
        assertTrue(Condition<LargeTestModel> { it.embedded eq ClassUsedForEmbedding() }.readsResultOf(embedded))
        assertFalse(Condition<LargeTestModel> { it.int gt 3 }.readsResultOf(embedded))

        val nullable = Modification<LargeTestModel> { it.embeddedNullable assign null }
        assertTrue(Condition<LargeTestModel> { it.embeddedNullable.notNull.value1 eq "x" }.readsResultOf(nullable))
        assertTrue(Condition<LargeTestModel> { it.embeddedNullable.notNull.mapCondition(Condition.Always) }.readsResultOf(nullable))

        val list = Modification<LargeTestModel> { it.list assign listOf() }
        assertTrue(Condition<LargeTestModel> { it.list.any { it eq 7 } }.readsResultOf(list))
        assertTrue(Condition<LargeTestModel> { it.list.any { it.always } }.readsResultOf(list))
        assertTrue(Condition<LargeTestModel> { it.set.any { it eq 7 } }.readsResultOf(Modification { it.set assign setOf() }))

        val perElement = Modification<LargeTestModel> { it.listEmbedded.forEach { it.value1 assign "" } }
        assertTrue(Condition<LargeTestModel> { it.listEmbedded.any { it.value1 eq "x" } }.readsResultOf(perElement))
        assertFalse(Condition<LargeTestModel> { it.listEmbedded.any { it.value2 eq 3 } }.readsResultOf(perElement))
    }

    @Test
    fun partialOrWithNoPassingBranchIsFalse() {
        val partial = partialOf<LargeTestModel> { it.byte assign 0.toByte(); it.short assign 0.toShort() }
        assertEquals(false, Condition<LargeTestModel> { (it.byte eq 1) or (it.short eq 1) }(partial))
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
            assertFalse(condition.guaranteedAfter(Modification { it.int assign 2 }))
            assertTrue(condition.guaranteedAfter(Modification { it.int assign 3 }))
            assertFalse(condition.guaranteedAfter(Modification { it.int += 1 }))
            assertTrue(condition.guaranteedAfter(Modification { it.short += 1 }))
        }
        (path<LargeTestModel>().intNullable.notNull gt 2).let { condition ->
            assertFalse(condition.guaranteedAfter(Modification { it.intNullable assign 2 }))
            assertFalse(condition.guaranteedAfter(Modification { it.intNullable assign null }))
            assertTrue(condition.guaranteedAfter(Modification { it.intNullable assign 3 }))
            assertFalse(condition.guaranteedAfter(Modification { it.intNullable.notNull assign 2 }))
            assertTrue(condition.guaranteedAfter(Modification { it.intNullable.notNull assign 3 }))
            assertFalse(condition.guaranteedAfter(Modification { it.intNullable.notNull plusAssign 1 }))
        }
        (path<LargeTestModel>().listEmbedded.elements.value2 gt 2).let { condition ->
            assertFalse(condition.guaranteedAfter(Modification { it.listEmbedded.forEach { it.value2 assign 2 } }))
            assertTrue(condition.guaranteedAfter(Modification { it.listEmbedded.forEach { it.value2 assign 3 } }))
            assertFalse(condition.guaranteedAfter(Modification { it.listEmbedded.forEach { it.value2.plusAssign(1) } }))
        }
    }
}