package com.lightningkite.lightningdb

import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.bson.BsonDocument

public interface MongoCollectionAccess {
    public suspend fun <T> wholeDb(
        action: suspend MongoDatabase.() -> T
    ): T
    public suspend fun <T> run(
        action: suspend MongoCollection<BsonDocument>.() -> T
    ): T
}