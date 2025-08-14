package com.lightningkite.services.database.mongodb.bson

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import org.bson.BsonType
import org.bson.UuidRepresentation
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import java.math.BigDecimal
import java.util.Date
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
internal annotation class NonEncodeNull


@Serializer(forClass = Date::class)
internal object DateSerializer : KSerializer<Date> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DateSerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Date) {
        encoder as BsonEncoder
        encoder.encodeDateTime(value.time)
    }

    @InternalSerializationApi
    override fun deserialize(decoder: Decoder): Date {
        return when (decoder) {
            is FlexibleDecoder -> {
                Date(
                        when (decoder.reader.currentBsonType) {
                            BsonType.STRING -> decoder.decodeString().toLong()
                            BsonType.DATE_TIME -> decoder.reader.readDateTime()
                            else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading date")
                        }
                )
            }
            else -> throw SerializationException("Unknown decoder type")
        }
    }
}


@Serializer(forClass = BigDecimal::class)
internal object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("BigDecimalSerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder as BsonEncoder
        encoder.encodeDecimal128(Decimal128(value))
    }

    @InternalSerializationApi
    override fun deserialize(decoder: Decoder): BigDecimal {
        return when (decoder) {
            is FlexibleDecoder -> {
                when (decoder.reader.currentBsonType) {
                    BsonType.STRING -> BigDecimal(decoder.decodeString())
                    BsonType.DECIMAL128 -> decoder.reader.readDecimal128().bigDecimalValue()
                    else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading decimal128")
                }
            }
            else -> throw SerializationException("Unknown decoder type")
        }
    }
}


@Serializer(forClass = ByteArray::class)
internal object ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ByteArraySerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder as BsonEncoder
        encoder.encodeByteArray(value)
    }

    @InternalSerializationApi
    override fun deserialize(decoder: Decoder): ByteArray {
        return when (decoder) {
            is FlexibleDecoder -> {
                decoder.reader.readBinaryData().data
            }
            else -> throw SerializationException("Unknown decoder type")
        }

    }
}


@Serializer(forClass = ObjectId::class)
internal object ObjectIdSerializer : KSerializer<ObjectId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ObjectIdSerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ObjectId) {
        encoder as BsonEncoder
        encoder.encodeObjectId(value)
    }

    @InternalSerializationApi
    override fun deserialize(decoder: Decoder): ObjectId {
        return when (decoder) {
            is FlexibleDecoder -> {
                when (decoder.reader.currentBsonType) {
                    BsonType.STRING -> ObjectId(decoder.decodeString())
                    BsonType.OBJECT_ID -> decoder.reader.readObjectId()
                    else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading object id")
                }
            }
            else -> throw SerializationException("Unknown decoder type")
        }

    }
}

@Serializer(forClass = Uuid::class)
internal object UuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UuidSerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uuid) {
        encoder as BsonEncoder
        encoder.encodeUUID(value.toJavaUuid(), UuidRepresentation.STANDARD)
    }

    override fun deserialize(decoder: Decoder): Uuid {
        return when (decoder) {
            is FlexibleDecoder -> {
                when (decoder.reader.currentBsonType) {
                    BsonType.STRING -> {
                        Uuid.parse(decoder.decodeString())
                    }
                    BsonType.BINARY -> {
                        decoder.reader.readBinaryData().asUuid(UuidRepresentation.STANDARD).toKotlinUuid()
                    }
                    else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading object id")
                }
            }
            else -> throw SerializationException("Unknown decoder type")
        }
    }
}

internal val DefaultModule = SerializersModule {
    contextual(ObjectId::class, ObjectIdSerializer)
    contextual(BigDecimal::class, BigDecimalSerializer)
    contextual(ByteArray::class, ByteArraySerializer)
    contextual(Date::class, DateSerializer)
    contextual(Uuid::class, UuidSerializer)
}
