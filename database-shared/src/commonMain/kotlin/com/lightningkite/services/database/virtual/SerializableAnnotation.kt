@file:Suppress("PackageDirectoryMismatch")
@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import kotlinx.serialization.*
import kotlin.reflect.KClass

@Serializable
public data class SerializableAnnotation(
    public val fqn: String,
    public val values: Map<String, SerializableAnnotationValue>,
) {
    public companion object {
        private val __do_not_use_externally__parsers = HashMap<String, (Annotation) -> SerializableAnnotation>()
        @Suppress("unused")
        private val __defaults_loaded__ = DefaultSerializableAnnotationParsers
        public fun <T : Annotation> parser(name: String, handler: (T) -> SerializableAnnotation) {
            @Suppress("UNCHECKED_CAST")
            __do_not_use_externally__parsers[name] = handler as ((Annotation) -> SerializableAnnotation)
        }

        // by Claude - Modified to use reflection fallback
        public fun parseOrNull(annotation: Annotation): SerializableAnnotation? {
            val fqn = annotation.toString().removePrefix("@").substringBefore("(")
            // First check registered parsers (for custom handling), then fall back to reflection
            return __do_not_use_externally__parsers[fqn]?.invoke(annotation)
                ?: reflectAnnotation(annotation)
        }
    }
}

@Serializable
public sealed class SerializableAnnotationValue {
    @Serializable
    public data object NullValue : SerializableAnnotationValue()

    @Serializable
    public data class BooleanValue(val value: Boolean) : SerializableAnnotationValue()

    @Serializable
    public data class ByteValue(val value: Byte) : SerializableAnnotationValue()

    @Serializable
    public data class ShortValue(val value: Short) : SerializableAnnotationValue()

    @Serializable
    public data class IntValue(val value: Int) : SerializableAnnotationValue()

    @Serializable
    public data class LongValue(val value: Long) : SerializableAnnotationValue()

    @Serializable
    public data class FloatValue(val value: Float) : SerializableAnnotationValue()

    @Serializable
    public data class DoubleValue(val value: Double) : SerializableAnnotationValue()

    @Serializable
    public data class CharValue(val value: Char) : SerializableAnnotationValue()

    @Serializable
    public data class StringValue(val value: String) : SerializableAnnotationValue()

    @Serializable
    public data class ClassValue(val fqn: String) : SerializableAnnotationValue()

    @Serializable
    public data class ArrayValue(val value: List<SerializableAnnotationValue>) : SerializableAnnotationValue()
    public companion object {
        @OptIn(InternalSerializationApi::class)
        public operator fun invoke(value: Any?): SerializableAnnotationValue {
            return when (value) {
                null -> NullValue
                is Boolean -> BooleanValue(value)
                is Byte -> ByteValue(value)
                is Short -> ShortValue(value)
                is Int -> IntValue(value)
                is Long -> LongValue(value)
                is Float -> FloatValue(value)
                is Double -> DoubleValue(value)
                is Char -> CharValue(value)
                is String -> StringValue(value)
                // Enums render to their name, matching the JVM reflective fallback in parseOrNull.
                is Enum<*> -> StringValue(value.name)
                is KClass<*> -> ClassValue(value.serializerOrNull()?.descriptor?.serialName ?: "")
                is BooleanArray -> ArrayValue(value.map { invoke(it) })
                is ByteArray -> ArrayValue(value.map { invoke(it) })
                is ShortArray -> ArrayValue(value.map { invoke(it) })
                is IntArray -> ArrayValue(value.map { invoke(it) })
                is LongArray -> ArrayValue(value.map { invoke(it) })
                is FloatArray -> ArrayValue(value.map { invoke(it) })
                is DoubleArray -> ArrayValue(value.map { invoke(it) })
                is CharArray -> ArrayValue(value.map { invoke(it) })
                is Array<*> -> ArrayValue(value.map { invoke(it) })
                else -> NullValue
            }
        }
    }
}
