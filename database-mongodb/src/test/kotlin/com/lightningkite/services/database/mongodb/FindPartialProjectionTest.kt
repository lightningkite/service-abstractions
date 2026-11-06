package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.bson.BsonDocument
import org.bson.BsonString
import kotlin.test.Test
import kotlin.test.assertEquals

/** A projected [Table.findPartial] must match trimming the fully decoded model, even when stored keys are missing. */
class FindPartialProjectionTest {
    private fun raw(name: String) = db().database.getCollection(name, BsonDocument::class.java)

    @Test
    fun missingKeysGetTheirDefaults() = runTest {
        val name = "FindPartialProjectionTest_missing"
        val collection = db().prepare(DatabaseTableDefinition<LargeTestModel>(name))
        val item = LargeTestModel(int = 7, embedded = ClassUsedForEmbedding("kept", 5), embeddedNullable = ClassUsedForEmbedding("n", 3))
        collection.insertOne(item)
        // Documents written before these fields existed.
        raw(name).updateOne(Filters.empty(), BsonDocument("\$unset", BsonDocument("int", BsonString("")).append("embedded.value2", BsonString("")).append("embeddedNullable.value1", BsonString(""))))

        val model = Lens<LargeTestModel>()
        val fields: Set<AnyOptic<LargeTestModel>> = setOf(model.int, model.embedded.value2, model.embeddedNullable.notNull.value1, model.string)
        val full = collection.get(item._id)!!
        assertEquals(listOf(partialOf(full, fields)), collection.findPartial(fields, Condition.Always).toList())
    }
}
