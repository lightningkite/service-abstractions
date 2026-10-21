@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.data.*
import com.lightningkite.services.data.Length.Companion.miles
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer
import kotlin.js.JsName
import kotlin.jvm.JvmName

/** Returns an [IdentityLens] on [T], the starting point for building a path with [Optic.plus]. */
public fun <T> path(serializer: KSerializer<T>): IdentityLens<T> = IdentityLens(serializer)
/** Returns an [IdentityLens] on [T], the starting point for building a path with [Optic.plus]. */
public inline fun <reified T> path(): IdentityLens<T> = IdentityLens(serializerOrContextual<T>())

public inline fun <reified T> Condition(setup: (Lens<T, T>) -> Condition<T>): Condition<T> =
    path<T>().let(setup)

public fun Condition(boolean: Boolean): Condition<Any?> = if (boolean) Condition.Always else Condition.Never

public fun <K> Optic<K, *>.always(): Condition<K> = Condition.Always
public fun <K> Optic<K, *>.never(): Condition<K> = Condition.Never

public infix fun <K, T> Optic<K, T>.eq(value: T): Condition<K> = mapCondition(Condition.Equal(value))

public infix fun <K, T : Any> Optic<K, T>.eqNn(value: T?): Condition<K> =
    if (value == null) Condition.Never else mapCondition(Condition.Equal(value))

public infix fun <K, T> Optic<K, T>.neq(value: T): Condition<K> = mapCondition(Condition.NotEqual(value))

@JsName("xOpticNotInSet")
public infix fun <K, T> Optic<K, T>.notInside(values: Set<T>): Condition<K> =
    mapCondition(Condition.NotInside(values))

public infix fun <K, T> Optic<K, T>.notInside(values: List<T>): Condition<K> =
    mapCondition(Condition.NotInside(values.toSet()))

@JsName("xOpticNotInVararg")
public fun <K, T> Optic<K, T>.notInside(vararg values: T): Condition<K> =
    mapCondition(Condition.NotInside(values.toSet()))

public infix fun <K, T : Comparable<T>> Optic<K, T>.gt(value: T): Condition<K> =
    mapCondition(Condition.GreaterThan(value))

public infix fun <K, T : Comparable<T>> Optic<K, T>.lt(value: T): Condition<K> =
    mapCondition(Condition.LessThan(value))

public infix fun <K, T : Comparable<T>> Optic<K, T>.gte(value: T): Condition<K> =
    mapCondition(Condition.GreaterThanOrEqual(value))

public infix fun <K, T : Comparable<T>> Optic<K, T>.lte(value: T): Condition<K> =
    mapCondition(Condition.LessThanOrEqual(value))

public infix fun <K> Optic<K, Int>.allClear(mask: Int): Condition<K> =
    mapCondition(Condition.IntBitsClear(mask))

public infix fun <K> Optic<K, Int>.allSet(mask: Int): Condition<K> = mapCondition(Condition.IntBitsSet(mask))
public infix fun <K> Optic<K, Int>.anyClear(mask: Int): Condition<K> =
    mapCondition(Condition.IntBitsAnyClear(mask))

public infix fun <K> Optic<K, Int>.anySet(mask: Int): Condition<K> = mapCondition(Condition.IntBitsAnySet(mask))

public infix fun <K> Optic<K, String>.contains(value: String): Condition<K> =
    mapCondition(Condition.StringContains(value, ignoreCase = true))

public fun <K> Optic<K, GeoCoordinate>.distanceBetween(
    value: GeoCoordinate,
    greaterThan: Length = 0.0.miles,
    lessThan: Length = 100_000.0.miles,
): Condition<K> = mapCondition(Condition.GeoDistance(value, greaterThan, lessThan))

@JsName("xOpticContainsCased")
public fun <K> Optic<K, String>.contains(value: String, ignoreCase: Boolean): Condition<K> =
    mapCondition(Condition.StringContains(value, ignoreCase = ignoreCase))

@JsName("xOpticCollectionAll")
@JvmName("collectionAll")
public inline infix fun <K, C: Collection<T>, reified T> Optic<K, C>.all(condition: (Lens<T, T>) -> Condition<T>): Condition<K> =
    mapCondition(Condition.CollectionAllElements(path<T>().let(condition)))

@JsName("xOpticCollectionAny")
@JvmName("collectionAny")
public inline infix fun <K, C: Collection<T>, reified T> Optic<K, C>.any(condition: (Lens<T, T>) -> Condition<T>): Condition<K> =
    mapCondition(Condition.CollectionAnyElements(path<T>().let(condition)))

public inline infix fun <K, reified T> Optic<K, T>.Condition(make: (Lens<T, T>) -> Condition<T>): Condition<K> =
    mapCondition(make(path<T>()))

@JsName("xOpticInsideSet")
public infix fun <K, T> Optic<K, T>.inside(values: Set<T>): Condition<K> =
    mapCondition(Condition.Inside(values))

public infix fun <K, T> Optic<K, T>.inside(values: List<T>): Condition<K> =
    mapCondition(Condition.Inside(values.toSet()))

@JsName("xOpticInsideVararg")
public fun <K, T> Optic<K, T>.inside(vararg values: T): Condition<K> =
    mapCondition(Condition.Inside(values.toSet()))

public fun <T, V> OpticWithValue<T, V>.eq(): Condition<T> = path.mapCondition(Condition.Equal(value))
public inline fun <reified T> Partial<T>.toCondition(): Condition<T> = toCondition(serializer())
public fun <T> Partial<T>.toCondition(serializer: KSerializer<T>): Condition<T> {
    val out = ArrayList<Condition<T>>()
    perPath(IdentityLens(serializer)) { out += it.eq() }
    return Condition.And(out)
}


private val f = StringArrayFormat(EmptySerializersModule())
private fun <T : Enum<T>> KSerializer<T>.entries(): List<T> {
    return (0..<descriptor.elementsCount).map {
        f.decodeFromString(this, descriptor.getElementName(it))
    }
}

public infix fun <K, T : Enum<T>> Optic<K, T>.gt(value: T): Condition<K> =
    mapCondition(Condition.Inside(serializer.entries().filter { it > value }))

public infix fun <K, T : Enum<T>> Optic<K, T>.lt(value: T): Condition<K> =
    mapCondition(Condition.Inside(serializer.entries().filter { it < value }))

public infix fun <K, T : Enum<T>> Optic<K, T>.gte(value: T): Condition<K> =
    mapCondition(Condition.Inside(serializer.entries().filter { it >= value }))

public infix fun <K, T : Enum<T>> Optic<K, T>.lte(value: T): Condition<K> =
    mapCondition(Condition.Inside(serializer.entries().filter { it <= value }))

public infix fun <K, T, V : T> Optic<K, T>.isType(type: KSerializer<V>): Condition<K> =
    mapCondition(Condition.IfIsType(type, Condition.Always))

public inline fun <K, T, reified V : T> Optic<K, T>.isType(): Condition<K> = isType(serializer<V>())