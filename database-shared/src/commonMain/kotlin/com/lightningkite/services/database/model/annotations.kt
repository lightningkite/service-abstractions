@file:OptIn(ExperimentalSerializationApi::class)
@file:Suppress("PackageDirectoryMismatch")

package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * [InlineProperty] marks a generated [Field] for an inline `value class`.
 *
 * Properties marked with this annotation are accessors to the wrapped type of an inline class.
 *
 * This is an opt-in feature for two reasons:
 *
 * 1. Support for inline properties is not yet supported on the `postgres` database
 * 2. Using this accessor is almost always less efficient than using the wrapper type directly.
 *
 *
 * ## Use Cases
 *
 * There are cases where you should use these properties, such as
 *
 * - An aggregate query on a type that wraps a number
 * - Doing `+=` or `-=` modifications on a type that wraps a number
 * - Doing string queries on a type that wraps a string
 *
 * Simply put, these properties should be used when the type system requires the underlying primitive.
 *
 * Please **do not use this for querying on equality**. Just use the wrapper type.
 *
 * ## Example
 *
 * ```kotlin
 * // a value class wrapping a Long
 * value class Money(val cents: Long) : Comparable<Money>
 *
 * @Queryable
 * data class Model(
 *    override val _id: Uuid,
 *    val money: Money = Money(cents = 0),
 * }
 *
 * // --- Generated properties ---
 * @InlineProperty
 * val Money_cents: Field<Money, Long> get() = ...
 *
 * @InlineProperty
 * val <T> Optic<T, Money>.cents: Optic<T, Long> get() = ...
 * // ----------------------------
 *
 *
 * // == Good examples ==
 *
 * val model = Server.models.info.findOne(
 *    condition { it.money eq Money(100L) }     // use the wrapper type if you can (99% of the time)
 * )
 *
 * val totalMoneyInCents = Server.models.info.aggregate(
 *    Aggregate.Sum,
 *    Condition.Always,
 *    property = Model.money.cents              // aggregate requires Long type to work
 * )
 *
 * // == Bad example ==
 *
 * val model = Server.models.info.findOne(
 *    condition { it.money.cents eq 100L }     // Don't do this. Do the example above.
 * )
 *
 * ```
 * */
@Target(AnnotationTarget.PROPERTY)
@RequiresOptIn("Prefer using the wrapper type if possible.", RequiresOptIn.Level.WARNING)
public annotation class InlineProperty
