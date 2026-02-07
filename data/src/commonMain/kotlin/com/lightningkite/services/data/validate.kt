package com.lightningkite.services.data

import com.lightningkite.IsRawString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.modules.plus
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

class ValidationInvalidType(message: String) : Exception(message)

interface ShouldValidateSub<A> : KSerializer<A> {
    fun validate(value: A, existingAnnotations: List<Annotation>, defer: (Any?, List<Annotation>) -> Unit) =
        defer(value, existingAnnotations)
}

typealias ValidationOut = (ValidationIssue) -> Unit

@Serializable
data class ValidationIssue(val path: List<String>, val code: Int, val text: String)

@Serializable
data class ValidationIssuePart(val code: Int, val text: String)

fun <T> Validators.validateFast(serializer: SerializationStrategy<T>, value: T, out: ValidationOut) {
    val e = ValidationEncoder(this, this.serializersModule, out)
    e.encodeSerializableValue(serializer, value)
}

suspend fun <T> Validators.validates(serializer: SerializationStrategy<T>, value: T, out: ValidationOut) {
    val e = ValidationEncoder(this, this.serializersModule, out)
    e.encodeSerializableValue(serializer, value)
    e.runSuspend()
}

//fun <T> SerializersModule.validateOrThrow(serializer: SerializationStrategy<T>, value: T) {
//    val issues = ArrayList<Pair<List<String>, String>>()
//    ValidationEncoder(this, { a, b ->
//        issues.add(a to b)
//    }).encodeSerializableValue(serializer, value)
//    if(issues.isNotEmpty()) {
//        throw BadRe
//    }
//}

