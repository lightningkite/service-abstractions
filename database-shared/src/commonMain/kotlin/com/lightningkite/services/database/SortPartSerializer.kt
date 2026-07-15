@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import com.lightningkite.services.data.Description
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

public class SortPartSerializer<T>(public val inner: KSerializer<T>) : KSerializerWithDefault<SortPart<T>> {
    override val default: SortPart<T>
        get() = SortPart(DataClassPathAccess(DataClassPathSelf(inner), inner.serializableProperties!!.first()))

    @OptIn(ExperimentalSerializationApi::class, SealedSerializationApi::class)
    override val descriptor: SerialDescriptor = PrimitiveDescriptorWithAnnotations(
        serialName = "com.lightningkite.services.database.SortPart",
        kind = PrimitiveKind.STRING,
        annotations = listOf(
            Description("The name of the property to sort by.  Prepend a '-' if you wish to sort descending.  Prepend '~' if you wish to ignore case.")
        )
    )

    private val sub = DataClassPathSerializer(inner)

    override fun deserialize(decoder: Decoder): SortPart<T> {
        val value = decoder.decodeString()
        val descending = value.startsWith('-')
        val nameWithoutCase = value.removePrefix("-")
        val ignoreCase = nameWithoutCase.startsWith('~')
        val name = nameWithoutCase.removePrefix("~")
        return SortPart(sub.fromString(name), !descending, ignoreCase)
    }

    override fun serialize(encoder: Encoder, value: SortPart<T>) {
        encoder.encodeString(buildString {
            if (!value.ascending) append('-')
            if (value.ignoreCase) append('~')
            append(value.field.toString())
        })
    }
}