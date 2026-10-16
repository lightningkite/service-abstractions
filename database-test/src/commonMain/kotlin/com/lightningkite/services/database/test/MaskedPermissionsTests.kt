package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Masked values must not be learnable through anything a [ModelPermissionsTable] returns: filtered counts,
 * aggregates, partial reads, the counts of writes and deletes, or the results of writes whose effect depends on them.
 */
abstract class MaskedPermissionsTests {

    abstract val database: Database

    private suspend fun table(name: String, permissions: ModelPermissions<LargeTestModel>): Pair<Table<LargeTestModel>, Table<LargeTestModel>> {
        val raw = database.prepare(DatabaseTableDefinition<LargeTestModel>(name))
        raw.deleteManyIgnoringOld(Condition.Always)
        return raw to raw.withPermissions(permissions)
    }

    @Test
    fun filteringInsideMaskedStructuresRevealsNothing() = runTest {
        val (raw, table) = table(
            "maskedStructures", ModelPermissions(
                all = Condition.Always,
                readMask = mask<LargeTestModel> {
                    it.embedded.mask(ClassUsedForEmbedding("masked", 0))
                    it.embeddedNullable.mask(null)
                    it.list.mask(listOf())
                    it.set.mask(setOf())
                    it.listEmbedded.mask(listOf())
                }
            )
        )
        raw.insertOne(
            LargeTestModel(
                int = 5,
                embedded = ClassUsedForEmbedding("secret", 99),
                embeddedNullable = ClassUsedForEmbedding("secret", 99),
                list = listOf(7),
                set = setOf(7),
                listEmbedded = listOf(ClassUsedForEmbedding("secret", 99)),
            )
        )

        assertEquals(1, table.count(condition { it.int eq 5 }), "unmasked fields stay filterable")
        assertEquals(0, table.count(condition { it.embedded.value2 eq 99 }))
        assertEquals(0, table.count(condition { it.embedded eq ClassUsedForEmbedding("secret", 99) }))
        assertEquals(0, table.count(condition { it.embeddedNullable.notNull.value1 eq "secret" }))
        assertEquals(0, table.count(condition { it.list.any { it eq 7 } }))
        assertEquals(0, table.count(condition { it.set.any { it eq 7 } }))
        assertEquals(0, table.count(condition { it.listEmbedded.any { it.value2 eq 99 } }))
        assertEquals(0, table.find(condition { it.embedded.value2 eq 99 }).toList().size)
        assertEquals(mapOf(), table.groupCount(condition { it.list.any { it eq 7 } }, path<LargeTestModel>().byte))
    }

    @Test
    fun searchNestedUnderAnUnmaskedFieldRevealsNothing() = runTest {
        val (raw, table) = table(
            "maskedNestedSearch", ModelPermissions(
                all = Condition.Always,
                readMask = mask<LargeTestModel> { it.embedded.value1.mask("") }
            )
        )
        raw.insertOne(LargeTestModel(int = 5, embedded = ClassUsedForEmbedding("secret", 1)))

        assertEquals(0, table.count(path<LargeTestModel>().embedded.mapCondition(Condition.FullTextSearch("secret"))))
        // Only MongoDB can fail this one: its search covers the whole text index, wherever the condition sits.
        assertEquals(0, table.count(path<LargeTestModel>().int.mapCondition(Condition.FullTextSearch("secret"))))
    }

    @Test
    fun aggregatesDoNotRevealMaskedValues() = runTest {
        val (raw, table) = table(
            "maskedAggregates", ModelPermissions(
                all = Condition.Always,
                readMask = mask<LargeTestModel> { it.int.mask(0) }
            )
        )
        val secret = raw.insertOne(LargeTestModel(int = 1234))

        assertNull(table.aggregate(Aggregate.Sum, condition { it._id eq secret._id }, path<LargeTestModel>().int))
        assertNull(
            table.groupAggregate(Aggregate.Sum, condition { it._id eq secret._id }, path<LargeTestModel>().byte, path<LargeTestModel>().int)[0]
        )
    }

    @Test
    fun partialReadsAreMasked() = runTest {
        val (raw, table) = table(
            "maskedPartials", ModelPermissions(
                all = Condition.Always,
                readMask = mask<LargeTestModel> {
                    it.int.mask(0, unless = condition<LargeTestModel> { (it.byte eq 1) or (it.short eq 1) })
                    it.embedded.mask(ClassUsedForEmbedding("masked", 0))
                }
            )
        )
        raw.insertOne(LargeTestModel(byte = 0, short = 0, int = 1234, embedded = ClassUsedForEmbedding("secret", 99)))

        val int = table.findPartial(setOf(path<LargeTestModel>().int), Condition.Always).toList().single()
        assertEquals(0, int.parts[LargeTestModel_int])

        val nested = table.findPartial(setOf(path<LargeTestModel>().embedded.value1), Condition.Always).toList().single()
        assertEquals("masked", (nested.parts[LargeTestModel_embedded] as Partial<*>).parts[ClassUsedForEmbedding_value1])

        val whole = table.findPartial(setOf(path<LargeTestModel>().embedded), Condition.Always).toList().single()
        assertEquals(
            ClassUsedForEmbedding("masked", 0),
            (whole.parts[LargeTestModel_embedded] as Partial<ClassUsedForEmbedding>).total(ClassUsedForEmbedding.serializer())
        )
    }

