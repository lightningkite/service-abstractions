package com.lightningkite.services.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
public value class SealedTypeDiscriminator<T> private constructor(public val serialName: String) {
    public constructor(serializer: KSerializer<T>) : this(serializer.descriptor.serialName)
}

/**
 * The name of a variant relative to this sealed supertype, e.g. "Bar" for "com.example.Polymorphic.Bar" under
 * "com.example.Polymorphic". Null when the variant's serial name isn't nested under the supertype's.
 *
 * The DataClassPaths processor rejects sealed types where these names conflict (`TableGenerator.validateVariantShortNames`).
 */
internal fun <T, V : T> KSerializer<T>.variantShortName(discriminator: SealedTypeDiscriminator<V>): String? =
    discriminator.serialName.removePrefix(descriptor.serialName + ".").takeIf { it != discriminator.serialName }

@OptIn(ExperimentalSerializationApi::class)
internal fun <T, V : T> SealedTypeDiscriminator<V>.matches(value: T): Boolean {
    return value != null && try {
        val ser = serializer(value::class, listOf(), false)
        ser.descriptor.serialName == serialName
    }
    catch (e: SerializationException) {
        println("WARN! SealedTypeDiscriminator could not find a serializer for $value to determine if the type matched '$serialName'")
        e.printStackTrace()
        false
    }
}

@Suppress("UNCHECKED_CAST")
internal inline fun <T, V : T, R> SealedTypeDiscriminator<V>.handle(
    value: T,
    matchFail: () -> R,
    matchSuccess: (V) -> R
): R =
    if (matches(value)) matchSuccess(value as V)
    else matchFail()