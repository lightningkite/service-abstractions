package com.lightningkite.services.database.validation

import com.lightningkite.services.data.ExpectedPattern
import com.lightningkite.services.data.FloatRange
import com.lightningkite.services.data.IntegerRange
import com.lightningkite.services.data.MaxLength
import com.lightningkite.services.data.MaxSize
import com.lightningkite.services.data.StringArrayFormat
import com.lightningkite.services.database.childSerializersOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.modules.plus
import kotlin.collections.set
import kotlin.reflect.KClass

/**
 * A registry of annotation-based validators for data model validation.
 *
 * This class maps annotations (like [MaxLength], [IntegerRange], etc.) to validation functions
 * that check field values during serialization. Validators can be synchronous or suspending.
 *
 * Use [AnnotationValidators.Standard] for the default set of validators, or build custom
 * validators using the `AnnotationValidators { ... }` builder function.
 *
 * Validators can be combined using `+` or the `overwriteWith` infix fun. Combining using
 * `+` will throw an exception if there are any duplicates.
 *
 * ## Example
 * ```kotlin
 * @Serializable
 * data class User(
 *     @MaxLength(50) val name: String,
 *     @IntegerRange(0, 120) val age: Int
 * )
 *
 * val validators = AnnotationValidators()
 * val issues = validators.validate(serializer<User>(), User("John", 150))
 * // issues will contain: mapOf("age" to "Out of range; expected to be between 0 and 120")
 * ```
 */
