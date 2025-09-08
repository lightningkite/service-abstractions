package com.lightningkite.services.database.mongodb.bson

import com.lightningkite.services.database.mongodb.bson.utils.BsonCodecUtils
import kotlinx.serialization.*
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.bson.*
import org.bson.codecs.*



@OptIn(ExperimentalSerializationApi::class)
internal class KBson(override val serializersModule: SerializersModule = EmptySerializersModule(), private val configuration: BsonConfiguration = BsonConfiguration(explicitNulls = true)) : SerialFormat, BinaryFormat {
    fun <T> stringify(serializer: SerializationStrategy<T>, obj: T): BsonDocument {
        val doc = BsonDocument()
        val writer = BsonDocumentWriter(doc)

        BsonCodecUtils.createBsonEncoder(writer, serializersModule, configuration).encodeSerializableValue(serializer, obj)
        writer.flush()

        return doc
    }
    fun <T> parse(deserializer: DeserializationStrategy<T>, doc: BsonDocument): T {
        return BsonCodecUtils.createBsonDecoder(doc.asBsonReader() as AbstractBsonReader, serializersModule, configuration).decodeSerializableValue(deserializer)
    }

    override fun <T> encodeToByteArray(
        serializer: SerializationStrategy<T>,
        value: T
    ): ByteArray = this.stringify(serializer, value).toByteArray()

    override fun <T> decodeFromByteArray(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray
    ): T = BsonCodecUtils.createBsonDecoder(RawBsonDocument(bytes).asBsonReader() as AbstractBsonReader, serializersModule, configuration).decodeSerializableValue(deserializer)

    companion object {
        val default = KBson()
    }
}

internal fun BsonDocument.toDocument(): Document {
    return DocumentCodec().decode(this.asBsonReader(), DecoderContext.builder().build())
}

internal fun BsonDocument.toByteArray(): ByteArray {
    return RawBsonDocumentCodec()
            .decode(this.asBsonReader(), DecoderContext.builder().build())
            .byteBuffer
            .array()
}
