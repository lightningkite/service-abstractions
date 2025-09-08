package com.lightningkite.services.database

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.jvm.JvmName

// TODO : Optimize for fully complete subobjects
// TODO : Better handle nullable objects for mod translation
@Serializable(PartialSerializer::class)
public data class Partial<T> (
    public val parts: MutableMap<SerializableProperty<T, *>, Any?> = mutableMapOf()
) {

    public fun total(serializer: KSerializer<T>): T? =
        if (parts.keys.containsAll(serializer.serializableProperties!!.asList())) {
            var out = serializer.default()
            perPath(DataClassPathSelf(serializer)) {
                out = it.set(out)
            }
            out
        } else null

    public fun <S> perPath(soFar: DataClassPath<S, T>, action: (DataClassPathWithValue<S, *>) -> Unit) {
        for (part in parts) {

            @Suppress("UNCHECKED_CAST")
            val p = DataClassPathAccess(soFar, part.key as SerializableProperty<T, Any?>)
            if (part.value is Partial<*> && part.key.serializer.let {
                    it.nullElement() ?: it
                } !is PartialSerializer<*>) {

                @Suppress("UNCHECKED_CAST")
                val partial = (part.value as Partial<Any?>)
                val ser = part.key.serializer.let { it.nullElement() ?: it }

                @Suppress("UNCHECKED_CAST")
                partial.total(ser as KSerializer<Any?>)?.let {
                    action(DataClassPathWithValue(p, it))
                } ?: partial.perPath(p, action)
            } else {
                action(DataClassPathWithValue(p, part.value))
            }
        }
    }
}

public data class DataClassPathWithValue<A, V>(val path: DataClassPath<A, V>, val value: V) {
    public fun set(a: A): A = path.set(a, value)
}

@Suppress("UNCHECKED_CAST")
public class PartialBuilder<T>(public val partial: Partial<T> = Partial()) {
    public inline infix fun <A> DataClassPath<T, A>.assign(value: A) {
        var target: Partial<Any> = partial as Partial<Any>
        val props = properties
        for (prop in props.dropLast(1)) {
            target = target.parts.getOrPut(prop as SerializableProperty<Any, Any>) { Partial<Any>() } as Partial<Any>
        }
        target.parts[props.last() as SerializableProperty<Any, A>] = value
    }

    public inline infix fun <A> DataClassPath<T, A>.assign(value: Partial<A>) {
        var target: Partial<Any> = partial as Partial<Any>
        val props = properties
        for (prop in props.dropLast(1)) {
            target = target.parts.getOrPut(prop as SerializableProperty<Any, Any>) { Partial<Any>() } as Partial<Any>
        }
        target.parts[props.last() as SerializableProperty<Any, A>] = value
    }
}

public inline fun <reified T> partialOf(builder: PartialBuilder<T>.(DataClassPathSelf<T>) -> Unit): Partial<T> =
    PartialBuilder<T>().apply {
        builder(
            DataClassPathSelf(
                serializer()
            )
        )
    }.partial


public fun <T> partialOf(item: T, properties: Array<SerializableProperty<T, *>>): Partial<T> = Partial<T>().apply {
    properties.forEach { parts[it] = it.get(item) }
}

public fun <T> partialOf(item: T, properties: Iterable<SerializableProperty<T, *>>): Partial<T> = Partial<T>().apply {
    properties.forEach { parts[it] = it.get(item) }
}

@JvmName("partialOfPaths")
public fun <T> partialOf(item: T, paths: Iterable<DataClassPathPartial<T>>): Partial<T> = Partial<T>().apply {
    paths.forEach { it.setMap(item, this) }
}

@JvmName("partialOfPaths")
public fun <T> partialOf(item: T, paths: Array<DataClassPathPartial<T>>): Partial<T> = Partial<T>().apply {
    paths.forEach { it.setMap(item, this) }
}