public class AnnotationValidators private constructor(
    public val serializersModule: SerializersModule,
    private val validators: ValidationMap<(Annotation, Any?) -> String?>,
    private val suspendingValidators: ValidationMap<suspend (Annotation, Any?) -> String?>,
) {

    public operator fun plus(other: AnnotationValidators): AnnotationValidators =
        if (this === other) this else AnnotationValidators(
            serializersModule + other.serializersModule,
            validators.combineWith(other.validators, overwrite = false),
            suspendingValidators.combineWith(other.suspendingValidators, overwrite = false)
        )

    public infix fun overwriteWith(other: AnnotationValidators): AnnotationValidators =
        if (this === other) this else AnnotationValidators(
            serializersModule overwriteWith other.serializersModule,
            validators.combineWith(other.validators, overwrite = true),
            suspendingValidators.combineWith(other.suspendingValidators, overwrite = true)
        )

    public suspend fun <T> validate(serializer: SerializationStrategy<T>, value: T): Map<String, String> {
        val e = ValidationEncoder(doSuspendingChecks = true)
        e.encodeSerializableValue(serializer, value)
        e.runQueuedSuspendingChecks()
        return e.issues
    }

    public fun <T> validateSkipSuspending(serializer: SerializationStrategy<T>, value: T): Map<String, String> {
        val e = ValidationEncoder(doSuspendingChecks = false)
        e.encodeSerializableValue(serializer, value)
        return e.issues
    }

    override fun toString(): String =
        if (this === Standard) "AnnotationValidators.Standard"
        else buildString {
            append("AnnotationValidators(")
            val fast = validators.entries()
            val suspending = suspendingValidators.entries()
            for (annotation in fast.keys + suspending.keys) {
                append("${annotation.substringAfterLast('.')}:[")
                val fast = fast[annotation]
                fast?.joinTo(this)
                suspending[annotation]?.joinTo(
                    this,
                    prefix = if (fast != null) ", " else ""
                ) { "$it(S)" }
                append("], ")
            }
            append(')')
        }

    public fun prettyPrint(qualified: Boolean = false): String =
        if (this === Standard) "AnnotationValidators.Standard"
        else buildString {
            append("AnnotationValidators(")
            val fast = validators.entries()
            val suspending = suspendingValidators.entries()
            for (annotation in fast.keys + suspending.keys) {
                append("\n\t")
                if (qualified) append(annotation)
                else append(annotation.substringAfterLast('.'))
                append(":[")

                val fast = fast[annotation]
                fast?.joinTo(this) {
                    val s = if (qualified) it.qualifiedString()
                    else it.toString()
                    "\n\t\t$s"
                }
                suspending[annotation]?.joinTo(
                    this,
                    prefix = if (fast != null) ", " else ""
                ) {
                    val s = if (qualified) it.qualifiedString()
                    else it.toString()
                    "\n\t\t$s(S)"
                }

                append("\n\t], ")
            }
            append("\n)\n")
        }


    public class Builder(public val serializersModule: SerializersModule) {
        private val used = HashSet<Pair<String, SerialKType>>()
        private val validators = ValidationMap<(Annotation, Any?) -> String?>()
        private val suspendingValidators = ValidationMap<suspend (Annotation, Any?) -> String?>()

        @PublishedApi
        internal inline fun <reified T> Any?.cast(): T = this as T

        private fun <T : Any> ValidationMap<T>.register(annotation: KClass<out Annotation>, type: SerialKType, value: T) {
            val key = annotation.normalizedTypeName()
            if (!used.add(key to type)) throw IllegalArgumentException("Multiple validator declarations encountered for ${annotation.simpleName ?: key}/$type")
            put(annotation, type, value)
        }

        public fun <A : Annotation> KClass<A>.rawValidation(onType: SerialKType, condition: Annotation.(Any?) -> String?) {
            validators.register(this, onType, condition)
        }

        public fun <A : Annotation> KClass<A>.rawValidationSuspending(onType: SerialKType, condition: suspend Annotation.(Any?) -> String?) {
            suspendingValidators.register(this, onType, condition)
        }

        public inline fun <reified A : Annotation, reified T> validate(noinline condition: A.(T) -> String?) {
            A::class.rawValidation(serialKTypeOf<T>(serializersModule), condition.cast())
        }

        public inline fun <A : Annotation, reified T> KClass<A>.validates(noinline condition: A.(T) -> String?) {
            rawValidation(serialKTypeOf<T>(serializersModule), condition.cast())
        }

        public inline fun <reified A : Annotation, reified T> validateSuspending(noinline condition: suspend A.(T) -> String?) {
            A::class.rawValidationSuspending(serialKTypeOf<T>(serializersModule), condition.cast())
        }

        public fun build(): AnnotationValidators =
            AnnotationValidators(serializersModule, validators, suspendingValidators)
    }

    public companion object {
        private val regexCache = HashMap<String, Regex>()
        private fun cachedRegex(pattern: String): Regex = regexCache.getOrPut(pattern) { Regex(pattern) }

        public var printInvalidTypeWarnings: Boolean = true

        /**
         * The standard set of validators for common validation annotations.
         *
         * Includes validators for:
         * - [MaxSize] - validates collection and map size limits
         * - [MaxLength] - validates string length limits
         * - [ExpectedPattern] - validates strings against regex patterns
         * - [IntegerRange] - validates integer types (Byte, Short, Int, Long) against min/max ranges
         * - [FloatRange] - validates floating-point types (Float, Double) against min/max ranges
         */
        public val Standard: AnnotationValidators = AnnotationValidators {
            // MaxSize validators
            validateCollections<MaxSize> {
                if (it.size > size) "Too long; got ${it.size} items but the maximum allowed is $size"
                else null
            }
            validate<MaxSize, Map<*, *>> {
                if (it.size > size) "Too long; got ${it.size} items but the maximum allowed is $size"
                else null
            }

            // MaxLength validators
            validateStrings<MaxLength> {
                if (it.length > size) "Too long; maximum $size characters allowed"
                else null
            }

            // ExpectedPattern validators
            validateStrings<ExpectedPattern> {
                val regex = cachedRegex(pattern)
                if (!regex.matches(it)) "Does not match pattern; expected to match $pattern"
                else null
            }

            // IntegerRange validators
            validate<IntegerRange, Byte> {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            validate<IntegerRange, Short> {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            validate<IntegerRange, Int> {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            validate<IntegerRange, Long> {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }

            // FloatRange validators
            validate<FloatRange, Float> {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
            validate<FloatRange, Double> {
                if (it !in min..max) "Out of range; expected to be between $min and $max"
                else null
            }
        }
    }


    // Implementation

    private class ValidationMap<T : Any> {
        // map of qualified annotation name to possible type/validation pairs
        private val map = HashMap<String, ArrayList<Pair<SerialKType, T>>>()

        fun entries(): Map<String, List<SerialKType>> = map.mapValues { entry -> entry.value.map { it.first } }

        fun put(annotation: KClass<out Annotation>, type: SerialKType, value: T) {
            map.getOrPut(annotation.normalizedTypeName(), ::ArrayList).add(type to value)
        }

        fun finalize() {
            for (list in map.values) list.sortBy { it.first.generality() }  // we want to search most_specific->least_specific
        }

        fun get(annotation: KClass<out Annotation>, type: SerialKType): T? {
            val forAnnotation = map[annotation.normalizedTypeName()] ?: return null
            val found = forAnnotation.firstOrNull { it.first.matches(type) }?.second
            if (found == null) {
                if (printInvalidTypeWarnings) println(
                    "${annotation.simpleName ?: annotation.normalizedTypeName()} applied to invalid type: $type. Valid types: [${
                        forAnnotation.joinToString { it.first.toString() }
                    }]. Ignoring validation."
                )
            }
            return found
        }

        fun <V : Any> getJoint(other: ValidationMap<V>, annotation: KClass<out Annotation>, type: SerialKType): Pair<T?, V?>? {
            val key = annotation.normalizedTypeName()
            val first = map[key]
            val second = other.map[key]

            if (first == null && second == null) return null

            val firstFound = first?.firstOrNull { it.first.matches(type) }?.second
            val secondFound = second?.firstOrNull { it.first.matches(type) }?.second

            if (firstFound == null && secondFound == null && printInvalidTypeWarnings) println(buildString {
                append("${annotation.simpleName ?: annotation.normalizedTypeName()} applied to invalid type: $type. Valid types: [")
                first?.joinTo(this) { it.first.toString() }
                second?.joinTo(this) { "${it.first}(S)" }
                append("]. Ignoring validation.")
            })

            return Pair(firstFound, secondFound)
        }

        fun combineWith(other: ValidationMap<T>, overwrite: Boolean): ValidationMap<T> {
            if (this.map.isEmpty()) return other
            if (other.map.isEmpty()) return this

            val out = ValidationMap<T>()

            // independent keys
            for (key in this.map.keys - other.map.keys) out.map[key] = this.map.getValue(key)
            for (key in other.map.keys - this.map.keys) out.map[key] = other.map.getValue(key)

            // shared keys
            for (k1 in this.map.keys.intersect(other.map.keys)) {
                val list1 = this.map.getValue(k1).toMutableList()   // create copy
                val list2 = other.map.getValue(k1)

                val dest = ArrayList<Pair<SerialKType, T>>(list1.size + list2.size)
                val used = HashSet<SerialKType>()

                // we can trust that existing lists do not have any conflicts in themselves because that is checked for in the builder
                dest.addAll(list2)
                list2.mapTo(used) { it.first }

                val iter = list1.iterator()
                while (iter.hasNext()) {
                    val pair = iter.next()
                    if (pair.first !in used) {
                        dest.add(pair)
                        iter.remove()
                    }
                }

                if (list1.isNotEmpty() && !overwrite)
                    IllegalArgumentException("AnnotationValidators encountered conflicting validators for $k1 : [${list1.joinToString { it.first.toString() }}]. To overwrite validators use the `overwriteWith` infix function.")

                out.map[k1] = dest
            }

            out.finalize()

            return out
        }
    }


    private var enumDecoder: StringArrayFormat? = null

    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    private inner class ValidationEncoder(val doSuspendingChecks: Boolean) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = this@AnnotationValidators.serializersModule

        val issues = HashMap<String, String>()

        private val queuedSuspendingChecks = ArrayList<suspend () -> Unit>()

        suspend fun runQueuedSuspendingChecks() {
            queuedSuspendingChecks.forEach { it.invoke() }
            queuedSuspendingChecks.clear()
        }

        private val path = ArrayList<String>()
        private var annotationStack = ArrayList<List<Annotation>>()
        private fun queuedAnnotations() = annotationStack.flatten()

        private fun <T> encodeValue(serializer: KSerializer<T>, value: T) {
            validate(serializer, value)

            // element encoded, pop last element in path
            path.removeLastOrNull()
            annotationStack.removeLastOrNull()
        }

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            // I've never seen a raw SerializationStrategy, and all of our helpers require KSerializer, if this breaks, and you are debugging this at some point in the future - I apologize.
            lastParentSerializer = serializer as KSerializer<T>
            validate(serializer, value)

            serializer.serialize(this, value)
        }

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            path.add(descriptor.getElementName(index))
            annotationStack.add(descriptor.getElementAnnotations(index))
            return true
        }

        override fun encodeNull() = encodeValue(NothingSerializer().nullable, null)

        override fun encodeBoolean(value: Boolean): Unit = encodeValue(Boolean.serializer(), value)
        override fun encodeByte(value: Byte): Unit = encodeValue(Byte.serializer(), value)
        override fun encodeShort(value: Short): Unit = encodeValue(Short.serializer(), value)
        override fun encodeInt(value: Int): Unit = encodeValue(Int.serializer(), value)
        override fun encodeLong(value: Long): Unit = encodeValue(Long.serializer(), value)
        override fun encodeFloat(value: Float): Unit = encodeValue(Float.serializer(), value)
        override fun encodeDouble(value: Double): Unit = encodeValue(Double.serializer(), value)
        override fun encodeChar(value: Char): Unit = encodeValue(Char.serializer(), value)
        override fun encodeString(value: String): Unit = encodeValue(String.serializer(), value)

        private var lastParentSerializer: KSerializer<*>? = null
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
            // enums are dumb. Luckily this should be a very rare case.
            val annotations = queuedAnnotations()

            if (annotations.isEmpty()) {    // if there's nothing to validate then don't even try to find a serializer
                path.removeLastOrNull()
                annotationStack.removeLastOrNull()
                return
            }

            val serializer = lastParentSerializer
                ?.childSerializersOrNull()
                ?.find { it.descriptor.serialName == enumDescriptor.serialName }

            if (serializer == null) {
                if (printInvalidTypeWarnings) println("WARN!! Could not determine a serializer for enum ${enumDescriptor.serialName}, skipping validation.")
                return
            }

            val decoder = enumDecoder ?: StringArrayFormat(serializersModule).also { enumDecoder = it }

            validate(
                type = SerialKType.Specified(
                    type = SerialKType.Specified.Type.Exact(serialName = enumDescriptor.serialName),
                    arguments = emptyList(),
                    nullable = false
                ),
                value = decoder.decodeFromString(serializer, enumDescriptor.getElementName(index)),
                annotations = annotations
            )

            path.removeLastOrNull()
            annotationStack.removeLastOrNull()
        }

        private val savedAnnotationStates = ArrayList<ArrayList<List<Annotation>>>()
        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            when (descriptor.kind) {
                StructureKind.MAP -> {}
                StructureKind.LIST -> {}
                else -> {
                    savedAnnotationStates.add(annotationStack)
                    annotationStack = ArrayList()
                }
            }
            return this
        }
        override fun endStructure(descriptor: SerialDescriptor) {
            when (descriptor.kind) {
                StructureKind.MAP -> {}
                StructureKind.LIST -> {}
                else -> {
                    savedAnnotationStates.removeLastOrNull()?.let {
                        annotationStack = it
                    }
                }
            }
        }

        private fun <T> validate(serializer: KSerializer<T>, value: T) {
            val annotations = queuedAnnotations()
            if (annotations.isEmpty()) return

            if (serializer is ShouldValidateSub<T>) serializer.validate(
                value,
                annotations
            ) { sub: ShouldValidateSub.SerializerAndValue<*>, intercepted: List<Annotation> ->
                validate(SerialKType(sub.serializer), sub.value, intercepted)
            }
            else validate(SerialKType(serializer), value, annotations)
        }

        private fun validate(
            type: SerialKType,
            value: Any?,
            annotations: List<Annotation>,
        ) {
            annotations.forEach { annotation ->
                if (doSuspendingChecks) {
                    val (fast, slow) = validators.getJoint(suspendingValidators, annotation::class, type) ?: return@forEach
                    val path = path.joinToString(".") // need to pre-calculate for lazy suspending check

                    fast?.invoke(annotation, value)?.let { issues[path] = it }
                    if (slow != null) queuedSuspendingChecks.add { slow(annotation, value)?.let { issues[path] = it } }
                } else {
                    validators.get(annotation::class, type)
                        ?.invoke(annotation, value)
                        ?.let { issues[path.joinToString(".")] = it }
                }
            }
        }
    }
}

