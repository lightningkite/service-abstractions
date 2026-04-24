package com.lightningkite.services.database.validation

import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.typeParametersSerializersOrNull
import kotlinx.serialization.*
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.*

/**
 * A type representation system for `kotlinx.serialization` types.
 *
 * ## Type Matching
 * Types are matched by serial name and type parameters:
 * - `List<String>` matches `List<String>` exactly
 * - `List<*>` matches any `List` (e.g., `List<Int>`, `List<String>`)
 * - Nullability is considered: `String` does not match `String?`, but `String?` can match `String`.
 *
 * ## Use Cases
 * This is primarily used in [AnnotationValidators] to match validators to their targeted type.
 *
 * ### The Problem This Solves
 * You can retrieve a serializer for an arbitrary type `T` using built-in kotlin
 * serialization functions like `SerializersModule.serializer<T>()`. However, this
 * does not work with star projections. For example, `SerializersModule.serializer<List<*>>()`
 * will throw an exception because `*` can't be serialized. Which makes sense.
 *
 * However, for the case of [AnnotationValidators] (which function based on serialization)
 * it makes sense to add validators that are generic over some time. For example
 *
 * ```kotlin
 * AnnotationValidators {
 *    validate<MaxSize, List<*>> {
 *       if (it.size > size) "Too long; got ${it.size} items but the maximum allowed is $size"
 *       else null
 *    }
 * }
 * ```
 *
 * Here we establish a validator for the `MaxSize` annotation that verifies that any
 * list it validates is at most the specified maximum size.
 *
 * We want this validation to work for any list, regardless of type, so we need some
 * new way to describe a type with generics, similar to [KType], but using serialization
 * terminology and logic so that it can be used to later match against concrete serializers
 * where the type parameters are known.
 *
 * ## Hierarchy
 * - [Wildcard] - Matches any type (analogous to `*` in Kotlin generics)
 * - [Specified] - A concrete type with optional type parameters and nullability
 *
 * ## Examples
 * ```kotlin
 * // Create from reified type parameter
 * val listOfStrings: SerialKType = serialKTypeOf<List<String>>()
 *
 * // Create from reified type parameter
 * val listOfAnything: SerialKType = serialKTypeOf<List<*>>()
 *
 * // Check if a serializer matches
 * val stringList = ListSerializer(String.serializer())
 *
 * assertTrue(listOfStrings.matches(stringList))
 * assertTrue(listOfAnything.matches(stringList))
 *
 * // Create from KSerializer
 * val altListOfStrings = SerialKType(stringList)
 *
 * // As long as it has a corresponding serializer, you can define a SerialKType for it.
 * val wtf = serialKTypeOf<Map<Pair<Int, *>, Map<String, List<Pair<*, *>>>()
 *
 * ```
 */
public sealed interface SerialKType {
    /** Returns true if the given serializer matches this type specification. */
    public fun matches(serializer: KSerializer<*>): Boolean

    /** Returns true if the given SerialKType matches this type specification. */
    public fun matches(type: SerialKType): Boolean

    /**Returns a string representation of the type, optionally using [qualified] names.*/
    public fun toString(qualified: Boolean): String

    /**
     * Wildcard type that matches any type (like `*` in Kotlin generics).
     *
     * Used for star projections in generic types, e.g., `List<*>`.
     */
    public data object Wildcard : SerialKType {
        override fun matches(serializer: KSerializer<*>): Boolean = true
        override fun matches(type: SerialKType): Boolean = true
        override fun toString(): String = "*"
        override fun toString(qualified: Boolean): String = "*"
    }

