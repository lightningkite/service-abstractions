package com.lightningkite.services.database.mongodb.bson

import org.bson.AbstractBsonReader
import org.bson.BsonType


internal fun AbstractBsonReader.readIntSafe(): Int = when (currentBsonType) {
    BsonType.DOUBLE -> readDouble().toInt()
    BsonType.INT32 -> readInt32().toInt()
    BsonType.INT64 -> readInt64().toInt()
    BsonType.DECIMAL128 -> readDecimal128().toInt()
    else -> throw IllegalStateException("Expected number type but got ${currentBsonType}")
}

internal fun AbstractBsonReader.readLongSafe(): Long = when (currentBsonType) {
    BsonType.DOUBLE -> readDouble().toLong()
    BsonType.INT32 -> readInt32().toLong()
    BsonType.INT64 -> readInt64().toLong()
    BsonType.DECIMAL128 -> readDecimal128().toLong()
    else -> throw IllegalStateException("Expected number type but got ${currentBsonType}")
}

internal fun AbstractBsonReader.readDoubleSafe(): Double = when (currentBsonType) {
    BsonType.DOUBLE -> readDouble().toDouble()
    BsonType.INT32 -> readInt32().toDouble()
    BsonType.INT64 -> readInt64().toDouble()
    BsonType.DECIMAL128 -> readDecimal128().toDouble()
    else -> throw IllegalStateException("Expected number type but got ${currentBsonType}")
}

internal fun AbstractBsonReader.readByteSafe(): Byte = when (currentBsonType) {
    BsonType.DOUBLE -> readDouble().toInt().toByte()
    BsonType.INT32 -> readInt32().toByte()
    BsonType.INT64 -> readInt64().toByte()
    BsonType.DECIMAL128 -> readDecimal128().toByte()
    else -> throw IllegalStateException("Expected number type but got ${currentBsonType}")
}

internal fun AbstractBsonReader.readShortSafe(): Short = when (currentBsonType) {
    BsonType.DOUBLE -> readDouble().toInt().toShort()
    BsonType.INT32 -> readInt32().toShort()
    BsonType.INT64 -> readInt64().toShort()
    BsonType.DECIMAL128 -> readDecimal128().toShort()
    else -> throw IllegalStateException("Expected number type but got ${currentBsonType}")
}

internal fun AbstractBsonReader.readFloatSafe(): Float = when (currentBsonType) {
    BsonType.DOUBLE -> readDouble().toInt().toFloat()
    BsonType.INT32 -> readInt32().toFloat()
    BsonType.INT64 -> readInt64().toFloat()
    BsonType.DECIMAL128 -> readDecimal128().toFloat()
    else -> throw IllegalStateException("Expected number type but got ${currentBsonType}")
}