    @Test
    fun partialReadsThroughAMaskedElementHideIt() = runTest {
        val (raw, table) = table(
            "maskedElementPartials", ModelPermissions(
                all = Condition.Always,
                readMask = mask<LargeTestModel> { it.listEmbedded.elements.value1.mask("masked") }
            )
        )
        raw.insertOne(LargeTestModel(listEmbedded = listOf(ClassUsedForEmbedding("secret", 0))))

        val read = table.findPartial(setOf(path<LargeTestModel>().listEmbedded.elements.value1), Condition.Always).toList().single()
        assertFalse("secret" in read.toString(), read.toString())
    }

    @Test
    fun writesThatReadMaskedElementsRevealNothing() = runTest {
        val (raw, table) = table(
            "maskedElementWrites", ModelPermissions(
                all = Condition.Always,
                readMask = mask<LargeTestModel> {
                    it.listEmbedded.elements.value1.mask("masked")
                    it.setEmbedded.elements.value1.mask("masked")
                },
            )
        )
        val row = raw.insertOne(
            LargeTestModel(
                listEmbedded = listOf(ClassUsedForEmbedding("secret", 0), ClassUsedForEmbedding("other", 0)),
                setEmbedded = setOf(ClassUsedForEmbedding("secret", 3)),
            )
        )
        suspend fun write(modification: Modification<LargeTestModel>) = table.updateOne(condition { it._id eq row._id }, modification)

        assertNull(write(modification { it.listEmbedded.forEachIf({ it.value1 eq "secret" }) { it.value2 assign 2 } }).new)
        assertNull(write(modification { it.listEmbedded.removeAll { it.value1 eq "secret" } }).new)
        assertNull(write(modification { it.listEmbedded -= ClassUsedForEmbedding("secret", 0) }).new)
        assertNull(write(modification { it.setEmbedded -= ClassUsedForEmbedding("secret", 3) }).new)
        assertNull(write(modification { it.setEmbedded += ClassUsedForEmbedding("secret", 3) }).new)
        assertEquals(row, raw.get(row._id))

        val unfiltered = write(modification { it.listEmbedded.forEach { it.value2 assign 1 } }).new
        assertEquals(listOf(ClassUsedForEmbedding("masked", 1), ClassUsedForEmbedding("masked", 1)), unfiltered?.listEmbedded)
        assertNotNull(write(modification { it.listEmbedded.forEachIf({ it.value2 eq 1 }) { it.value2 assign 2 } }).new)
    }

    @Test
    fun writesAndDeletesRevealNothingUnreadable() = runTest {
        val (raw, table) = table(
            "maskedWrites", ModelPermissions(
                create = Condition.Always,
                read = condition { it.byte eq 1 },
                readMask = mask<LargeTestModel> { it.int.mask(0) },
                update = Condition.Always,
                delete = Condition.Always,
            )
        )
        val hidden = raw.insertOne(LargeTestModel(byte = 0, int = 1234))
        val masked = raw.insertOne(LargeTestModel(byte = 1, int = 1234))
        val touch = modification<LargeTestModel> { it.boolean assign true }

        assertEquals(1, table.updateManyIgnoringResult(Condition.Always, touch), "only the readable row is writable")
        assertEquals(0, table.updateManyIgnoringResult(condition { it.int eq 1234 }, touch))
        assertEquals(0, table.updateMany(condition { it.int eq 1234 }, touch).changes.size)
        assertFalse(table.updateOneIgnoringResult(condition { it.int eq 1234 }, touch))
        assertNull(table.updateOne(condition { it._id eq hidden._id }, touch).old)
        assertFalse(table.replaceOneIgnoringResult(condition { it._id eq hidden._id }, hidden.copy(byte = 1)))
        assertEquals(0, table.deleteManyIgnoringOld(condition { it.int eq 1234 }))
        assertFalse(table.deleteOneIgnoringOld(condition { it._id eq hidden._id }))
        assertNull(table.deleteOne(condition { it.int eq 1234 }))
        assertEquals(listOf(), table.deleteMany(condition { it._id eq hidden._id }))

        assertEquals(2, raw.count())
        assertTrue(table.deleteOneIgnoringOld(condition { it._id eq masked._id }))
    }

}
