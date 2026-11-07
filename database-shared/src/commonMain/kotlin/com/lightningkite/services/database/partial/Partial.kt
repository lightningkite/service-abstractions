@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*

import kotlinx.serialization.*

// TODO : Optimize for fully complete subobjects
// TODO : Better handle nullable objects for mod translation
@Serializable(PartialSerializer::class)
public data class Partial<T>(
    public val parts: MutableMap<Field<T, *>, Any?> = mutableMapOf(),
) {

    /** Returns the model this partial describes, or `null` if it doesn't include every field. */
    public fun total(serializer: KSerializer<T>): T? = if (isComplete(serializer)) toModel(serializer) else null

    public fun <S> perPath(soFar: Optic<S, T>, action: (OpticWithValue<S, *>) -> Unit) {
        for (part in parts) {

            @Suppress("UNCHECKED_CAST")
            val p = soFar + part.key as Field<T, Any?>
            if (part.value is Partial<*> && part.key.serializer.let {
                    it.nullElement() ?: it
                } !is PartialSerializer<*>) {

                @Suppress("UNCHECKED_CAST")
                val partial = (part.value as Partial<Any?>)
                val ser = part.key.serializer.let { it.nullElement() ?: it }

                @Suppress("UNCHECKED_CAST")
                partial.total(ser as KSerializer<Any?>)?.let {
                    action(OpticWithValue(p, it))
                } ?: partial.perPath(p, action)
            } else {
                action(OpticWithValue(p, part.value))
            }
        }
    }
}

/**
 * Returns `true` if this partial includes the target of [optic].
 *
 * A `null` along the way counts as included, since then [optic] has no target.
 */
public operator fun <T> Partial<T>.contains(optic: AnyOptic<T>): Boolean {
    var entry: Any? = this
    for (field in optic.fields) {
        if (entry !is Partial<*>) return true
        if (field !in entry.parts) return false
        entry = entry.parts[field]
    }
    return entry !is Partial<*> || entry.isComplete(optic.coerceToOptic().serializer)
}

/**
 * Returns the target of [optic] in this partial, or `null` if this partial doesn't include it or [optic] has no target.
 */
public inline operator fun <reified T, V> Partial<T>.get(optic: Optic<T, V>): V? =
    if (optic in this) optic.get(toModel(serializer())) else null

/**
 * Returns the target of [lens] in this partial.
 *
 * @throws NoSuchElementException if this partial doesn't include [lens].
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T, V> Partial<T>.getValue(lens: Lens<T, V>): V = getValue(lens as Optic<T, V>) as V

/**
 * Returns the target of [optic] in this partial, or `null` if [optic] has no target.
 *
 * @throws NoSuchElementException if this partial doesn't include [optic].
 */
public inline fun <reified T, V> Partial<T>.getValue(optic: Optic<T, V>): V? {
    if (optic !in this) throw NoSuchElementException("This partial doesn't include $optic.")
    return optic.get(toModel(serializer()))
}

/** Returns a model with this partial's values, and defaults for the parts it doesn't include. */
@PublishedApi
@Suppress("UNCHECKED_CAST")
internal fun <T> Partial<T>.toModel(serializer: KSerializer<T>): T =
    parts.entries.fold(serializer.default()) { model, (field, entry) ->
        field as Field<T, Any?>
        val value =
            if (entry is Partial<*> && field.nestedFields != null)
                (entry as Partial<Any?>).toModel((field.serializer.nullElement() ?: field.serializer) as KSerializer<Any?>)
            else entry
        field.set(model, value)
    }

@Suppress("UNCHECKED_CAST")
private fun Partial<*>.isComplete(serializer: KSerializer<*>): Boolean {
    val parts = parts as Map<Field<*, *>, Any?>
    return (serializer.nullElement() ?: serializer).fields.orEmpty().all { field ->
        field in parts && parts[field].let { it !is Partial<*> || it.isComplete(field.serializer) }
    }
}

