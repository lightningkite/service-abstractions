package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.DataClassPathPartial
import com.mongodb.client.model.changestream.UpdateDescription
import org.bson.*
import org.bson.codecs.Codec
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecRegistry

public fun BsonValue.setPath(path: String, value: BsonValue) {
    val pathParts = path.split('.')
    var current: BsonValue = this
    for (partIndex in 0 until pathParts.lastIndex) {
        when (current) {
            is BsonDocument -> current = current[pathParts[partIndex]] ?: return
            is BsonArray -> current = current[pathParts[partIndex].toInt()] ?: return
        }
    }
    when (current) {
        is BsonDocument -> current[pathParts.last()] = value
        is BsonArray -> {
            val index = pathParts.last().toInt()
            if (index > current.lastIndex)
                current.add(value)
            else
                current[index] = value
        }
    }
}

public fun BsonValue.removePath(path: String) {
    val pathParts = path.split('.')
    var current: BsonValue = this
    for (partIndex in 0 until pathParts.lastIndex) {
        when (current) {
            is BsonDocument -> current = current[pathParts[partIndex]] ?: return
            is BsonArray -> current = current[pathParts[partIndex].toInt()] ?: return
        }
    }
    when (current) {
        is BsonDocument -> current.remove(pathParts.last())
        is BsonArray -> current.removeAt(pathParts.last().toInt())
    }
}

public fun <T : Any> Codec<T>.fromUpdateDescription(on: T, updateDescription: UpdateDescription): T {
    val doc = BsonDocument()
    val writer = BsonDocumentWriter(doc)
    this.encode(writer, on, EncoderContext.builder().build())
    updateDescription.removedFields?.forEach { removal ->
        doc.removePath(removal)
    }
    updateDescription.updatedFields?.forEach { update ->
        doc.setPath(update.key, update.value)
    }
    return this.decode(BsonDocumentReader(doc), DecoderContext.builder().build())
}

public fun <T> Codec<T>.fromDocument(doc: Document, registry: CodecRegistry): T {
    return this.decode(
        BsonDocumentReader(BsonDocumentWrapper.asBsonDocument(doc, registry)),
        DecoderContext.builder().build()
    )
}

public val DataClassPathPartial<*>.mongo: String get() = properties.filter { !it.inline }.joinToString(".") { it.name }