internal fun KClass<*>.normalizedTypeName(): String = qualifiedName ?: toString().let { str ->
    if (str.startsWith("interface")) str.removePrefix("interface").trim().split(' ')[0].trim()
    else str.split('$').dropLast(1).last().replace('_', '.').trim()
}

public interface ShouldValidateSub<A> : KSerializer<A> {
    public data class SerializerAndValue<T>(val serializer: KSerializer<T>, val value: T)

    public fun validate(value: A, annotations: List<Annotation>, defer: (value: SerializerAndValue<*>, annotations: List<Annotation>) -> Unit)
}


/**
 * Constructs a new set of [AnnotationValidators] with the provided [serializersModule]
 * */
public inline fun AnnotationValidators(
    serializersModule: SerializersModule = EmptySerializersModule(),
    setup: AnnotationValidators.Builder.() -> Unit
): AnnotationValidators = AnnotationValidators.Builder(serializersModule).apply(setup).build()

/**
 * Constructs an empty set of [AnnotationValidators] with the provided [serializersModule]
 * */
@Suppress("FunctionName")
public fun EmptyAnnotationValidators(
    serializersModule: SerializersModule = EmptySerializersModule()
): AnnotationValidators = AnnotationValidators.Builder(serializersModule).build()

/**
 * Returns [AnnotationValidators.Standard] with the provided [serializersModule]
 * */
public fun AnnotationValidators(
    serializersModule: SerializersModule = EmptySerializersModule()
): AnnotationValidators =
    if (serializersModule === EmptySerializersModule()) AnnotationValidators.Standard
    else AnnotationValidators.Standard overwriteWith EmptyAnnotationValidators(serializersModule)
