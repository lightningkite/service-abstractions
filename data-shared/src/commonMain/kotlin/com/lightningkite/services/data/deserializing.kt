package com.lightningkite.services.data

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException

/**
 * Runs [parse], reporting anything it throws as a [SerializationException] naming this type.
 *
 * Deserialization has to fail with a [SerializationException]: a caller holding untrusted input -
 * a server routing a request, say - tells "the input was malformed" from "something broke" by
 * catching that type, so a serializer that validates by throwing [IllegalArgumentException]
 * instead reads as a server fault and answers a bad request with a 500 rather than a 400.
 *
 * Use it around any parse a serializer does for itself:
 * ```kotlin
 * override fun deserialize(decoder: Decoder): EmailAddress =
 *     deserializing { decoder.decodeString().toEmailAddress() }
 * ```
 */
public inline fun <T> DeserializationStrategy<T>.deserializing(parse: () -> T): T = try {
    parse()
} catch (e: SerializationException) {
    throw e
} catch (e: Exception) {
    throw SerializationException("Not a valid ${descriptor.serialName}: ${e.message}", e)
}
