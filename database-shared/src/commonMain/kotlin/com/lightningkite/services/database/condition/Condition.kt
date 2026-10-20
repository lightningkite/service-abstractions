@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.data.*
import com.lightningkite.services.data.Length.Companion.kilometers
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.*

/**
 * Represents a boolean query condition for filtering database records.
 *
 * Condition forms a composable query DSL that can be:
 * - **Serialized**: All conditions are @Serializable for network transport/storage
 * - **Evaluated**: Can test against in-memory objects via invoke()
 * - **Translated**: Database implementations convert to native queries (MongoDB, SQL, etc.)
 *
 * ## Core Conditions
 *
 * - [Never]/[Always] - Constant true/false (useful for dynamic query building)
 * - [Equal]/[NotEqual] - Equality comparisons
 * - [Inside]/[NotInside] - Set membership
 * - [GreaterThan]/[LessThan] etc. - Numeric comparisons
 * - [And]/[Or]/[Not] - Boolean logic combinators
 *
 * ## Special Conditions
 *
 * - [StringContains] - Substring search
 * - [RegexMatches] - Regular expression matching (may not be supported by all backends)
 * - [GeoDistance] - Geospatial queries by distance
 * - [IntBitsClear]/[IntBitsSet] etc. - Bitwise operations
 *
 * ## Collection Conditions
 *
 * - [CollectionAllElements]/[CollectionAnyElements] - Test all/any elements of a list or set
 *
 * ## Nested Conditions
 *
 * - [OnField] - Navigate to nested object fields (created by optics)
 * - [OnKey] - Access map values by key
 * - [IfNotNull] - Null-safe condition wrapper
 *
 * ## Usage with Type-Safe DSL
 *
 * ```kotlin
 * // Using generated optic extensions
 * val adults = userTable.find(
 *     condition = User.path.age gte 18
 * )
 *
 * // Complex conditions
 * val activeVips = User.path.status eq UserStatus.Active and
 *                  (User.path.tier eq Tier.VIP)
 *
 * // Nested conditions
 * val hasVerifiedEmail = User.path.email.notNull eq "verified@example.com"
 * ```
 *
 * ## Important Gotchas
 *
 * - **Backend support varies**: Not all database backends support all conditions (e.g., RegexMatches)
 * - **hashCode/equals**: Throw NotImplementedError by default - only specific implementations override
 * - **Type variance**: Condition is contravariant (Condition<Any?> is a valid Condition<String>)
 *
 * @param T The type of values this condition can test
 */
@Serializable(ConditionSerializer::class)
public sealed class Condition<in T> {
    open override fun hashCode(): Int = throw NotImplementedError()
    open override fun equals(other: Any?): Boolean = throw NotImplementedError()
    public open operator fun invoke(on: T): Boolean = throw NotImplementedError()

    @Serializable
    @DisplayName("None")
    public data object Never : Condition<Any?>() {

        override fun invoke(on: Any?): Boolean = false
        override fun toString(): String = "false"
    }

    @Serializable
    @DisplayName("All")
    public data object Always : Condition<Any?>() {

        override fun invoke(on: Any?): Boolean = true
        override fun toString(): String = "true"
    }

    @Serializable(ConditionAndSerializer::class)
    public data class And<T>(val conditions: List<Condition<T>>) : Condition<T>() {
        public constructor(vararg conditions: Condition<T>) : this(conditions.toList())

        override fun invoke(on: T): Boolean = conditions.all { it(on) }
        override fun toString(): String = conditions.joinToString(" && ", "(", ")")
    }

    @Serializable(ConditionOrSerializer::class)
    public data class Or<T>(val conditions: List<Condition<T>>) : Condition<T>() {
        public constructor(vararg conditions: Condition<T>) : this(conditions.toList())

        override fun invoke(on: T): Boolean = conditions.any { it(on) }
        override fun toString(): String = conditions.joinToString(" || ", "(", ")")
    }

    @Serializable(ConditionNotSerializer::class)
    public data class Not<T>(val condition: Condition<T>) : Condition<T>() {
        override fun invoke(on: T): Boolean = !condition(on)
        override fun toString(): String = "!($condition)"
    }

