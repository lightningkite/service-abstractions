@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer

/**
 * Represents an update operation that transforms a value of type T.
 *
 * Modification forms a composable update DSL that can be:
 * - **Serialized**: All modifications are @Serializable for network transport/storage
 * - **Applied**: Can transform in-memory objects via invoke()
 * - **Translated**: Database implementations convert to native update operations (MongoDB $set/$inc, SQL UPDATE)
 *
 * ## Core Modifications
 *
 * - [Nothing] - No-op modification (useful for conditional updates)
 * - [Assign] - Set field to a specific value
 * - [Chain] - Sequence multiple modifications (applied left-to-right)
 *
 * ## Numeric Modifications
 *
 * - [Increment] - Add a value (supports all Number types)
 * - [Multiply] - Multiply by a value
 * - [CoerceAtMost]/[CoerceAtLeast] - Clamp values to min/max
 *
 * ## Collection Modifications
 *
 * - [ListAppend]/[SetAppend] - Add elements
 * - [ListRemove]/[SetRemove] - Remove elements matching a condition
 * - [ListRemoveInstances]/[SetRemoveInstances] - Remove specific elements
 * - [ListDropFirst]/[ListDropLast] etc. - Remove first/last element
 * - [ListPerElement]/[SetPerElement] - Apply modification to each element matching a condition
 *
 * ## Map Modifications
 *
 * - [Combine] - Merge map entries (overwrites existing keys)
 * - [RemoveKeys] - Delete map entries
 *
 * ## Nested Modifications
 *
 * - [OnField] - Navigate to nested object fields (created by optics)
 * - [IfNotNull] - Null-safe modification wrapper
 *
 * ## Usage with Type-Safe DSL
 *
 * ```kotlin
 * // Using generated optic extensions
 * userTable.updateOne(
 *     condition = User.path._id eq userId,
 *     modification = modification<User> { it ->
 *         it.age += 1
 *         it.lastLoginAt assign Clock.System.now()
 *     }
 * )
 *
 * // Collection modifications
 * modification<Post> { it ->
 *     it.tags += "featured"
 *     it.comments.removeAll { comment -> comment.isSpam eq true }
 * }
 *
 * // Chained modifications
 * modification<Counter> { it ->
 *     it.value += 1
 *     it.value coerceAtMost 100  // Cap at 100
 * }
 * ```
 *
 * ## Important Gotchas
 *
 * - **Chain order matters**: Modifications in Chain are applied sequentially left-to-right
 * - **Nothing is special**: isNothing property allows optimizing out no-op updates
 * - **Type safety via optics**: Direct field access creates OnField wrappers automatically
 * - **Backend support varies**: Some complex modifications may not be supported by all databases
 *
 * @param T The type of values this modification transforms
 */
@Serializable(ModificationSerializer::class)
public sealed class Modification<T> {
    public abstract operator fun invoke(on: T): T

    //    abstract fun invokeDefault(): T
    public open val isNothing: Boolean get() = false

    @Serializable
    public data object Nothing : Modification<Any?>() {
        override val isNothing: Boolean get() = true

        @Suppress("UNCHECKED_CAST")
        public operator fun <T> invoke(): Modification<T> = this as Modification<T>
        override fun invoke(on: Any?): Any? = on

        //        override fun invokeDefault(): Any? = throw IllegalStateException()
        override fun toString(): String = ""
    }

    @Serializable(ModificationChainSerializer::class)
    public data class Chain<T>(val modifications: List<Modification<T>>) : Modification<T>() {
        override val isNothing: Boolean
            get() = modifications.all { it.isNothing }

        override fun invoke(on: T): T = modifications.fold(on) { item, mod -> mod(item) }
        override fun toString(): String = modifications.joinToString("; ") { it.toString() }
    }

    /**
     * Applies [modification] only when the value isn't null. Backends must never write onto a null or missing value:
     * guards validate the in-memory result of a client's modification, so such a write would be a way around them.
     * MongoDB is stricter and leaves the whole row unchanged when any check fails.
     */
    @Serializable(ModificationIfNotNullSerializer::class)
    public data class IfNotNull<T>(val modification: Modification<T>) : Modification<T?>() {
        override fun invoke(on: T?): T? = on?.let { modification(it) }
        override fun toString(): String = "?$modification"
    }

