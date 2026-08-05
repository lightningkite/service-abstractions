package com.lightningkite.services.database.postgres

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.LargeTestModel
import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.ClassRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `deleteOne` without an `orderBy` is the only path that reaches the custom DELETE ... RETURNING
 * statement with a limit. The shared suite always passes an `orderBy`, which this backend rejects
 * up front, so that path is otherwise never executed against a real database.
 */
class DeleteOneLimitTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = EmbeddedPostgresRules.singleInstance()
    }

    private val database: com.lightningkite.services.database.Database by lazy {
        PostgresDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect(postgres.embeddedPostgres.postgresDatabase), null)
        }
    }

    @Test
    fun `deleteOne without orderBy deletes exactly one row and returns it`() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("test_deleteOne_noOrder"))
        collection.insert((1..3).map { LargeTestModel(int = it) })
        assertEquals(3, collection.count())

        val deleted = collection.deleteOne(condition = Condition.Always)

        assertNotNull(deleted, "deleteOne should return the row it removed")
        assertEquals(2, collection.count(), "exactly one row should have been deleted")
    }
}