    @Serializable(ConditionEqualSerializer::class)
    public data class Equal<T>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on == value
        override fun toString(): String = " == $value"
    }

    @Serializable(ConditionNotEqualSerializer::class)
    public data class NotEqual<T>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on != value
        override fun toString(): String = " != $value"
    }

    @Serializable(ConditionInsideSerializer::class)
    public data class Inside<T>(val values: Set<T>) : Condition<T>() {
        public constructor(values: Collection<T>) : this(values.toSet())

        override fun invoke(on: T): Boolean = values.contains(on)
        override fun toString(): String = " in $values"
    }

    @Serializable(ConditionNotInsideSerializer::class)
    public data class NotInside<T>(val values: Set<T>) : Condition<T>() {
        public constructor(values: Collection<T>) : this(values.toSet())

        override fun invoke(on: T): Boolean = !values.contains(on)
        override fun toString(): String = " !in $values"
    }

    @Serializable(ConditionGreaterThanSerializer::class)
    public data class GreaterThan<T : Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on > value
        override fun toString(): String = " > $value"
    }

    @Serializable(ConditionLessThanSerializer::class)
    public data class LessThan<T : Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on < value
        override fun toString(): String = " < $value"
    }

    @Serializable(ConditionGreaterThanOrEqualSerializer::class)
    public data class GreaterThanOrEqual<T : Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on >= value
        override fun toString(): String = " >= $value"
    }

    @Serializable(ConditionLessThanOrEqualSerializer::class)
    public data class LessThanOrEqual<T : Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on <= value
        override fun toString(): String = " <= $value"
    }

    @Serializable
    public data class StringContains(@DoesNotNeedLabel val value: String, val ignoreCase: Boolean = false) :
        Condition<String>() {
        override fun invoke(on: String): Boolean = on.contains(value, ignoreCase)
        override fun toString(): String = ".contains($value, $ignoreCase)"
    }

    @Serializable
    public data class GeoDistance(
        val value: GeoCoordinate,
        val greaterThan: Length = 0.0.kilometers,
        val lessThan: Length = 100_000.0.kilometers,
    ) : Condition<GeoCoordinate>() {
        override fun invoke(on: GeoCoordinate): Boolean =
            on.distanceTo(value) in greaterThan..lessThan

        override fun toString(): String = ".distanceTo($value) in $greaterThan..$lessThan"
    }

    /**
     * Matches if [pattern] is found anywhere within the value -- unanchored, like MongoDB's `$regex`,
     * Postgres's `~`, and SQL's `REGEXP`, all of which search for the pattern anywhere in the string
     * rather than requiring it to match the entire value. A caller that wants a full-string match can
     * anchor the pattern themselves with `^…$`.
     */
    @Serializable
    public data class RegexMatches(
        @DoesNotNeedLabel val pattern: String,
        val ignoreCase: Boolean = false
    ) : Condition<String>() {
        @Transient
        private val regex = Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else setOf())
        override fun invoke(on: String): Boolean = regex.containsMatchIn(on)
        override fun toString(): String = ".contains(Regex($pattern), $ignoreCase)"
    }

    @Serializable(ConditionIntBitsClearSerializer::class)
    public data class IntBitsClear(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask == 0
        override fun toString(): String = ".bitsClear(${mask.toString(16)})"
    }

    @Serializable(ConditionIntBitsSetSerializer::class)
    public data class IntBitsSet(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask == mask
        override fun toString(): String = ".bitsSet(${mask.toString(16)})"
    }

    @Serializable(ConditionIntBitsAnyClearSerializer::class)
    public data class IntBitsAnyClear(val mask: Int) : Condition<Int>() {
        // Stated as the exact negation of IntBitsSet rather than `on and mask < mask`. Ordered
        // comparison is signed, so a mask containing bit 31 is negative and `<` gives the wrong
        // answer for the very highest bit of the field.
        override fun invoke(on: Int): Boolean = on and mask != mask
        override fun toString(): String = ".bitsAnyClear(${mask.toString(16)})"
    }

    @Serializable(ConditionIntBitsAnySetSerializer::class)
    public data class IntBitsAnySet(val mask: Int) : Condition<Int>() {
        // The exact negation of IntBitsClear; see IntBitsAnyClear for why not `on and mask > 0`.
        override fun invoke(on: Int): Boolean = on and mask != 0
        override fun toString(): String = ".bitsAnySet(${mask.toString(16)})"
    }

    @Serializable(ConditionCollectionAllElementsSerializer::class)
    public data class CollectionAllElements<C: Collection<E>, E>(val condition: Condition<E>) : Condition<C>() {
        override fun invoke(on: C): Boolean = on.all { condition(it) }
        override fun toString(): String = ".all { it$condition }"
    }

    @Serializable(ConditionCollectionAnyElementsSerializer::class)
    public data class CollectionAnyElements<C: Collection<E>, E>(val condition: Condition<E>) : Condition<C>() {
        override fun invoke(on: C): Boolean = on.any { condition(it) }
        override fun toString(): String = ".any { it$condition }"
    }

    public data class OnField<K, V>(
        val key: SerializableProperty<in K, V>,
        val condition: Condition<V>,
    ) : Condition<K>() {
        override fun invoke(on: K): Boolean = condition(key.get(on))
        override fun toString(): String {
            return if (condition is OnField<*, *>)
                "${key.name}.$condition"
            else
                "${key.name}$condition"
        }
    }

    @Serializable(ConditionIfNotNullSerializer::class)
    public data class IfNotNull<T>(val condition: Condition<T>) : Condition<T?>() {
        override fun invoke(on: T?): Boolean = on != null && condition(on)
        override fun toString(): String = "? $condition"
    }

    public data class IfIsType<T, V : T>(
        val discriminator: SealedTypeDiscriminator<V>,
        val condition: Condition<V>
    ) : Condition<T>() {
        public constructor(
            type: KSerializer<V>,
            condition: Condition<V>
        ) : this(SealedTypeDiscriminator(type), condition)

        override fun invoke(on: T): Boolean = discriminator.handle(on,
            matchFail = { false },
            matchSuccess = { condition(it) }
        )

        override fun toString(): String = " as? ${discriminator.serialName} $condition"
    }
}