public data class OpticWithValue<A, V>(val path: Optic<A, V>, val value: V) {
    public fun set(a: A): A = path.set(a, value)
}

/**
 * Returns [value] in the form a [Partial] stores it under this property: a composite (non-inline,
 * multi-field) value is converted to a nested [Partial], while leaf and inline values are returned
 * as-is. This upholds the invariant that a Partial never holds a complete composite object as a
 * field entry, only another Partial.
 */
@Suppress("UNCHECKED_CAST")
public fun <A> Field<*, A>.toPartialEntry(value: A): Any? =
    nestedFields?.let { nestedProps -> value?.let { partialOf(it, nestedProps) } } ?: value

// Claude: the fields a Partial splits this field's value into, or null when it stores the value whole.
@Suppress("UNCHECKED_CAST")
internal val Field<*, *>.nestedFields: Array<Field<Any?, *>>?
    get() = serializer
        .takeIf { !it.descriptor.isInline }
        ?.let { (it.nullElement() ?: it) as KSerializer<Any?> }
        ?.fields

@Suppress("UNCHECKED_CAST")
public class PartialBuilder<T>(public val partial: Partial<T> = Partial()) {
    public infix fun <A> Optic<T, A>.assign(value: A) {
        var target: Partial<Any> = partial as Partial<Any>
        val props = fields
        for (prop in props.dropLast(1)) {
            target = target.parts.getOrPut(prop as Field<Any, Any>) { Partial<Any>() } as Partial<Any>
        }
        val last = props.last() as Field<Any, A>
        target.parts[last] = last.toPartialEntry(value)
    }

    public infix fun <A> Optic<T, A>.assign(value: Partial<A>) {
        var target: Partial<Any> = partial as Partial<Any>
        val props = fields
        for (prop in props.dropLast(1)) {
            target = target.parts.getOrPut(prop as Field<Any, Any>) { Partial<Any>() } as Partial<Any>
        }
        target.parts[props.last() as Field<Any, A>] = value
    }
}

public inline fun <T> buildPartial(serializer: KSerializer<T>, builder: PartialBuilder<T>.(Lens<T, T>) -> Unit): Partial<T> =
    PartialBuilder<T>().apply {
        builder(IdentityLens(serializer))
    }.partial

public inline fun <reified T> buildPartial(builder: PartialBuilder<T>.(Lens<T, T>) -> Unit): Partial<T> =
    buildPartial(serializer<T>(), builder)

public fun <T> partialOf(item: T, paths: Iterable<AnyOptic<T>>): Partial<T> = Partial<T>().apply {
    paths.forEach { it.setMap(item, this) }
}

public fun <T> partialOf(item: T, paths: Array<out AnyOptic<T>>): Partial<T> = Partial<T>().apply {
    paths.forEach { it.setMap(item, this) }
}

public inline fun <reified T> partialOfDifference(old: T?, new: T?): Partial<T>? = partialOfDifference(serializer(), old = old, new = new)
public fun <T> partialOfDifference(serializer: KSerializer<T>, old: T?, new: T?): Partial<T>? {
    if (old == new) return Partial()
    // fields is only present on the non-null generated serializer, so unwrap any
    // nullable wrapper before reading it — otherwise nullable composite fields would NPE here.
    @Suppress("UNCHECKED_CAST")
    val unwrapped = (serializer.nullElement() ?: serializer) as KSerializer<T>
    if (old == null) return partialOf(new!!, unwrapped.fields!!)
    if (new == null) return null
    val p = Partial<T>()
    unwrapped.fields!!.forEach { property ->
        @Suppress("UNCHECKED_CAST")
        property as Field<T, Any?>
        val oldValue = property.get(old)
        val newValue = property.get(new)
        if (oldValue != newValue) {
            p.parts[property] =
                if (property.nestedFields != null) partialOfDifference(property.serializer, oldValue, newValue)
                else property.toPartialEntry(newValue)
        }
    }
    return p
}
