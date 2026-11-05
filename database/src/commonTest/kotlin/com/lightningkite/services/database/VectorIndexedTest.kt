package com.lightningkite.services.database

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.optics.Field
import com.lightningkite.services.database.optics.fields
import com.lightningkite.services.embedding.EmbeddingModelId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*

@Serializable
data class Note(override val _id: String, val text: String, val topic: String, val hidden: Boolean = false) : HasId<String>

class VectorIndexedTest {
    private val definition = VectorIndexDefinition("Note", String.serializer(), VectorSpace(EmbeddingModelId("test-model"), 3))

    private val axes = mapOf("cats" to Embedding.of(1f, 0f, 0f), "dogs" to Embedding.of(0f, 1f, 0f), "fish" to Embedding.of(0f, 0f, 1f))
    private val embedded = ArrayList<List<String>>()

    private fun <T> field(serializer: KSerializer<T>, name: String): Field<T, Any?> {
        @Suppress("UNCHECKED_CAST")
        return serializer.fields!!.single { it.name == name } as Field<T, Any?>
    }

    private val projection = VectorProjection<Note, String>(
        condition = Condition.OnField(field(Note.serializer(), "hidden"), Condition.Equal(false)),
        embed = { notes -> embedded += notes.map { it.text }; notes.map { axes.getValue(it.text) } },
        reembedWhen = { old, new -> old.text != new.text },
    )

    private val database = InMemoryDatabase("test", context = TestSettingContext())
    private val notes = database.table(DatabaseTableDefinition(Note.serializer(), "Note"))

    private fun assign(name: String, value: Any?) = Modification.OnField(field(Note.serializer(), name), Modification.Assign(value))

    @Test
    fun `a table keeps its index in step, embedding only what changed`() = runTest {
        val index = database.index(definition)
        val table = notes.vectorIndexed(index, projection)

        table.insert(listOf(Note("1", "cats", "pets"), Note("2", "dogs", "pets")))
        assertEquals(listOf(listOf("cats", "dogs")), embedded, "A batch is embedded in one call")
        assertEquals(axes["cats"], index.get(listOf("1")).single().embedding)

        table.updateOneById("1", assign("topic", "wild"))
        assertEquals(1, embedded.size, "A change to what isn't embedded keeps the embedding")

        table.updateOneById("1", assign("text", "fish"))
        assertEquals(listOf("fish"), embedded.last())
        assertEquals(axes["fish"], index.get(listOf("1")).single().embedding)

        table.upsertOneById("3", Note("3", "dogs", "pets"))
        assertEquals(listOf("dogs"), embedded.last())

        table.deleteOneById("2")
        assertEquals(setOf("1", "3"), index.ids().toList().toSet())
    }

    @Test
    fun `only rows matching the condition are indexed`() = runTest {
        val index = database.index(definition)
        val table = notes.vectorIndexed(index, projection)

        table.insert(listOf(Note("1", "cats", "pets"), Note("2", "dogs", "pets", hidden = true)))
        assertEquals(listOf(listOf("cats")), embedded)
        assertEquals(setOf("1"), index.ids().toList().toSet())

        table.updateOneById("1", assign("hidden", true))
        assertEquals(setOf(), index.ids().toList().toSet(), "A row that stops matching leaves the index")

        table.updateOneById("2", assign("hidden", false))
        assertEquals(listOf("dogs"), embedded.last(), "A row that starts matching is embedded")
        assertEquals(setOf("2"), index.ids().toList().toSet())
    }

    @Test
    fun `rebuild backfills matching rows and drops every other entry`() = runTest {
        val index = database.index(definition)
        notes.insert(listOf(Note("1", "cats", "pets"), Note("2", "dogs", "pets"), Note("3", "fish", "pets", hidden = true)))
        index.upsert(listOf(VectorIndexEntry("gone", Embedding.of(1f, 0f, 0f)), VectorIndexEntry("3", Embedding.of(0f, 0f, 1f))))

        index.rebuild(notes, projection)

        assertEquals(setOf("1", "2"), index.ids().toList().toSet())
        assertEquals(axes["dogs"], index.get(listOf("2")).single().embedding)
    }

    @Test
    fun `ram vector database settings build the in-memory database`() {
        Database.Settings.supports("ram")  // Loads the companion that registers `ram`.
        assertIs<InMemoryDatabase>(VectorDatabase.Settings("ram")("vectors", TestSettingContext()))
    }
}