public infix fun <T> Condition<T>.and(other: Condition<T>): Condition.And<T> = Condition.And(listOf(this, other))
public infix fun <T> Condition<T>.or(other: Condition<T>): Condition.Or<T> = Condition.Or(listOf(this, other))
public operator fun <T> Condition<T>.not(): Condition.Not<T> = Condition.Not(this)

public fun <T> Condition.Companion.andNotNull(vararg conditions: Condition<T>?): Condition<T> {
    val list = conditions.toList().filterNotNull()
    return when (list.size) {
        0 -> Condition.Always // vacuous truth
        1 -> list.first()
        else -> Condition.And(list)
    }
}

public fun <T> Condition.Companion.orNotNull(vararg conditions: Condition<T>?): Condition<T> {
    val list = conditions.toList().filterNotNull()
    return when (list.size) {
        0 -> Condition.Never
        1 -> list.first()
        else -> Condition.Or(list)
    }
}

/**
 * Creates a conditional condition that ensures logical consistency between two conditions.
 *
 * If the `if_` condition is true, the `then` condition must also be true for the overall condition to be true.
 * If the `if_` condition is false, the overall condition will always return true, regardless of the `then` condition.
 *
 * This can be thought of as a logical implication: `if_` implies `then`.
 *
 * Useful when building [Mask] or [UpdateRestrictions] when changes only _sometimes_ must meet conditions
 */
public fun <T> Condition.Companion.ifThen(if_: Condition<T>, then: Condition<T>): Condition.Or<T> =
    (if_ and then) or !if_

/**
 * Creates a conditional condition that evaluates to one of two conditions based on another condition.
 *
 * If the `if_` condition is true, the result will be the `then` condition.
 * If the `if_` condition is false, the result will be the `else_` condition.
 *
 * This can be thought of as a logical ternary operation: `if_ ? then : else_`.
 *
 * Useful when you need to express alternative sets of requirements
 */
public fun <T> Condition.Companion.ifThenElse(
    if_: Condition<T>,
    then: Condition<T>,
    else_: Condition<T>,
): Condition.Or<T> =
    (if_ and then) or (!if_ and else_)