    /** Applies [modification] only when the value is the variant [discriminator] names; see [IfNotNull]. */
    public data class IfIsType<T, V : T>(
        val discriminator: SealedTypeDiscriminator<V>,
        val modification: Modification<V>
    ) : Modification<T>() {
        public constructor(
            serializer: KSerializer<V>,
            modification: Modification<V>
        ) : this(SealedTypeDiscriminator(serializer), modification)

        override fun invoke(on: T): T = discriminator.handle(on,
            matchFail = { on },
            matchSuccess = { modification(it) }
        )

        override fun toString(): String = " as? ${discriminator.serialName} $modification"
    }

    @Serializable(ModificationAssignSerializer::class)
    public data class Assign<T>(val value: T) : Modification<T>() {
        override fun invoke(on: T): T = value
        override fun toString(): String = " = $value"
    }

    @Serializable(ModificationCoerceAtMostSerializer::class)
    public data class CoerceAtMost<T : Comparable<T>>(val value: T) : Modification<T>() {
        override fun invoke(on: T): T = on.coerceAtMost(value)
        override fun toString(): String = " = .coerceAtMost($value)"
    }

    @Serializable(ModificationCoerceAtLeastSerializer::class)
    public data class CoerceAtLeast<T : Comparable<T>>(val value: T) : Modification<T>() {
        override fun invoke(on: T): T = on.coerceAtLeast(value)
        override fun toString(): String = " = .coerceAtLeast($value)"
    }

    @Serializable(ModificationIncrementSerializer::class)
    public data class Increment<T : Number>(val by: T) : Modification<T>() {
        override fun invoke(on: T): T = on + by
        override fun toString(): String = " += $by"
    }

    @Serializable(ModificationMultiplySerializer::class)
    public data class Multiply<T : Number>(val by: T) : Modification<T>() {
        override fun invoke(on: T): T = on * by
        override fun toString(): String = " *= $by"
    }

    @Serializable(ModificationListAppendSerializer::class)
    public data class ListAppend<T>(val items: List<T>) : Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on + items
        override fun toString(): String = " += $items"
    }

    @Serializable(ModificationListRemoveSerializer::class)
    public data class ListRemove<T>(val condition: Condition<T>) : Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.filter { !condition(it) }
        override fun toString(): String = ".removeAll { it.$condition }"
    }

    @Serializable(ModificationListRemoveInstancesSerializer::class)
    public data class ListRemoveInstances<T>(val items: List<T>) : Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on - items
        override fun toString(): String = " -= $items"
    }

    @Serializable
    public data class ListPerElement<T>(
        val condition: Condition<T>,
        val modification: Modification<T>
    ) : Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.map { if (condition(it)) modification(it) else it }
        override fun toString(): String = ".map { if (it.$condition) it.$modification }"
    }

    @Serializable(ModificationSetAppendSerializer::class)
    public data class SetAppend<T>(val items: Set<T>) : Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = (on + items)
        override fun toString(): String = " += $items"
    }

    @Serializable(ModificationSetRemoveSerializer::class)
    public data class SetRemove<T>(val condition: Condition<T>) : Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.filter { !condition(it) }.toSet()
        override fun toString(): String = ".removeAll { it.$condition }"
    }

    @Serializable(ModificationSetRemoveInstancesSerializer::class)
    public data class SetRemoveInstances<T>(val items: Set<T>) : Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on - items
        override fun toString(): String = " -= $items"
    }

    @Serializable
    public data class SetPerElement<T>(
        val condition: Condition<T>,
        val modification: Modification<T>
    ) : Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.map { if (condition(it)) modification(it) else it }.toSet()
        override fun toString(): String = ".map { if (it.$condition) it.$modification }"
    }

    public data class OnField<K, V>(val key: SerializableProperty<K, V>, val modification: Modification<V>) :
        Modification<K>() {
        override fun invoke(on: K): K = key.setCopy(on, modification(key.get(on)))
        override fun toString(): String {
            return if (modification is OnField<*, *>)
                "${key.name}.$modification"
            else if (modification is Chain<*>)
                "${key.name}.let { $modification }"
            else
                "${key.name}$modification"
        }
    }
}

// TODO: API Recommendation - Add atomic compare-and-swap modification
//  Add a CompareAndSet modification that only updates if current value matches expected value.
//  Useful for optimistic locking without external version fields.
//  Example: Modification.CompareAndSet(expected = oldValue, new = newValue)
//
// TODO: API Recommendation - Consider adding list insert/replace at index
//  Currently only support append/remove. Add ListInsertAt/ListReplaceAt for positional updates.
//  This would require index-based access which not all backends support efficiently.
