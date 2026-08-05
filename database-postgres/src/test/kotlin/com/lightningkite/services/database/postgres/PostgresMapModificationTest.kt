package com.lightningkite.services.database.postgres

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.ClassRule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for release review finding 8 (BLOCKER): `Modification.Combine` and
 * `Modification.RemoveKeys` were bare `TODO()` in the Postgres condition/modification mapper,
 * crashing with `NotImplementedError` instead of degrading or working. Maps are stored as a pair of
 * parallel Postgres arrays (one for keys, one for values — see `SerialDescriptorTable.columnType`'s
 * `StructureKind.MAP` branch), not JSONB, so these are implemented with array-unnest/rebuild via the
 * existing [MapOp] helper (already used for List/Set per-element operations on the same storage
 * shape) rather than `jsonb_set`.
 *
 * (`Modification.ModifyByKey` was implemented here the same way for a time, but was removed from the
 * library entirely in 1.3 -- see its removal note in `database-shared/.../Modification.kt` -- so this
 * file no longer covers it.)
 *
 * Unrelated finding still worth flagging: a map value type that is itself a collection (e.g.
 * `Map<String, List<Int>>`) can't be persisted at all on this driver today -- `ArrayColumnType.sqlType()`
 * (see `arraySupport.kt`) emits an invalid doubled `... ARRAY ARRAY` column type for a column whose
 * element type is itself an array, so `CREATE TABLE` fails outright. Pre-existing and out of scope
 * here; flagged for a follow-up.
 */
class PostgresMapModificationTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = EmbeddedPostgresRules.singleInstance()
    }

    private val database: com.lightningkite.services.database.Database by lazy {
        PostgresDatabase(
            "test",
            TestSettingContext(EmptySerializersModule())
        ) { PooledDatabase(Database.connect(postgres.embeddedPostgres.postgresDatabase), null) }
    }

    @Test
    fun combineAddsAndOverwritesKeys() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("combineAddsAndOverwritesKeys"))
        val item = LargeTestModel(map = mapOf("a" to 1, "b" to 2))
        collection.insertOne(item)

        // In-memory reference: on + map -- overwrites "a", adds "c".
        collection.updateOneById(item._id, modification { it.map += mapOf("a" to 10, "c" to 3) })

        val result = collection.get(item._id)!!
        assertEquals(mapOf("a" to 10, "b" to 2, "c" to 3), result.map)
    }

    @Test
    fun removeKeysDropsOnlyTheGivenKeys() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("removeKeysDropsOnlyTheGivenKeys"))
        val item = LargeTestModel(map = mapOf("a" to 1, "b" to 2, "c" to 3))
        collection.insertOne(item)

        collection.updateOneById(item._id, modification { it.map.removeKeys(setOf("a", "c")) })

        val result = collection.get(item._id)!!
        assertEquals(mapOf("b" to 2), result.map)
    }
}
