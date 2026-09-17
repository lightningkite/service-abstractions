@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


private class SerializablePropertyParser<T>(val serializer: KSerializer<T>) {
    val children = run {
        (serializer.serializableProperties
            ?: throw SerializationException("${serializer.descriptor.serialName} does not have any serializable properties")).associateBy { it.name }
    }

    companion object {
        val existing = HashMap<KSerializerKey, SerializablePropertyParser<*>>()

        @Suppress("UNCHECKED_CAST")
        operator fun <T> get(serializer: KSerializer<T>): SerializablePropertyParser<T> = existing.getOrPut(
            KSerializerKey(serializer)
        ) {
            SerializablePropertyParser(serializer)
        } as SerializablePropertyParser<T>
    }

    operator fun invoke(key: String): SerializableProperty<T, *> {
        @Suppress("UNCHECKED_CAST")
        return children[key]
            ?: throw IllegalStateException("Could find no property with name '$key' on ${serializer.descriptor.serialName}")
    }
}

/** Splits a path string on '.', except inside `[...]` variant segments, whose serial names contain dots. */
private fun String.splitPathSegments(): List<String> {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    var depth = 0
    for (c in this) {
        when {
            c == '[' -> depth++
            c == ']' -> depth--
            c == '.' && depth == 0 -> {
                parts.add(current.toString())
                current.clear()
                continue
            }
        }
        current.append(c)
    }
    parts.add(current.toString())
    return parts
}

public class DataClassPathSerializer<T>(public val inner: KSerializer<T>) :
    KSerializerWithDefault<DataClassPathPartial<T>> {
    override val default: DataClassPathPartial<T>
        get() = DataClassPathSelf(inner)

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "com.lightningkite.services.database.DataClassPathPartial",
        PrimitiveKind.STRING
    )

    override fun deserialize(decoder: Decoder): DataClassPathPartial<T> {
        val value = decoder.decodeString()
        return fromString(value)
    }

    override fun serialize(encoder: Encoder, value: DataClassPathPartial<T>) {
        encoder.encodeString(value.toString())
    }

    public fun fromString(value: String): DataClassPathPartial<T> {
        var current: DataClassPathPartial<T>? = null
        var currentSerializer: KSerializer<*> = inner
        val valueParts = value.splitPathSegments()
        for ((index, part) in valueParts.withIndex()) {
            val name = part.removeSuffix("?")
            if (name == "this") continue
            if (name.startsWith('[') && name.endsWith(']')) {
                val typeName = name.substring(1, name.length - 1)
                val option = currentSerializer.serializableOptions?.find {
                    val discriminator = SealedTypeDiscriminator(it.serializer)
                    val serialName = discriminator.serialName
                    @Suppress("UNCHECKED_CAST")
                    serialName == typeName || (currentSerializer as KSerializer<Any?>).variantShortName(discriminator) == typeName || typeName in it.secondaryNames
                } ?: throw SerializationException("'$typeName' is not a variant of ${currentSerializer.descriptor.serialName}")
                @Suppress("UNCHECKED_CAST")
                current = DataClassPathOfType(
                    (current ?: DataClassPathSelf(inner)) as DataClassPath<T, Any?>,
                    option.serializer as KSerializer<Any?>
                )
                currentSerializer = option.serializer
                continue
            }
            if (name == "*") {
                val c = current ?: throw SerializationException("'*' cannot be the root of a path")
                when {
                    currentSerializer.listElement() != null -> {
                        @Suppress("UNCHECKED_CAST")
                        current = DataClassPathList(c as DataClassPath<T, List<Any?>>)
                        currentSerializer = currentSerializer.listElement()!!
                    }

                    else -> {
                        throw SerializationException("'*' used on non-collection type ${currentSerializer.descriptor.serialName}")
                    }
                }
                continue
            }

            val prop = try {
                SerializablePropertyParser[currentSerializer](name)
            } catch (e: IllegalStateException) {
                throw SerializationException(message = e.message, cause = e)
            }
            currentSerializer = prop.serializer
            val c = current
            @Suppress("UNCHECKED_CAST")
            current = if (c == null) DataClassPathAccess(
                DataClassPathSelf<T>(inner),
                prop as SerializableProperty<T, Any?>
            )
            else DataClassPathAccess(c as DataClassPath<T, Any?>, prop as SerializableProperty<Any?, Any?>)
            if (part.endsWith('?') || prop.serializer.descriptor.isNullable && index != valueParts.lastIndex) {
                @Suppress("UNCHECKED_CAST")
                current = DataClassPathNotNull(current as DataClassPath<T, Any?>)
                currentSerializer =
                    currentSerializer.nullElement() ?: throw SerializationException("${prop.name} is not nullable")
            }
        }

        return current ?: DataClassPathSelf(inner)
    }
}
