package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.HealthCheckTestModel
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.mongodb.bson.KBson
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmptyModifierPruneTest {
    @Test
    fun pruneRemovesOnlyEmptyModifiers() {
        val doc = Document()
            .append("\$set", Document())
            .append("\$setOnInsert", Document("_id", "x"))
            .append("\$inc", Document("count", 1))
        doc.pruneEmptyModifiers()
        assertFalse(doc.containsKey("\$set"), "empty \$set should be dropped")
        assertTrue(doc.containsKey("\$setOnInsert"))
        assertTrue(doc.containsKey("\$inc"))
    }

    @Test
    fun upsertOfIdOnlyModelProducesNoEmptyModifier() {
        // Reproduces the health-check path: upserting a model whose only field is _id. The whole-value
        // assignment yields an empty $set, which DocumentDB rejects ("Modifiers operate on fields").
        val bson = KBson()
        val model = HealthCheckTestModel("HealthCheck")
        val update = Modification.Assign(model).bson(HealthCheckTestModel.serializer(), bson)
        update.upsert(model, HealthCheckTestModel.serializer(), bson)
        update.document.forEach { (key, value) ->
            if (value is Document) assertTrue(value.isNotEmpty(), "modifier $key must not be an empty document")
        }
    }
}
