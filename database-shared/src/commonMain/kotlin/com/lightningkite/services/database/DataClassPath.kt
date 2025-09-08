@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.js.JsName
import kotlin.jvm.JvmName

@Serializable(DataClassPathSerializer::class)
public abstract class DataClassPathPartial<K> {
    public abstract fun getAny(key: K): Any?
    public abstract fun setAny(key: K, any: Any?): K
    public abstract val properties: List<SerializableProperty<*, *>>
    abstract override fun hashCode(): Int
    abstract override fun toString(): String
    abstract override fun equals(other: Any?): Boolean

    public abstract val serializerAny: KSerializer<*>
}

public abstract class DataClassPath<K, V> : DataClassPathPartial<K>() {
    public abstract fun get(key: K): V?
    public abstract fun set(key: K, value: V): K

    override fun getAny(key: K): Any? = get(key)

    @Suppress("UNCHECKED_CAST")
    override fun setAny(key: K, any: Any?): K = set(key, any as V)
    public abstract fun mapCondition(condition: Condition<V>): Condition<K>
    public abstract fun mapModification(modification: Modification<V>): Modification<K>

    @JsName("prop")
    public operator fun <V2> get(prop: SerializableProperty<V, V2>): DataClassPathAccess<K, V, V2> = DataClassPathAccess(this, prop)

    override val serializerAny: KSerializer<*> get() = serializer
    public abstract val serializer: KSerializer<V>
}

public class DataClassPathSelf<K>(override val serializer: KSerializer<K>) : DataClassPath<K, K>() {
    override fun get(key: K): K? = key
    override fun set(key: K, value: K): K = value
    override fun toString(): String = "this"
    override fun hashCode(): Int = 0
    override fun equals(other: Any?): Boolean = other is DataClassPathSelf<*>
    override val properties: List<SerializableProperty<*, *>> get() = listOf()
    override fun mapCondition(condition: Condition<K>): Condition<K> = condition
    override fun mapModification(modification: Modification<K>): Modification<K> = modification
}

public data class DataClassPathAccess<K, M, V>(
    val first: DataClassPath<K, M>,
    val second: SerializableProperty<M, V>
) : DataClassPath<K, V>() {
    override fun get(key: K): V? = first.get(key)?.let {
        try {
            second.get(it)
        } catch (e: Exception) {
            e.printStackTrace()
            throw IllegalArgumentException("Could not get ${second.name} on ${it}", e)
        }
    }

    override fun set(key: K, value: V): K = first.get(key)?.let { first.set(key, second.setCopy(it, value)) } ?: key
    override fun toString(): String = if (first is DataClassPathSelf<*>) second.name else "$first.${second.name}"
    override val properties: List<SerializableProperty<*, *>> get() = first.properties + listOf(second)
    override fun mapCondition(condition: Condition<V>): Condition<K> =
        first.mapCondition(Condition.OnField(second, condition))

    override fun mapModification(modification: Modification<V>): Modification<K> =
        first.mapModification(Modification.OnField(second, modification))

    override val serializer: KSerializer<V> get() = second.serializer
}

public data class DataClassPathNotNull<K, V>(val wraps: DataClassPath<K, V?>) :
    DataClassPath<K, V>() {
    override val properties: List<SerializableProperty<*, *>>
        get() = wraps.properties

    override fun get(key: K): V? = wraps.get(key)
    override fun set(key: K, value: V): K = wraps.set(key, value)
    override fun toString(): String = "$wraps?"
    override fun mapCondition(condition: Condition<V>): Condition<K> =
        wraps.mapCondition(Condition.IfNotNull(condition))

    override fun mapModification(modification: Modification<V>): Modification<K> =
        wraps.mapModification(Modification.IfNotNull(modification))

    @Suppress("UNCHECKED_CAST")
    override val serializer: KSerializer<V> get() = wraps.serializer.nullElement()!! as KSerializer<V>
}

public data class DataClassPathList<K, V>(val wraps: DataClassPath<K, List<V>>) :
    DataClassPath<K, V>() {
    override val properties: List<SerializableProperty<*, *>>
        get() = wraps.properties

    override fun get(key: K): V? = wraps.get(key)?.firstOrNull()
    override fun set(key: K, value: V): K = wraps.set(key, listOf(value))
    override fun toString(): String = "$wraps.*"
    override fun mapCondition(condition: Condition<V>): Condition<K> =
        wraps.mapCondition(Condition.ListAllElements(condition))

    override fun mapModification(modification: Modification<V>): Modification<K> =
        wraps.mapModification(Modification.ListPerElement(Condition.Always, modification))

    @Suppress("UNCHECKED_CAST")
    override val serializer: KSerializer<V> get() = wraps.serializer.listElement()!! as KSerializer<V>
}

public data class DataClassPathSet<K, V>(val wraps: DataClassPath<K, Set<V>>) :
    DataClassPath<K, V>() {
    override val properties: List<SerializableProperty<*, *>>
        get() = wraps.properties

    override fun get(key: K): V? = wraps.get(key)?.firstOrNull()
    override fun set(key: K, value: V): K = wraps.set(key, setOf(value))
    override fun toString(): String = "$wraps.*"
    override fun mapCondition(condition: Condition<V>): Condition<K> =
        wraps.mapCondition(Condition.SetAllElements(condition))

    override fun mapModification(modification: Modification<V>): Modification<K> =
        wraps.mapModification(Modification.SetPerElement(Condition.Always, modification))

    @Suppress("UNCHECKED_CAST")
    override val serializer: KSerializer<V> get() = wraps.serializer.listElement()!! as KSerializer<V>
}

public val <K, V> DataClassPath<K, V?>.notNull: DataClassPathNotNull<K, V>
    get() = DataClassPathNotNull(
        this
    )

@get:JvmName("getListElements")
public val <K, V> DataClassPath<K, List<V>>.elements: DataClassPathList<K, V>
    get() = DataClassPathList(this)

@get:JvmName("getSetElements")
public val <K, V> DataClassPath<K, Set<V>>.elements: DataClassPathSet<K, V>
    get() = DataClassPathSet(this)
