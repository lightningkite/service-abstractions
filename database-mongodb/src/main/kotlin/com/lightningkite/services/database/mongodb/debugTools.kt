package com.lightningkite.services.database.mongodb

import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.database.*
import com.lightningkite.services.database.mongodb.bson.BsonConfiguration
import com.lightningkite.services.database.mongodb.bson.JsonBsonDecoderImpl
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.bson.BsonDocument
import org.bson.BsonDocumentReader

public fun MongoDatabase.export(): DatabaseExport {
    return database
        .listCollectionNames()
        .map { name ->
            TableExport(
                tableName = name,
                items = database
                    .getCollection(name, BsonDocument::class.java)
                    .aggregate(emptyList())
                    .map { document ->
                        JsonBsonDecoderImpl(
                            BsonDocumentReader(document).apply { readBsonType() },  // for some stupid reason you have to manually initialize this.
                            context.internalSerializersModule,
                            configuration = BsonConfiguration(explicitNulls = true)
                        )
                            .decodeJsonElement()
                            .jsonObject
                    }
            )
        }
}

public suspend fun MongoDatabase.exportToJsonFiles(folder: KFile, json: Json) {
    this.export().writeToJsonFiles(folder, json)
}