package com.lightningkite.services.database.sql

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@Serializable
data class NullableValuesTestModel(
    override val _id: Uuid = Uuid.random(),
    val byName: Map<String, ClassUsedForEmbedding?> = mapOf(),
) : HasId<Uuid>

/** Present elements of a list or map of nullable classes used to read back as null. */
class SqlNullableElementsTest {
    private val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect("jdbc:h2:mem:nullableElementsTest;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
        }
    }

    @Test
    fun listElements() = runTest {
        val table = database.prepare(DatabaseTableDefinition<NullableEmbedsTestModel>("nullableListElements"))
        val model = NullableEmbedsTestModel(embeds = listOf(null, ClassUsedForEmbedding("a", 2)))
        table.insert(listOf(model))
        assertEquals(listOf(model), table.find(Condition.Always).toList())
    }

    @Test
    fun mapValues() = runTest {
        val table = database.prepare(DatabaseTableDefinition(NullableValuesTestModel.serializer(), "nullableMapValues"))
        val model = NullableValuesTestModel(byName = mapOf("none" to null, "some" to ClassUsedForEmbedding("a", 2)))
        table.insert(listOf(model))
        assertEquals(listOf(model), table.find(Condition.Always).toList())
    }
}
