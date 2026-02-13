package com.lightningkite.services.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.modules.plus
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

class AnnotationValidators private constructor(
    val serializersModule: SerializersModule,
    private val validators: Map<KClass<out Annotation>, Map<String, (Annotation, Any?) -> String?>>,
    private val suspendingValidators: Map<KClass<out Annotation>, Map<String, suspend (Annotation, Any?) -> String?>>
) {
    operator fun plus(other: AnnotationValidators): AnnotationValidators =
        if (this === other) this else AnnotationValidators(
            serializersModule + other.serializersModule,
            validators.mergeWith(other.validators, overwrite = false),
            suspendingValidators.mergeWith(other.suspendingValidators, overwrite = false)
        )

    infix fun overwriteWith(other: AnnotationValidators): AnnotationValidators =
        if (this === other) this else AnnotationValidators(
            serializersModule overwriteWith other.serializersModule,
            validators.mergeWith(other.validators, overwrite = true),
            suspendingValidators.mergeWith(other.suspendingValidators, overwrite = true)
        )

    private fun <K1, K2, V> Map<K1, Map<K2, V>>.mergeWith(other: Map<K1, Map<K2, V>>, overwrite: Boolean): Map<K1, Map<K2, V>> {
        val out = HashMap<K1, Map<K2, V>>()

        // independent keys
        for (key in this.keys - other.keys) out[key] = getValue(key)
        for (key in other.keys - this.keys) out[key] = other.getValue(key)

        // shared keys
        for (k1 in this.keys.intersect(other.keys)) {
            val dest = HashMap<K2, V>()
            val map1 = this.getValue(k1)
            val map2 = other.getValue(k1)

            // independent
            for (key in map1.keys - map2.keys) dest[key] = map1.getValue(key)
            for (key in map2.keys - map1.keys) dest[key] = map2.getValue(key)

            // shared
            val shared = map1.keys.intersect(map2.keys)
            if (overwrite) for (key in shared) dest[key] = map2.getValue(key)
            else throw IllegalArgumentException("AnnotationValidators encountered conflicting validators for $k1 : $shared. To overwrite validators use the `overwriteWith` infix function.")

            out[k1] = dest
        }

        return out
    }

    class Builder(val serializersModule: SerializersModule) {
        private val validators = HashMap<KClass<out Annotation>, HashMap<String, (Annotation, Any?) -> String?>>()
        private val suspendingValidators = HashMap<KClass<out Annotation>, HashMap<String, suspend (Annotation, Any?) -> String?>>()

        private inline fun <reified T> Any?.cast(): T = this as T

        @OptIn(ExperimentalSerializationApi::class)
        private val <T : Any> KClass<T>.parameterlessSerialName get() =
            serializersModule.serializer(this, List(10) { NothingSerializer() }, isNullable = false).descriptor.serialName



        // NOTE: I purposely did not make any reified versions, this prevents users from creating validations
        //       on collections with specific inner types, which would break the current implementation.
        //       For example, validating a List<*> is fine, but a List<Int> could cause errors.

        fun <A : Annotation, T> KClass<A>.validates(serializer: KSerializer<T>, condition: A.(T) -> String?) {
            validators.getOrPut(this, ::HashMap)[serializer.descriptor.serialName] = condition.cast()
        }
        fun <A : Annotation, T> KClass<A>.validatesSuspending(serializer: KSerializer<T>, condition: suspend A.(T) -> String?) {
            suspendingValidators.getOrPut(this, ::HashMap)[serializer.descriptor.serialName] = condition.cast()
        }

        fun <A : Annotation, T : Any> KClass<A>.validates(type: KClass<T>, condition: A.(T) -> String?) {
            validators.getOrPut(this, ::HashMap)[type.parameterlessSerialName] = condition.cast()
        }
        fun <A : Annotation, T : Any> KClass<A>.validatesSuspending(type: KClass<T>, condition: suspend A.(T) -> String?) {
            suspendingValidators.getOrPut(this, ::HashMap)[type.parameterlessSerialName] = condition.cast()
        }

        private fun <A : Annotation, T : Any> KClass<A>.validatesPrimitiveKind(primitiveKind: PrimitiveKind, condition: A.(T) -> String?) {
            validators.getOrPut(this, ::HashMap)["PrimitiveKind.$primitiveKind"] = condition.cast()
        }
        fun <A : Annotation> KClass<A>.validatesStrings(condition: A.(String) -> String?) = validatesPrimitiveKind(PrimitiveKind.STRING, condition)
        fun <A : Annotation> KClass<A>.validatesBooleans(condition: A.(Boolean) -> String?) = validatesPrimitiveKind(PrimitiveKind.BOOLEAN, condition)
        fun <A : Annotation> KClass<A>.validatesBytes(condition: A.(Byte) -> String?) = validatesPrimitiveKind(PrimitiveKind.BYTE, condition)
        fun <A : Annotation> KClass<A>.validatesShorts(condition: A.(Short) -> String?) = validatesPrimitiveKind(PrimitiveKind.SHORT, condition)
        fun <A : Annotation> KClass<A>.validatesInts(condition: A.(Int) -> String?) = validatesPrimitiveKind(PrimitiveKind.INT, condition)
        fun <A : Annotation> KClass<A>.validatesLongs(condition: A.(Long) -> String?) = validatesPrimitiveKind(PrimitiveKind.LONG, condition)
        fun <A : Annotation> KClass<A>.validatesFloats(condition: A.(Float) -> String?) = validatesPrimitiveKind(PrimitiveKind.FLOAT, condition)
        fun <A : Annotation> KClass<A>.validatesDoubles(condition: A.(Double) -> String?) = validatesPrimitiveKind(PrimitiveKind.DOUBLE, condition)
        fun <A : Annotation> KClass<A>.validatesChars(condition: A.(Char) -> String?) = validatesPrimitiveKind(PrimitiveKind.CHAR, condition)

        private fun <A : Annotation, T : Any> KClass<A>.validatesPrimitiveKindSuspending(primitiveKind: PrimitiveKind, condition: suspend A.(T) -> String?) {
            suspendingValidators.getOrPut(this, ::HashMap)["PrimitiveKind.$primitiveKind"] = condition.cast()
        }
        fun <A : Annotation> KClass<A>.validatesStringsSuspending(condition: suspend A.(String) -> String?) = validatesPrimitiveKindSuspending(PrimitiveKind.STRING, condition)
        fun <A : Annotation> KClass<A>.validatesBooleansSuspending(condition: suspend A.(Boolean) -> String?) = validatesPrimitiveKindSuspending(PrimitiveKind.BOOLEAN, condition)
        fun <A : Annotation> KClass<A>.validatesBytesSuspending(condition: suspend A.(Byte) -> String?) = validatesPrimitiveKindSuspending(PrimitiveKind.BYTE, condition)
        fun <A : Annotation> KClass<A>.validatesShortsSuspending(condition: suspend A.(Short) -> String?) = validatesPrimitiveKindSuspending(PrimitiveKind.SHORT, condition)
        fun <A : Annotation> KClass<A>.validatesIntsSuspending(condition: suspend A.(Int) -> String?) = validatesPrimitiveKindSuspending(PrimitiveKind.INT, condition)
        fun <A : Annotation> KClass<A>.validatesLongsSuspending(condition: suspend A.(Long) -> String?) = validatesPrimitiveKindSuspending(PrimitiveKind.LONG, condition)
        fun <A : Annotation> KClass<A>.validatesFloatsSuspending(condition: suspend A.(Float) -> String?) = validatesPrimitiveKindSuspending(PrimitiveKind.FLOAT, condition)
        fun <A : Annotation> KClass<A>.validatesDoublesSuspending(condition: suspend A.(Double) -> String?) = validatesPrimitiveKindSuspending(PrimitiveKind.DOUBLE, condition)
        fun <A : Annotation> KClass<A>.validatesCharsSuspending(condition: suspend A.(Char) -> String?) = validatesPrimitiveKindSuspending(PrimitiveKind.CHAR, condition)

        fun build(): AnnotationValidators =
            AnnotationValidators(serializersModule, validators, suspendingValidators)
    }

    companion object {
        private val regexCache = HashMap<String, Regex>()
        private fun cachedRegex(pattern: String): Regex = regexCache.getOrPut(pattern) { Regex(pattern) }

        val standard = AnnotationValidators {
            // MaxSize validators
            MaxSize::class.validatesCollections {
                if (it.size > size) "Too long; got ${it.size} items but the maximum allowed is $size"
                else null
            }
            MaxSize::class.validates(Array::class) {
                if (it.size > size) "Too long; got ${it.size} items but the maximum allowed is $size"
                else null
            }
            MaxSize::class.validatesMaps {
                if (it.size > size) "Too long; got ${it.size} entries but the maximum allowed is $size"
                else null
            }

            // MaxLength validators
            MaxLength::class.validatesStrings {
                if (it.length > size) "Too long; maximum $size characters allowed"
                else null
            }

            // ExpectedPattern validators
            ExpectedPattern::class.validatesStrings {
                val regex = cachedRegex(pattern)
                if (!regex.matches(it)) "Does not match pattern; expected to match $pattern"
                else null
            }

            // IntegerRange validators
            IntegerRange::class.validatesBytes {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            IntegerRange::class.validatesShorts {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            IntegerRange::class.validatesInts {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            IntegerRange::class.validatesLongs {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }

            // FloatRange validators
            FloatRange::class.validatesFloats {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            FloatRange::class.validatesDoubles {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
        }
    }

    class InvalidTypeException(message: String) : Exception(message)


    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    private inner class ValidationEncoder(val doSuspendingChecks: Boolean) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = this@AnnotationValidators.serializersModule

        private val issues = HashMap<String, String>()
        private val suspendingChecks = ArrayList<suspend () -> Unit>()

        private val

        private var nextAnnotations: List<Annotation>? = null

        override fun encodeValue(value: Any) {

        }

        private fun invalidType(annotation: Annotation, value: Any?, valid: Set<String>) {
            InvalidTypeException(
                "${annotation::class.simpleName} applied to invalid type ${value?.let { it::class.qualifiedName }}. Valid types are ${valid.joinToString()}. Ignoring Validation."
            ).printStackTrace()
        }

        private fun validateRaw(
            value: Any?,
            elementAnnotations: List<Annotation>,
            keys: List<String>
        ) {
            for (annotation in elementAnnotations) {
                validators[annotation::class]?.let { validators ->
                    val specific = keys.firstNotNullOfOrNull { validators[it] }
                    if (specific == null) invalidType(annotation, value, validators.keys)
                    else specific(annotation, value)?.let {
                        issues[currentPath()] = it
                    }
                }
                if (doSuspendingChecks) suspendingValidators[annotation::class]?.let { validators ->
                    val specific = keys.firstNotNullOfOrNull { validators[it] }
                    if (specific == null) invalidType(annotation, value, validators.keys)
                    else suspendingChecks.add {
                        specific(annotation, value)?.let {
                            issues[currentPath()] = it
                        }
                    }
                }
            }
        }

    }

    private fun <V> Map<String, V>.get(type: KSerializer<*>): V? =
        get(type.descriptor.serialName) ?: if (type.descriptor.kind is PrimitiveKind) get("PrimitiveKind.${type.descriptor.kind}") else null

    fun <T> validateValueFast(value: T, serializer: KSerializer<T>, annotations: List<Annotation>, out: MutableList<String>) {
        for (annotation in annotations) {
            val validators = validators[annotation::class] ?: continue

            val specific = validators.get(serializer)

            if (specific == null) invalidType(annotation, value, validators.keys)
            else specific(annotation, value)?.let(out::add)
        }
    }

    suspend fun <T> validateValue(value: T, serializer: KSerializer<T>, annotations: List<Annotation>, out: MutableList<String>) {
        validateValueFast(value, serializer, annotations, out)

        for (annotation in annotations) {
            val validators = suspendingValidators[annotation::class] ?: continue

            val specific = validators.get(serializer)

            if (specific == null) invalidType(annotation, value, validators.keys)
            else specific(annotation, value)?.let(out::add)
        }
    }
}

fun AnnotationValidators(
    serializersModule: SerializersModule = EmptySerializersModule(),
    setup: AnnotationValidators.Builder.() -> Unit
): AnnotationValidators = AnnotationValidators.Builder(serializersModule).apply(setup).build()

fun AnnotationValidators(): AnnotationValidators = AnnotationValidators.standard

fun EmptyAnnotationValidators(
    serializersModule: SerializersModule = EmptySerializersModule()
): AnnotationValidators = AnnotationValidators.Builder(serializersModule).build()
