@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.path.*

import kotlinx.serialization.*

// TODO : Optimize for fully complete subobjects
// TODO : Better handle nullable objects for mod translation
@Serializable(PartialSerializer::class)
public data class Partial<T>(
    public val parts: MutableMap<Field<T, *>, Any?> = mutableMapOf(),
) {

    public fun total(serializer: KSerializer<T>): T? =
        if (parts.keys.containsAll(serializer.fields!!.asList())) {
            var out = serializer.default()
            perPath(IdentityLens(serializer)) {
                out = it.set(out)
            }
            out
        } else null

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
    serializer
        .takeIf { !it.descriptor.isInline }
        ?.let { (it.nullElement() ?: it) as KSerializer<Any?> }
        ?.fields
        ?.let { nestedProps -> value?.let { partialOf(it, nestedProps) } }
        ?: value

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

public inline fun <reified T> partialOf(builder: PartialBuilder<T>.(Lens<T, T>) -> Unit): Partial<T> =
    PartialBuilder<T>().apply {
        builder(
            IdentityLens(
                serializer()
            )
        )
    }.partial


public fun <T> partialOf(item: T, paths: Iterable<OpticOn<T>>): Partial<T> = Partial<T>().apply {
    paths.forEach { it.setMap(item, this) }
}

public fun <T> partialOf(item: T, paths: Array<out OpticOn<T>>): Partial<T> = Partial<T>().apply {
    paths.forEach { it.setMap(item, this) }
}

public inline fun <reified T> partialOfDifference(old: T?, new: T?): Partial<T>? = partialOfDifference(serializer(), old = old, new = new)
public fun <T> partialOfDifference(serializer: KSerializer<T>, old: T?, new: T?): Partial<T>? {
    if (old == new) return Partial()
    // serializableProperties is only present on the non-null generated serializer, so unwrap any
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
            @Suppress("UNCHECKED_CAST")
            property.serializer
                .takeIf { !it.descriptor.isInline }
                ?.let { (it.nullElement() ?: it) as KSerializer<Any?> }
                ?.fields
                ?.let { _ -> p.parts[property] = partialOfDifference(property.serializer, oldValue, newValue) }
                ?: run { p.parts[property] = property.toPartialEntry(newValue) }
        }
    }
    return p
}
