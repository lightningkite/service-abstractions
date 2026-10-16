package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.bson.BsonDocument
import org.bson.BsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * notNull/asType modifications must behave in MongoDB exactly as they do in memory. The shared suites compare parsed
 * models; these check what's actually stored, which parsing can hide (unknown fields are ignored, missing fields get
 * defaults).
 */
class ConditionalModificationTest {
    // Each test's collection holds a single document.
    private fun raw(name: String) = db().database.getCollection(name, BsonDocument::class.java)

    @Test
    fun notNull_leavesMissingFieldMissing() = runTest {
        val name = "ConditionalModificationTest_missing"
        val collection = db().prepare(DatabaseTableDefinition<LargeTestModel>(name))
        val item = LargeTestModel()
        collection.insertOne(item)
        // A document written before the field existed.
        raw(name).updateOne(Filters.empty(), BsonDocument("\$unset", BsonDocument("embeddedNullable", BsonString(""))))

        collection.updateOneById(item._id, modification { it.embeddedNullable.notNull.value1 assign "forged" })

        val stored = raw(name).find(Filters.empty()).first()
        assertFalse(stored.containsKey("embeddedNullable"), "Stored: $stored")
    }

    @Test
    fun asType_otherVariant_writesNothing() = runTest {
        val name = "ConditionalModificationTest_otherVariant"
        val collection = db().prepare(DatabaseTableDefinition<SealedPathTestModel>(name))
        val item = SealedPathTestModel(
            shape = Shape.Square(2, "a"),
            shapes = listOf(Shape.Square(1), Shape.Empty),
        )
        collection.insertOne(item)
        val before = raw(name).find(Filters.empty()).first()

        collection.updateOneById(item._id, modification { it.shape.asCircle.radius += 1 })
        collection.updateOneById(item._id, modification { it.shapes.forEach { it.asCircle.radius += 1 } })

        assertEquals(before, raw(name).find(Filters.empty()).first())
    }

    // The check goes into the filter, so any operator can sit beside it.
    @Test
    fun notNull_withListRemove_applies() = runTest {
        val collection = db().prepare(DatabaseTableDefinition<LargeTestModel>("ConditionalModificationTest_listRemove"))
        val item = LargeTestModel(intNullable = 1, list = listOf(1, 5))
        collection.insertOne(item)
        val modification = modification<LargeTestModel> {
            it.intNullable.notNull += 1
            it.list.removeAll { it gt 3 }
        }
        collection.updateOneById(item._id, modification)
        assertEquals(modification(item), collection.get(item._id))
    }
}