private class AnnotationValidators private constructor(
    val serializersModule: SerializersModule,
    private val validators: Map<KClass<out Annotation>, Map<String, (Annotation, Any?) -> String?>>,
    private val suspendingValidators: Map<KClass<out Annotation>, Map<String, suspend (Annotation, Any?) -> String?>>
) {
    operator fun plus(other: AnnotationValidators): AnnotationValidators = AnnotationValidators(
        serializersModule + other.serializersModule,
        validators.mergeWith(other.validators, overwrite = false),
        suspendingValidators.mergeWith(other.suspendingValidators, overwrite = false)
    )

    infix fun overwriteWith(other: AnnotationValidators): AnnotationValidators = AnnotationValidators(
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

        fun build(): AnnotationValidators =
            AnnotationValidators(serializersModule, validators, suspendingValidators)
    }

    companion object {
        private val regexCache = HashMap<String, Regex>()
        private fun cachedRegex(pattern: String): Regex = regexCache.getOrPut(pattern) { Regex(pattern) }

        val standard = AnnotationValidators {
            // MaxSize validators
            MaxSize::class.validates(Collection::class) {
                if (it.size > size) "Too long; got ${it.size} items but the maximum allowed is $size"
                else null
            }
            MaxSize::class.validates(Map::class) {
                if (it.size > size) "Too long; got ${it.size} entries but the maximum allowed is $size"
                else null
            }

            // MaxLength validators
            MaxLength::class.validates(String::class) {
                if (it.length > size) "Too long; maximum $size characters allowed"
                else null
            }

            // ExpectedPattern validators
            ExpectedPattern::class.validates(String::class) {
                val regex = cachedRegex(pattern)
                if (!regex.matches(it)) "Does not match pattern; expected to match $pattern"
                else null
            }

            // IntegerRange validators
            IntegerRange::class.validates(Byte::class) {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            IntegerRange::class.validates(Short::class) {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            IntegerRange::class.validates(Int::class) {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            IntegerRange::class.validates(Long::class) {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            IntegerRange::class.validates(UByte::class) {
                if (it.toLong() !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            IntegerRange::class.validates(UShort::class) {
                if (it.toLong() !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            IntegerRange::class.validates(UInt::class) {
                if (it.toLong() !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            IntegerRange::class.validates(ULong::class) {
                if (it.toLong() !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }

            // FloatRange validators
            FloatRange::class.validates(Float::class) {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            FloatRange::class.validates(Double::class) {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
        }
    }
}

private fun AnnotationValidators(
    serializersModule: SerializersModule = EmptySerializersModule(),
    setup: AnnotationValidators.Builder.() -> Unit
): AnnotationValidators = AnnotationValidators.Builder(serializersModule).apply(setup).build()


class Validators(val serializersModule: SerializersModule = EmptySerializersModule()) {
    private val regexCache = HashMap<String, Regex>()
    private fun cachedRegex(pattern: String): Regex = regexCache.getOrPut(pattern) { Regex(pattern) }

    internal val processors = ArrayList<(Annotation, value: Any?) -> ValidationIssuePart?>()
    inline fun <reified T : Annotation, V : Any> processor(crossinline action: (T, V) -> ValidationIssuePart?) {
        directProcessor(T::class) { a, b ->
            if (a is T) {
                @Suppress("UNCHECKED_CAST")
                if (b is Collection<*>)
                    b.asSequence().mapNotNull { action(a, it as V) }.firstOrNull()
                else if (b is Map<*, *>)
                    b.values.asSequence().mapNotNull { action(a, it as V) }.firstOrNull()
                else action(a, b as V)
            } else
                null
        }
    }

    inline fun <reified T : Annotation, V : Any> directProcessor(crossinline action: (T, V) -> ValidationIssuePart?) {
        directProcessor(T::class) { a, b ->
            @Suppress("UNCHECKED_CAST")
            if (a is T) action(a, b as V) else null
        }
    }

    fun directProcessor(
        @Suppress("UNUSED_PARAMETER") type: KClass<out Annotation>,
        action: (Annotation, Any?) -> ValidationIssuePart?
    ) {
        processors.add(action)
    }

    internal val suspendProcessors = ArrayList<suspend (Annotation, value: Any?) -> ValidationIssuePart?>()
    inline fun <reified T : Annotation, reified V : Any> suspendProcessor(crossinline action: suspend (T, V) -> ValidationIssuePart?) {
        directSuspendProcessor(T::class) { a, b ->
            if (a is T) {
                @Suppress("UNCHECKED_CAST")
                if (b is Collection<*>)
                    b.mapNotNull { if (it is V) action(a, it as V) else null }.firstOrNull()
                else if (b is Map<*, *>)
                    b.values.mapNotNull { if (it is V) action(a, it as V) else null }.firstOrNull()
                else if (b is V)
                    action(a, b as V)
                else
                    null
            } else
                null
        }
    }

    inline fun <reified T : Annotation, V : Any> directSuspendProcessor(crossinline action: suspend (T, V) -> ValidationIssuePart?) {
        directSuspendProcessor(T::class) { a, b ->
            @Suppress("UNCHECKED_CAST")
            if (a is T) action(a, b as V) else null
        }
    }

    fun directSuspendProcessor(
        @Suppress("UNUSED_PARAMETER") type: KClass<out Annotation>,
        action: suspend (Annotation, Any?) -> ValidationIssuePart?
    ) {
        suspendProcessors.add(action)
    }

    init {
        directProcessor<MaxSize, Any> { t, v ->
            when (v) {
                is Collection<*> -> if (v.size > t.size) ValidationIssuePart(
                    1,
                    "Too long; got ${v.size} items but have a maximum of ${t.size} items."
                ) else null

                is Map<*, *> -> if (v.size > t.size) ValidationIssuePart(
                    1,
                    "Too long; got ${v.size} entries but have a maximum of ${t.size} entries."
                ) else null

                else -> {
                    ValidationInvalidType("MaxSize applied to invalid type ${v::class}. Ignoring validation.").printStackTrace()
                    null
                }
            }
        }
        processor<MaxLength, Any> { t, v ->
            when (v) {
                is String -> if (v.length > t.size) ValidationIssuePart(
                    1,
                    "Too long; maximum ${t.size} characters allowed"
                ) else null

                is IsRawString -> if (v.raw.length > t.size) ValidationIssuePart(
                    1,
                    "Too long; maximum ${t.size} characters allowed"
                ) else null

                else -> {
                    ValidationInvalidType("MaxLength applied to invalid type ${v::class}. Ignoring validation.").printStackTrace()
                    null
                }
            }
        }
        processor<ExpectedPattern, Any> { t, v ->
            val regex = cachedRegex(t.pattern)
            when (v) {
                is String -> if (!regex.matches(v)) ValidationIssuePart(
                    2,
                    "Does not match pattern; expected to match ${t.pattern}"
                ) else null

                is IsRawString -> if (!regex.matches(v.raw)) ValidationIssuePart(
                    2,
                    "Does not match pattern; expected to match ${t.pattern}"
                ) else null

                else -> {
                    ValidationInvalidType("ExpectPattern applied to invalid type ${v::class}. Ignoring validation.").printStackTrace()
                    null
                }
            }
        }
        processor<IntegerRange, Any> { t, v ->
            when (v) {
                is Byte -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is Short -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is Int -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is Long -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is UByte -> if (v.toLong() !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is UShort -> if (v.toLong() !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is UInt -> if (v.toLong() !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is ULong -> if (v.toLong() !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                else -> {
                    ValidationInvalidType("IntegerRange applied to invalid type ${v::class}. Ignoring validation.").printStackTrace()
                    null
                }
            }
        }
        processor<FloatRange, Any> { t, v ->
            when (v) {
                is Float -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is Double -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                else -> {
                    ValidationInvalidType("FloatRange applied to invalid type ${v::class}. Ignoring validation.").printStackTrace()
                    null
                }
            }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class ValidationEncoder(val validators: Validators, override val serializersModule: SerializersModule, val out: ValidationOut) :
    AbstractEncoder() {

    val queued = ArrayList<suspend () -> Unit>()
    val keyPath = ArrayList<Pair<SerialDescriptor, Int>>()
    var lastDescriptor: SerialDescriptor? = null
    var lastElementIndex: Int = 0

    suspend fun runSuspend() {
        queued.forEach { it() }
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

    var next: List<Annotation> = emptyList()
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
        ) { a, b -> if (a != null) validate(a, b) }
        else if (next.isNotEmpty() && value != null) validate(
            value,
            lastDescriptor?.getElementAnnotations(lastElementIndex) ?: listOf()
        )
        next = emptyList()
        super.encodeSerializableValue(serializer, value)
    }

    override fun encodeValue(value: Any) {
        if (next.isNotEmpty()) validate(value, lastDescriptor?.getElementAnnotations(lastElementIndex) ?: listOf())
        next = emptyList()
    }

    override fun encodeNull() {
        next = emptyList()
    }

    fun validate(value: Any, annotations: List<Annotation>) {
        annotations.forEach {
            validators.processors.forEach { runner ->
                runner.invoke(it, value)?.let {
                    out(ValidationIssue(buildList {
                        keyPath.forEach {
                            add(it.first.getElementName(it.second))
                        }
                        add(lastDescriptor?.takeIf { lastElementIndex < it.elementsCount }
                            ?.getElementName(lastElementIndex) ?: lastElementIndex.toString())
                    }, it.code, it.text))
                }
            }
            queued += {
                validators.suspendProcessors.forEach { runner ->
                    runner.invoke(it, value)?.let {
                        out(ValidationIssue(buildList {
                            keyPath.forEach {
                                add(it.first.getElementName(it.second))
                            }
                            add(lastDescriptor?.takeIf { lastElementIndex < it.elementsCount }
                                ?.getElementName(lastElementIndex) ?: lastElementIndex.toString())
                        }, it.code, it.text))
                    }
                }
            }
        }
    }
}