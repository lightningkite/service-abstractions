package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.*
import com.lightningkite.services.database.mongodb.bson.KBson
import com.lightningkite.services.database.test.*
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The generated BSON for notNull steps: updates stay plain operator documents with the checks in the filter, and
 * common queries don't get an extra null check.
 */
class CheckedUpdateBsonTest {
    private val bson = KBson()
    private val serializer = LargeTestModel.serializer()

    @Test
    fun checks_goIntoFilter() {
        val update = modification<LargeTestModel> {
            it.int += 1
            it.embeddedNullable.notNull.value1 assign "x"
            it.intNullable.notNull += 1
        }.bson(serializer, bson)
        assertEquals(
            Document("\$inc", Document("int", BsonInt32(1)).append("intNullable", BsonInt32(1)))
                .append("\$set", Document("embeddedNullable.value1", BsonString("x"))),
            update.document
        )
        assertEquals(
            Document(
                "\$and", listOf(
                    Document("\$and", listOf(Document("embeddedNullable", Document("\$ne", null)))),
                    Document("\$and", listOf(Document("intNullable", Document("\$ne", null)))),
                )
            ),
            update.check
        )
    }

    private fun query(condition: Condition<LargeTestModel>) = condition.bson(serializer, bson = bson)

    @Test
    fun notNull_nullRejectingConditions_keepTheirShape() {
        assertEquals(Document("intNullable", Document("\$eq", BsonInt32(1))), query(condition { it.intNullable.notNull eq 1 }))
        assertEquals(Document("intNullable", Document("\$gt", BsonInt32(1))), query(condition { it.intNullable.notNull gt 1 }))
        assertEquals(
            Document("embeddedNullable.value1", Document("\$eq", BsonString("a"))),
            query(condition { it.embeddedNullable.notNull.value1 eq "a" })
        )
        assertEquals(Document("map.k", Document("\$eq", BsonInt32(1))), query(path<LargeTestModel>().map.mapCondition(Condition.OnKey("k", Condition.Equal(1)))))
    }
}
