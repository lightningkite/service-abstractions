package com.lightningkite.services.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.modules.plus
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

class AnnotationValidators private constructor(
    val serializersModule: SerializersModule,
    private val validators: Map<String, Map<String, (Annotation, Any?) -> String?>>,
    private val suspendingValidators: Map<String, Map<String, suspend (Annotation, Any?) -> String?>>
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
        if (this.isEmpty()) return other
        if (other.isEmpty()) return this

        val out = HashMap<K1, Map<K2, V>>(this.size + other.size)

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
            if (shared.isNotEmpty()) {
                if (overwrite) for (key in shared) dest[key] = map2.getValue(key)
                else throw IllegalArgumentException("AnnotationValidators encountered conflicting validators for $k1 : $shared. To overwrite validators use the `overwriteWith` infix function.")
            }

            out[k1] = dest
        }

        return out
    }


    private fun <V> Map<String, V>.get(type: SerialDescriptor): V? =
        get(type.serialName) ?: if (type.kind is PrimitiveKind) get("PrimitiveKind.${type.kind}") else null

    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    private inner class ValidationEncoder(val doSuspendingChecks: Boolean) : AbstractEncoder() {
        // basically just ripped from the old ValidationEncoder

        override val serializersModule: SerializersModule get() = this@AnnotationValidators.serializersModule

        val issues = HashMap<String, String>()

        val queued = ArrayList<suspend () -> Pair<String, String>?>()
        private val keyPath = ArrayList<Pair<SerialDescriptor, Int>>()
        private var lastDescriptor: SerialDescriptor? = null
        private var lastElementIndex: Int = 0

        suspend fun runQueuedSuspendingChecks() {
            queued.forEach { check ->
                check()?.let { issues[it.first] = it.second }
            }
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            lastDescriptor?.let {
                keyPath.add(it to lastElementIndex)
            }
            return super.beginStructure(descriptor)
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            keyPath.removeLastOrNull()
            super.endStructure(descriptor)
        }

        private var next: List<Annotation> = emptyList()
        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            next = descriptor.getElementAnnotations(index)
            lastElementIndex = index
            lastDescriptor = descriptor
            return super.encodeElement(descriptor, index)
        }

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            if (serializer is ShouldValidateSub<T>) serializer.validate(
                value,
                lastDescriptor?.getElementAnnotations(lastElementIndex) ?: listOf()
            ) { sub: ShouldValidateSub.SerializerAndValue<*>, annotations: List<Annotation> ->
                validate(sub.serializer.descriptor, sub.value, annotations)
            }
            else if (next.isNotEmpty() && value != null) validate(value)
            next = emptyList()
            super.encodeSerializableValue(serializer, value)
        }

        override fun encodeValue(value: Any) {
            if (next.isNotEmpty()) validate(value)
            next = emptyList()
        }

        override fun encodeNull() {
            next = emptyList()
        }

        private fun currentPath(): String = buildString {
            keyPath.forEach {
                append(it.first.getElementName(it.second))
                append('.')
            }
            append(
                lastDescriptor?.getElementName(lastElementIndex) ?: lastElementIndex.toString()
            )
        }

        private fun validate(value: Any) {
            val parentDescriptor = lastDescriptor ?: return
            validate(
                parentDescriptor.getElementDescriptor(lastElementIndex),
                value,
                parentDescriptor.getElementAnnotations(lastElementIndex)
            )
        }

        private fun validate(
            descriptor: SerialDescriptor,
            value: Any?,
            annotations: List<Annotation>,
        ) {
            annotations.forEach { annotation ->
                var found = false
                var options: Set<String>? = null

                val clazz = annotation::class
                val key = clazz.normalizedTypeName()

                validators[key]?.let { validators ->
                    val check = validators.get(descriptor)
                    if (check == null) options = validators.keys
                    else found = true
                    check?.invoke(annotation, value)?.let { error ->
                        issues[currentPath()] = error
                    }
                }

                if (doSuspendingChecks) suspendingValidators[key]?.let { validators ->
                    val check = validators.get(descriptor)
                    if (check == null && !found && printInvalidTypeWarnings)
                        println("${clazz.simpleName ?: annotation.toString()} applied to invalid type: ${descriptor.serialName}. Valid types: ${validators.keys + options.orEmpty()}. Ignoring validation.")

                    check?.let { check ->
                        val path = currentPath() // have to pre-calculate path because this lambda isn't run until later.

                        queued.add { check(annotation, value)?.let { error -> path to error } }
                    }
                }
                else if (options != null && printInvalidTypeWarnings)
                    println("${clazz.simpleName ?: annotation.toString()} applied to invalid type: ${descriptor.serialName}. Valid types: $options. Ignoring validation.")
            }
        }
    }

    suspend fun <T> validate(serializer: SerializationStrategy<T>, value: T): Map<String, String> {
        val e = ValidationEncoder(doSuspendingChecks = true)
        e.encodeSerializableValue(serializer, value)
        e.runQueuedSuspendingChecks()
        return e.issues
    }

    fun <T> validateSkipSuspending(serializer: SerializationStrategy<T>, value: T): Map<String, String> {
        val e = ValidationEncoder(doSuspendingChecks = false)
        e.encodeSerializableValue(serializer, value)
        return e.issues
    }



    class Builder(val serializersModule: SerializersModule) {
        private val validators = HashMap<String, HashMap<String, (Annotation, Any?) -> String?>>()
        private val suspendingValidators = HashMap<String, HashMap<String, suspend (Annotation, Any?) -> String?>>()

        private inline fun <reified T> Any?.cast(): T = this as T

        @OptIn(ExperimentalSerializationApi::class)
        private val <T : Any> KClass<T>.parameterlessSerialName
            get() =
                serializersModule.serializer(this, List(10) { NothingSerializer() }, isNullable = false).descriptor.serialName

        private fun <K1, K2, V> HashMap<K1, HashMap<K2, V>>.register(k1: K1, k2: K2, v: V) {
            val map = getOrPut(k1, ::HashMap)
            if (map.containsKey(k2)) throw IllegalArgumentException("Multiple validator declarations encountered for $k1/$k2.")
            map[k2] = v
        }


        // NOTE: I purposely did not make any reified versions, this prevents users from creating validations
        //       on collections with specific inner types, which would break the current implementation.
        //       For example, validating a List<*> is fine, but a List<Int> could cause errors.

        // In the future it may be possible to discriminate by type parameters by including them in the string key.

        fun <A : Annotation, T> KClass<A>.validates(serializer: KSerializer<T>, condition: A.(T) -> String?) {
            validators.register(normalizedTypeName(), serializer.descriptor.serialName, condition.cast())
        }

        fun <A : Annotation, T> KClass<A>.validatesSuspending(serializer: KSerializer<T>, condition: suspend A.(T) -> String?) {
            suspendingValidators.register(normalizedTypeName(), serializer.descriptor.serialName, condition.cast())
        }

        fun <A : Annotation, T : Any> KClass<A>.validates(type: KClass<T>, condition: A.(T) -> String?) {
            validators.register(normalizedTypeName(), type.parameterlessSerialName, condition.cast())
        }

        fun <A : Annotation, T : Any> KClass<A>.validatesSuspending(type: KClass<T>, condition: suspend A.(T) -> String?) {
            suspendingValidators.register(normalizedTypeName(), type.parameterlessSerialName, condition.cast())
        }

        private fun <A : Annotation, T : Any> KClass<A>.validatesPrimitiveKind(primitiveKind: PrimitiveKind, condition: A.(T) -> String?) {
            validators.register(normalizedTypeName(), "PrimitiveKind.$primitiveKind", condition.cast())
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
            suspendingValidators.register(normalizedTypeName(), "PrimitiveKind.$primitiveKind", condition.cast())
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

        var printInvalidTypeWarnings = true

        val Standard = AnnotationValidators {
            // MaxSize validators
            MaxSize::class.validatesCollections {
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
}

internal fun KClass<*>.normalizedTypeName(): String = qualifiedName ?: toString().let { str ->
    if (str.startsWith("interface")) str.removePrefix("interface").trim().split(' ')[0].trim()
    else str.split('$').dropLast(1).last().replace('_', '.').trim()
}

interface ShouldValidateSub<A> : KSerializer<A> {
    data class SerializerAndValue<T>(val serializer: KSerializer<T>, val value: T)

    fun validate(value: A, annotations: List<Annotation>, defer: (value: SerializerAndValue<*>, annotations: List<Annotation>) -> Unit)
}


/**
 * Constructs a new set of [AnnotationValidators] with the provided [serializersModule]
 * */
fun AnnotationValidators(
    serializersModule: SerializersModule = EmptySerializersModule(),
    setup: AnnotationValidators.Builder.() -> Unit
): AnnotationValidators = AnnotationValidators.Builder(serializersModule).apply(setup).build()

/**
 * Constructs an empty set of [AnnotationValidators] with the provided [serializersModule]
 * */
@Suppress("FunctionName")
fun EmptyAnnotationValidators(
    serializersModule: SerializersModule = EmptySerializersModule()
): AnnotationValidators = AnnotationValidators.Builder(serializersModule).build()

/**
 * Returns [AnnotationValidators.Standard] with the provided [serializersModule]
 * */
fun AnnotationValidators(
    serializersModule: SerializersModule = EmptySerializersModule()
): AnnotationValidators =
    if (serializersModule === EmptySerializersModule()) AnnotationValidators.Standard
    else AnnotationValidators.Standard overwriteWith EmptyAnnotationValidators(serializersModule)
