package com.lightningkite.services.database

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.internal.GeneratedSerializer
import java.lang.reflect.Constructor
import kotlin.reflect.KClass

@OptIn(markerClass = [InternalSerializationApi::class])
internal actual fun GeneratedSerializer<*>.factory(): (typeArguments: Array<KSerializer<*>>) -> KSerializer<*> {
    val c: Constructor<*> = this::class.java.constructors.first().also {
        it.isAccessible = true
    }
    return { typeArguments ->
        c.newInstance(*typeArguments.copyOfRange(0, c.parameterTypes.size)) as KSerializer<*>
    }
}

// by Claude - Reflection-based annotation parsing using Java reflection
internal actual fun reflectAnnotation(annotation: Annotation): SerializableAnnotation? {
    val annotationClass = annotation.annotationClass.java
    val fqn = annotationClass.canonicalName ?: return null

    // Get all annotation methods (which represent annotation properties) via Java reflection
    // Filter out methods from java.lang.annotation.Annotation interface
    val excludedMethods = setOf("annotationType", "hashCode", "equals", "toString")
    val values = annotationClass.declaredMethods
        .filter { it.name !in excludedMethods && it.parameterCount == 0 }
        .associate { method ->
            method.isAccessible = true
            val value = method.invoke(annotation)
            method.name to convertToSerializableValue(value)
        }

    return SerializableAnnotation(fqn = fqn, values = values)
}

// by Claude - Helper to convert annotation property values to SerializableAnnotationValue
private fun convertToSerializableValue(value: Any?): SerializableAnnotationValue {
    return when (value) {
        null -> SerializableAnnotationValue.NullValue
        is Boolean -> SerializableAnnotationValue.BooleanValue(value)
        is Byte -> SerializableAnnotationValue.ByteValue(value)
        is Short -> SerializableAnnotationValue.ShortValue(value)
        is Int -> SerializableAnnotationValue.IntValue(value)
        is Long -> SerializableAnnotationValue.LongValue(value)
        is Float -> SerializableAnnotationValue.FloatValue(value)
        is Double -> SerializableAnnotationValue.DoubleValue(value)
        is Char -> SerializableAnnotationValue.CharValue(value)
        is String -> SerializableAnnotationValue.StringValue(value)
        is Class<*> -> SerializableAnnotationValue.ClassValue(value.canonicalName ?: "")
        is KClass<*> -> SerializableAnnotationValue.ClassValue(value.qualifiedName ?: "")
        is Enum<*> -> SerializableAnnotationValue.StringValue(value.name)
        is BooleanArray -> SerializableAnnotationValue.ArrayValue(value.map { convertToSerializableValue(it) })
        is ByteArray -> SerializableAnnotationValue.ArrayValue(value.map { convertToSerializableValue(it) })
        is ShortArray -> SerializableAnnotationValue.ArrayValue(value.map { convertToSerializableValue(it) })
        is IntArray -> SerializableAnnotationValue.ArrayValue(value.map { convertToSerializableValue(it) })
        is LongArray -> SerializableAnnotationValue.ArrayValue(value.map { convertToSerializableValue(it) })
        is FloatArray -> SerializableAnnotationValue.ArrayValue(value.map { convertToSerializableValue(it) })
        is DoubleArray -> SerializableAnnotationValue.ArrayValue(value.map { convertToSerializableValue(it) })
        is CharArray -> SerializableAnnotationValue.ArrayValue(value.map { convertToSerializableValue(it) })
        is Array<*> -> SerializableAnnotationValue.ArrayValue(value.map { convertToSerializableValue(it) })
        else -> SerializableAnnotationValue.NullValue
    }
}