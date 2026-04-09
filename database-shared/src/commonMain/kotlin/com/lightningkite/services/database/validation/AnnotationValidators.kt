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
 * This class is designed with ergonomics similar to [SerializersModule].
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
    /**
     * Combines this set of [AnnotationValidators] with [other], throwing an exception if there are any overlapping definitions.
     *
     * To overwrite validators without throwing see [overwriteWith].
     * */
    public operator fun plus(other: AnnotationValidators): AnnotationValidators =
        if (this === other) this else AnnotationValidators(
            serializersModule + other.serializersModule,
            validators.combineWith(other.validators, overwrite = false),
            suspendingValidators.combineWith(other.suspendingValidators, overwrite = false)
        )

    /** Combines this set of [AnnotationValidators] with [other], any overlapping definitions will be taken from [other] */
    public infix fun overwriteWith(other: AnnotationValidators): AnnotationValidators =
        if (this === other) this else AnnotationValidators(
            serializersModule overwriteWith other.serializersModule,
            validators.combineWith(other.validators, overwrite = true),
            suspendingValidators.combineWith(other.suspendingValidators, overwrite = true)
        )

    /**
     * Validates the provided [value] recursively using applied property annotations.
     *
     * @return A map of any found validation issues where the keys are `.` separated paths to the value which failed
     *         validation and the values are found issues. Ex. `{photo.file.size="Too large; maximum size of 5 bytes but got 10"}`
     * */
    public suspend fun <T> validate(serializer: KSerializer<T>, value: T): Map<String, String> {
        val e = ValidationEncoder(doSuspendingChecks = true)
        e.encodeSerializableValue(serializer, value)
        e.runQueuedSuspendingChecks()
        return e.issues
    }

    /**
     * Validates the provided [value] recursively using applied property annotations, skipping any `suspending`
     * validation checks.
     *
     * For full validation, including suspending checks, use [validate].
     *
     * @return A map of any found validation issues where the keys are `.` separated paths to the value which failed
     *         validation and the values are found issues. Ex. `{photo.file.size="Too large; maximum size of 5 bytes but got 10"}`
     * */
    public fun <T> validateSkipSuspending(serializer: KSerializer<T>, value: T): Map<String, String> {
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
                    val s = if (qualified) it.toString(qualified = true)
                    else it.toString()
                    "\n\t\t$s"
                }
                suspending[annotation]?.joinTo(
                    this,
                    prefix = if (fast != null) ", " else ""
                ) {
                    val s = if (qualified) it.toString(qualified = true)
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
            val key = annotation.normalizedTypeName() ?: throw IllegalArgumentException("Cannot determine type name for $annotation.")
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
            validate<MaxSize, HashMap<*, *>> {
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

        var suppressWarnings = false
        private val printWarnings get() = printInvalidTypeWarnings && !suppressWarnings

        fun entries(): Map<String, List<SerialKType>> = map.mapValues { entry -> entry.value.map { it.first } }

        fun put(annotation: KClass<out Annotation>, type: SerialKType, value: T) {
            map.getOrPut(annotation.normalizedTypeName() ?: return, ::ArrayList).add(type to value)
        }

        fun finalize() {
            for (list in map.values) list.sortBy { it.first.generality() }  // we want to search most_specific->least_specific
        }



        private fun SerialKType.listOrMapElements(): List<SerialKType>? {
            val kind = when (this) {
                is SerialKType.Specified -> descriptor.kind
                SerialKType.Wildcard -> return null
            }
            return if (kind == StructureKind.LIST || kind == StructureKind.MAP) arguments else null
        }

        fun get(annotation: KClass<out Annotation>, type: SerialKType): T? {
            val forAnnotation = map[annotation.normalizedTypeName()] ?: return null
            val found = forAnnotation.firstOrNull { it.first.matches(type) }?.second
            if (found == null && printWarnings &&
                type.listOrMapElements()?.none { e ->   // suppress this warning when the annotation applies to the list/map elements, if not the list itself.
                    forAnnotation.any { it.first.matches(e) }
                } != false
            ) {
                println(
                    "${annotation.simpleName ?: annotation.normalizedTypeName()} applied to invalid type: $type. Valid types: [${
                        forAnnotation.joinToString { it.first.toString() }
                    }]. Ignoring validation.   (Set AnnotationValidators.printInvalidTypeWarnings = false to suppress this warning)"
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

            if (firstFound == null && secondFound == null && printWarnings &&
                type.listOrMapElements()?.none { e ->   // suppress this warning when the annotation applies to the list/map elements, if not the list itself.
                    first?.any { it.first.matches(e) } == true || second?.any { it.first.matches(e) } == true
                } != false
            ) println(buildString {
                append("${annotation.simpleName ?: annotation.normalizedTypeName()} applied to invalid type: $type. Valid types: [")
                first?.joinTo(this) { it.first.toString() }
                second?.joinTo(this) { "${it.first}(S)" }
                append("]. Ignoring validation.   (Set AnnotationValidators.printInvalidTypeWarnings = false to suppress this warning)")
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


    // I had Claude write docs for this internal stuff because it was fucking hard to figure out.

    private var enumDecoder: StringArrayFormat? = null

    /**
     * Custom encoder that validates values as they're encoded.
     *
     * This walks through the serialization tree, tracking the current path and annotations
     * for each field, then runs validators against each value.
     *
     * ## How it works:
     * 1. As the serializer encodes each field, [encodeElement] captures the field name and annotations
     * 2. When primitive values are encoded (via encodeInt, encodeString, etc.), we validate them
     * 3. For complex types, [encodeSerializableValue] handles validation
     * 4. Path is built up as we go (e.g., "user.address.city") for error messages
     * 5. Annotations are stacked and flattened so nested types can inherit validations
     */
    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    private inner class ValidationEncoder(val doSuspendingChecks: Boolean) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = this@AnnotationValidators.serializersModule

        /** Map of field paths to validation error messages. */
        val issues = HashMap<String, String>()

        /** Suspending validators are queued and run after encoding completes. */
        private val queuedSuspendingChecks = ArrayList<suspend () -> Unit>()

        suspend fun runQueuedSuspendingChecks() {
            queuedSuspendingChecks.forEach { it.invoke() }
            queuedSuspendingChecks.clear()
        }

        /** Current path through the object tree (e.g., ["user", "address", "city"]). */
        private val path = ArrayList<String>()

        /**
         * Stack of annotation lists for the current encoding path.
         * Each element corresponds to a field's annotations.
         * Flattened so that annotations on lists or maps cascade to their inner elements.
         */
        private var annotationStack = ArrayList<List<Annotation>>()

        /** Returns all annotations applicable to the current value (flattened from stack). */
        private fun queuedAnnotations() = annotationStack.flatten()

        /**
         * Called when a primitive value is encoded.
         * Validates the value, then pops path/annotation state since we're done with this field.
         */
        private fun <T> encodeValue(serializer: KSerializer<T>, value: T) {
            validate(serializer, value)

            // Element encoded, pop the field name and annotations we pushed in encodeElement
            path.removeLastOrNull()
            annotationStack.removeLastOrNull()
        }

        /**
         * Entry point for complex/serializable values.
         * Validates the value, then continues serialization (which will recursively encode its fields).
         *
         * Structures are also validated through here.
         */
        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            // Save the parent serializer for enum handling (see encodeEnum)
            // This cast should always work - SerializationStrategy is almost always KSerializer in practice
            lastParentSerializer = serializer as KSerializer<T>
            validate(serializer, value)

            // Continue serialization - this will recursively encode all fields of this value
            serializer.serialize(this, value)
        }

        /**
         * Called before encoding each field in a structure.
         * Pushes the field name onto the path and captures its annotations.
         *
         * For example, encoding `user.address.city` will call this 3 times:
         * - encodeElement(..., "user")
         * - encodeElement(..., "address")
         * - encodeElement(..., "city")
         */
        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            path.add(descriptor.getElementName(index))
            annotationStack.add(descriptor.getElementAnnotations(index))
            return true
        }

        // All primitive encode methods validate the value and pop path/annotations
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

        /**
         * Tracks the parent serializer to help reconstruct enum serializers.
         * Enums are encoded as ordinals, but we need the actual enum value for validation.
         */
        private var lastParentSerializer: KSerializer<*>? = null

        /**
         * Handles enum encoding with special logic.
         *
         * Problem: Enums are encoded as Int ordinals (0, 1, 2, ...) but validators need the actual enum value.
         * Solution: We reconstruct the enum serializer from the parent, then decode the ordinal to get the value.
         *
         * This is complex because:
         * - We only receive the ordinal index, not the enum value
         * - We need the serializer to decode "MyEnum.VALUE_A" from the ordinal
         * - We have to search child serializers of the parent to find the right enum serializer
         */
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
            // enums are dumb. but at least this should be a very rare case.

            val annotations = queuedAnnotations()

            // If there are no validators for this enum, skip all the complex reconstruction logic
            if (annotations.isEmpty()) {
                path.removeLastOrNull()
                annotationStack.removeLastOrNull()
                return
            }

            // Find the enum serializer by searching the parent's child serializers
            val serializer = lastParentSerializer
                ?.childSerializersOrNull()
                ?.find { it.descriptor.serialName == enumDescriptor.serialName }

            if (serializer == null) {
                if (printInvalidTypeWarnings) println("WARN!! Could not determine a serializer for enum ${enumDescriptor.serialName}, skipping validation.")
                return
            }

            // Use StringArrayFormat to decode the enum name into an actual enum value
            // (e.g., "ACTIVE" -> Status.ACTIVE)
            val decoder = enumDecoder ?: StringArrayFormat(serializersModule).also { enumDecoder = it }

            // Validate using the reconstructed enum value
            validate(
                type = SerialKType.Specified(
                    descriptor = enumDescriptor,
                    arguments = emptyList(),
                    nullable = false
                ),
                value = decoder.decodeFromString(serializer, enumDescriptor.getElementName(index)),
                annotations = annotations
            )

            path.removeLastOrNull()
            annotationStack.removeLastOrNull()
        }

        /**
         * Stack to save/restore annotation states when entering nested structures.
         * Maps and Lists don't save state because their elements should inherit parent annotations.
         */
        private val savedAnnotationStates = ArrayList<ArrayList<List<Annotation>>>()

        /**
         * Called when entering a nested structure (class, list, map).
         *
         * For classes: We save and reset the annotation stack so nested classes start fresh.
         * For lists/maps: We keep the annotation stack so elements inherit parent annotations.
         *
         * Example:
         * ```
         * data class Wrapper(@MaxLength(5) val items: List<String>)
         * ```
         * When encoding `items`, the @MaxLength annotation should apply to each String element,
         * so we don't clear the annotation stack for lists.
         */
        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            when (descriptor.kind) {
                StructureKind.MAP -> {} // Maps: keep annotation stack (applies to entries)
                StructureKind.LIST -> {} // Lists: keep annotation stack (applies to elements)
                else -> {
                    // Classes/Objects: save current state and start fresh
                    savedAnnotationStates.add(annotationStack)
                    annotationStack = ArrayList()
                }
            }
            return this
        }

        /**
         * Called when exiting a nested structure.
         * Restores the saved annotation state for classes (lists/maps don't save state).
         */
        override fun endStructure(descriptor: SerialDescriptor) {
            when (descriptor.kind) {
                StructureKind.MAP -> {} // No state to restore
                StructureKind.LIST -> {} // No state to restore
                else -> {
                    // Restore the annotation state from before we entered this structure
                    savedAnnotationStates.removeLastOrNull()?.let {
                        annotationStack = it
                    }
                }
            }
        }

        /**
         * Main validation entry point - validates a value against its queued annotations.
         *
         * Handles special case of [ShouldValidateSub] which allows serializers to customize
         * how their sub-elements are validated (used by collection serializers that validate elements).
         */
        private fun <T> validate(serializer: KSerializer<T>, value: T) {
            val annotations = queuedAnnotations()
            if (annotations.isEmpty()) return

            // Special handling for serializers that want custom sub-validation
            // (e.g., a collection that wants to validate each element individually)
            if (serializer is ShouldValidateSub<T>) serializer.validate(
                value,
                annotations
            ) { sub: ShouldValidateSub.SerializerAndValue<*>, intercepted: List<Annotation> ->
                validate(SerialKType(sub.serializer), sub.value, intercepted)
            }
            else if (printInvalidTypeWarnings) {    // cascaded annotations spam warnings, this fixes that.
                val onElement = annotationStack.last()
                val cascaded = annotationStack.dropLast(1).flatten()
                val type = SerialKType(serializer)

                validate(type, value, onElement)

                if (cascaded.isNotEmpty()) {
                    try {
                        validators.suppressWarnings = true
                        suspendingValidators.suppressWarnings = true
                        validate(type, value, cascaded)
                    } finally {
                        validators.suppressWarnings = false
                        suspendingValidators.suppressWarnings = false
                    }
                }
            }
            else validate(SerialKType(serializer), value, annotations)
        }

        /**
         * Core validation logic - runs validators for each annotation on the value.
         *
         * Algorithm:
         * 1. For each annotation on the field
         * 2. Look up validators registered for that annotation + type combination
         * 3. Run synchronous validators immediately, capturing any error messages
         * 4. Queue suspending validators to run later (after encoding completes)
         *
         * Type matching is done via SerialKType, which allows validators to be registered
         * for specific types (e.g., @MaxLength only for String, not Int).
         */
        private fun validate(
            type: SerialKType,
            value: Any?,
            annotations: List<Annotation>,
        ) {
            annotations.forEach { annotation ->
                if (doSuspendingChecks) {
                    // Get both fast and suspending validators for this annotation+type
                    val (fast, slow) = validators.getJoint(suspendingValidators, annotation::class, type) ?: return@forEach

                    // Pre-calculate path since suspending validators run later (path will change)
                    val path = path.joinToString(".")

                    // Run synchronous validator immediately
                    fast?.invoke(annotation, value)?.let { issues[path] = it }

                    // Queue suspending validator to run after encoding completes
                    if (slow != null) queuedSuspendingChecks.add { slow(annotation, value)?.let { issues[path] = it } }
                } else {
                    // Only run synchronous validators (skip suspending)
                    validators.get(annotation::class, type)
                        ?.invoke(annotation, value)
                        ?.let { issues[path.joinToString(".")] = it }
                }
            }
        }
    }
}

// I'm not sure if this works on anything other than JVM
internal fun KClass<*>.normalizedTypeName(): String? = toString().let { str ->
    if (str.startsWith("interface")) str.removePrefix("interface").trim().split(' ').getOrNull(0)?.trim()
    else str.split('$').dropLast(1).lastOrNull()?.replace('_', '.')?.trim()
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
