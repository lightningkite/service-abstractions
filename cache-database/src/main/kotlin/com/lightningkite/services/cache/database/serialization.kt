package com.lightningkite.services.cache.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

@OptIn(ExperimentalSerializationApi::class)
private class NumberEncoder(override val serializersModule: SerializersModule) : Encoder {
    var encodedInteger: Long? = null
    var encodedFloat: Double? = null

    override fun encodeByte(value: Byte) { encodedInteger = value.toLong() }
    override fun encodeShort(value: Short) { encodedInteger = value.toLong() }
    override fun encodeInt(value: Int) { encodedInteger = value.toLong() }
    override fun encodeLong(value: Long) { encodedInteger = value }

    override fun encodeFloat(value: Float) { encodedFloat = value.toDouble() }
    override fun encodeDouble(value: Double) { encodedFloat = value }


    @ExperimentalSerializationApi
    override fun encodeNull() {}
    override fun encodeBoolean(value: Boolean) {}
    override fun encodeChar(value: Char) {}
    override fun encodeString(value: String) {}
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {}
    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder { throw EarlyReturn }
}

@Suppress("OBJECT_EXTENDING_THROWABLE")
private object EarlyReturn : Throwable() {
    private fun readResolve(): Any = EarlyReturn
    override fun fillInStackTrace(): Throwable? = null
}

internal data class EncodedNumbers(
    val integer: Long?,
    val float: Double?
)

internal fun <T> encodeNumber(serializersModule: SerializersModule, serializer: SerializationStrategy<T>, value: T): EncodedNumbers {
    val encoder = NumberEncoder(serializersModule)
    try {
        serializer.serialize(encoder, value)
    } catch (_: EarlyReturn) {
        /*Squish*/
    }
    return EncodedNumbers(
        integer = encoder.encodedInteger,
        float = encoder.encodedFloat
    )
}