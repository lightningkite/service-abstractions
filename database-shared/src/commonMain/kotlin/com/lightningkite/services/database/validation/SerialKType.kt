package com.lightningkite.services.database.validation

import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.typeParametersSerializersOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

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

    /** Returns a fully qualified string representation (includes package names). */
    public fun qualifiedString(): String

    /**
     * Wildcard type that matches any type (like `*` in Kotlin generics).
     *
     * Used for star projections in generic types, e.g., `List<*>`.
     */
    public data object Wildcard : SerialKType {
        override fun matches(serializer: KSerializer<*>): Boolean = true
        override fun matches(type: SerialKType): Boolean = true
        override fun toString(): String = "*"
        override fun qualifiedString(): String = "*"
    }

    /**
     * A concrete type specification with type parameters and nullability.
     *
     * @property type The base type, either by exact serial name or by SerialKind
     * @property arguments Type parameters (e.g., `String` in `List<String>`)
     * @property nullable Whether this type is nullable
     */
    public data class Specified(
        val type: Type,
        val arguments: List<SerialKType>,
        val nullable: Boolean
    ) : SerialKType {
        /**
         * How to identify the base type.
         *
         * - [Kind]: Match by SerialKind (e.g., all lists, all maps) - more general
         * - [Exact]: Match by exact serial name (e.g., "kotlin.collections.ArrayList") - more specific
         */
        public sealed interface Type {
            /** Match by SerialKind (e.g., StructureKind.LIST matches any list type). */
            public data class Kind(val kind: SerialKind) : Type

            /** Match by exact serial name (e.g., "kotlin.String"). */
            public data class Exact(val serialName: String) : Type
        }

        override fun matches(serializer: KSerializer<*>): Boolean {
            if (serializer.descriptor.isNullable && !nullable) return false

            val serializer = serializer.nullElement() ?: serializer

            val typeMatches = when (type) {
                is Type.Kind -> serializer.descriptor.kind == type.kind
                is Type.Exact -> serializer.descriptor.serialName == type.serialName
            }

            if (!typeMatches) return false
            if (arguments.isEmpty() || arguments.all { it == Wildcard }) return true

            val args = serializer.typeParametersSerializersOrNull()
                ?: throw IllegalArgumentException("Cannot determine type parameters for type with serialName `${serializer.descriptor.serialName}`")

            if (args.size != arguments.size) return false

            return args.indices.all { arguments[it].matches(args[it]) }
        }

        override fun matches(type: SerialKType): Boolean {
            return when (type) {
                Wildcard -> false
                is Specified -> {
                    if (this.type != type.type) return false
                    if (arguments.size != type.arguments.size) return false
                    if (arguments.isEmpty()) return true

                    return arguments.indices.all { arguments[it].matches(type.arguments[it]) }
                }
            }
        }

        override fun toString(): String {
            val t = when (type) {
                is Type.Kind -> type.kind.toString()
                is Type.Exact -> type.serialName.substringAfterLast('.')
            }
            return if (arguments.isEmpty()) t
            else "$t<${arguments.joinToString()}>" + if (nullable) '?' else ""
        }

        override fun qualifiedString(): String {
            val t = when (type) {
                is Type.Kind -> type.kind.toString()
                is Type.Exact -> type.serialName
            }
            return if (arguments.isEmpty()) t
            else "$t<${arguments.joinToString { it.qualifiedString() }}>" + if (nullable) '?' else ""
        }
    }
}

private fun KType.noStarProjections(): Boolean =
    arguments.isEmpty() || arguments.all { it.type?.noStarProjections() == true }

private val kindIsSameAsType = buildMap {
    fun add(serializer: KSerializer<*>) = put(serializer.descriptor.serialName, SerialKType.Specified.Type.Kind(serializer.descriptor.kind))

    add(MapSerializer(Int.serializer(), Int.serializer()))
    add(ListSerializer(Int.serializer()))
}

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

    val serialName = (serializer.nullElement() ?: serializer).descriptor.serialName

    val d = SerialKType.Specified(
        type = kindIsSameAsType[serialName] ?: SerialKType.Specified.Type.Exact(serialName),
        arguments = kType.arguments.map { arg ->
            arg.type?.let { SerialKType(it, module) } ?: SerialKType.Wildcard
        },
        nullable = kType.isMarkedNullable
    )

    return d
}

public inline fun <reified T> serialKTypeOf(module: SerializersModule = EmptySerializersModule()): SerialKType = SerialKType(typeOf<T>(), module)

@Suppress("FunctionName")
public fun SerialKType(serializer: KSerializer<*>): SerialKType.Specified {
    val inner = serializer.nullElement() ?: serializer
    return SerialKType.Specified(
        type = kindIsSameAsType[inner.descriptor.serialName] ?: SerialKType.Specified.Type.Exact(inner.descriptor.serialName),
        arguments = inner.typeParametersSerializersOrNull()?.map(::SerialKType) ?: emptyList(),
        nullable = serializer.descriptor.isNullable
    )
}

/**
 * Returns a generality score from 0.0 (most specific) to 1.0 (most general).
 *
 * Used to sort validators from most specific to most general, ensuring that
 * exact type matches are tried before wildcard matches.
 *
 * Scoring:
 * - Wildcard (`*`): 1.0 (most general)
 * - Type.Kind (e.g., any LIST): 0.25 base + type arg generality
 * - Type.Exact (e.g., kotlin.collections.ArrayList): 0.0 base + type arg generality
 * - Type arguments contribute up to 0.5 to the score
 *
 * Examples:
 * - `String`: 0.0 (exact type, no args)
 * - `List<String>`: 0.0 (exact type, exact arg)
 * - `List<*>`: 0.5 (exact type, wildcard arg)
 * - `StructureKind.LIST`: 0.25 (kind match, no args)
 */
@OptIn(ExperimentalSerializationApi::class)
public fun SerialKType.generality(): Double =
    when (this) {
        is SerialKType.Specified -> {
            val n = arguments.size
            val argG = if (n == 0) 0.0
            else {
                // Type arguments contribute up to 0.5 total to the generality score
                val r = 0.5 / n.toDouble()
                arguments.sumOf { it.generality() * r }
            }
            argG + when (type) {
                is SerialKType.Specified.Type.Exact -> 0.0  // Most specific
                is SerialKType.Specified.Type.Kind -> 0.25  // Less specific
            }
        }
        SerialKType.Wildcard -> 1.0  // Most general
    }

/** Returns specificity score (inverse of generality). Higher = more specific. */
public fun SerialKType.specificity(): Double = 1.0 - generality()