    /**
     * A concrete type specification with type parameters and nullability.
     *
     * @property type The base type, either by exact serial name or by SerialKind
     * @property arguments Type parameters (e.g., `String` in `List<String>`)
     * @property nullable Whether this type is nullable
     */
    public data class Specified(
        val descriptor: SerialDescriptor,
        val arguments: List<SerialKType>,
        val nullable: Boolean,
    ) : SerialKType {
        public val serialName: String get() = descriptor.serialName

        override fun matches(serializer: KSerializer<*>): Boolean {
            if (serializer.descriptor.isNullable && !nullable) return false

            val serializer = serializer.nullElement() ?: serializer

            if (serializer.descriptor.serialName != serialName) return false

            // If no type arguments or all wildcards, we match
            if (arguments.isEmpty() || arguments.all { it == Wildcard }) return true

            // Get type parameters from serializer
            val args = serializer.typeParametersSerializersOrNull()
            if (args == null) {
                // Cannot determine type parameters - this is an error case
                if (AnnotationValidators.printInvalidTypeWarnings)
                    IllegalArgumentException("Cannot determine type parameters for type with serialName `${serializer.descriptor.serialName}`").printStackTrace()
                return false
            }

            if (args.size != arguments.size) return false

            return args.indices.all { arguments[it].matches(args[it]) }
        }

        override fun matches(type: SerialKType): Boolean {
            return when (type) {
                Wildcard -> false
                is Specified -> {
                    if (this.serialName != type.serialName) return false
                    if (type.nullable && !nullable) return false
                    if (arguments.size != type.arguments.size) return false
                    if (arguments.isEmpty()) return true

                    return arguments.indices.all { arguments[it].matches(type.arguments[it]) }
                }
            }
        }

        override fun toString(qualified: Boolean): String {
            val type = if (qualified) serialName
            else serialName
                .substringAfterLast('.')
                .replace("ArrayList", "List")  // builtin-serializers.
                .replace("LinkedHashSet", "Set")
                .replace("LinkedHashMap", "Map")

            return if (arguments.isEmpty()) type + if (nullable) '?' else ""
            else "$type<${arguments.joinToString { it.toString(qualified) }}>" + if (nullable) '?' else ""
        }

        override fun toString(): String = toString(qualified = false)
    }
}

private fun KType.noStarProjections(): Boolean =
    arguments.isEmpty() || arguments.all { it.type?.noStarProjections() == true }

@OptIn(ExperimentalSerializationApi::class)
public fun SerialKType(kType: KType, module: SerializersModule = EmptySerializersModule()): SerialKType {
    if (kType.classifier == null) return SerialKType.Wildcard

    val serializer =
        if (kType.noStarProjections()) module.serializer(kType)
        else module.serializer(
            kType.classifier as KClass<*>,
            List(kType.arguments.size) { NothingSerializer() },
            isNullable = false
        )

    val d = SerialKType.Specified(
        descriptor = (serializer.nullElement() ?: serializer).descriptor,
        arguments = kType.arguments.map { arg ->
            arg.type?.let { SerialKType(it, module) } ?: SerialKType.Wildcard
        },
        nullable = kType.isMarkedNullable
    )

    return d
}

public inline fun <reified T> serialKTypeOf(module: SerializersModule = EmptySerializersModule()): SerialKType =
    SerialKType(typeOf<T>(), module)

@Suppress("FunctionName")
public fun SerialKType(serializer: KSerializer<*>): SerialKType.Specified {
    val inner = serializer.nullElement() ?: serializer
    return SerialKType.Specified(
        descriptor = inner.descriptor,
        arguments = inner.typeParametersSerializersOrNull()?.map(::SerialKType) ?: emptyList(),
        nullable = serializer.descriptor.isNullable
    )
}

/**
 * The 'generality' `g(T)` score of a [SerialKType] is a measure of how many wildcards are
 * used in the definition of the type, or phrased another way, how "general" the type is,
 * on a scale from 0 to 1.
 *
 * For example, `List<String>` is fully defined and has a generality `g(List<String>) = 0.0`.
 * However `List<*>` is only "half-defined", specified as a list but is a list of any
 * element, and thus its generality is `0.5`.
 *
 * The score is defined as: `g(T<x0, x1, ..., xn>) = if (T == '*') 1.0 else SUM(1/2n * g(xi))`
 *
 * Examples
 * ```
 * g(String) == 0.0
 * g(List<String>) == 0.0
 * g(List<*>) == 0.5
 * g(List<List<*>>) == 0.75
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
public fun SerialKType.generality(): Double =
    when (this) {
        is SerialKType.Specified -> {
            val n = arguments.size
            if (n == 0) 0.0
            else {
                // Type arguments contribute up to 0.5 total to the generality score
                (0.5 / n.toDouble()) * arguments.sumOf { it.generality() }
            }
        }

        SerialKType.Wildcard -> 1.0  // Most general
    }

/** The inverse of the [generality] score, `s(T) = 1.0 - g(T)`. */
public fun SerialKType.specificity(): Double = 1.0 - generality()