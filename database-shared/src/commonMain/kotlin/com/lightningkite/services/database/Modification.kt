package com.lightningkite.services.database

import com.lightningkite.IsRawString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable(ModificationSerializer::class)
public sealed class Modification<T> {
    public abstract operator fun invoke(on: T): T

    //    abstract fun invokeDefault(): T
    public open val isNothing: Boolean get() = false

    @Serializable
    public data object Nothing : Modification<Any?>() {
        override val isNothing: Boolean get() = true

        @Suppress("UNCHECKED_CAST")
        public inline operator fun <T> invoke(): Modification<T> = this as Modification<T>
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

    @Serializable(ModificationIfNotNullSerializer::class)
    public data class IfNotNull<T>(val modification: Modification<T>) : Modification<T?>() {
        override fun invoke(on: T?): T? = on?.let { modification(it) }
        override fun toString(): String = "?$modification"
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

    @Serializable(ModificationAppendStringSerializer::class)
    public data class AppendString(val value: String) : Modification<String>() {
        override fun invoke(on: String): String = on + value
        override fun toString(): String = " += $value"
    }

    @Serializable(ModificationAppendRawStringSerializer::class)
    public data class AppendRawString<T : IsRawString>(val value: String) : Modification<T>() {
        @Suppress("UNCHECKED_CAST")
        override fun invoke(on: T): T = on.mapRaw { it + value } as T
        override fun toString(): String = " += $value"
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

    @Serializable(ModificationListDropFirstSerializer::class)
    public class ListDropFirst<T> : Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.drop(1)
        override fun hashCode(): Int = 1

        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? ListDropFirst<T>) != null
        override fun toString(): String = ".removeFirst()"
    }

    @Serializable(ModificationListDropLastSerializer::class)
    public class ListDropLast<T> : Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.dropLast(1)
        override fun hashCode(): Int = 1

        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? ListDropLast<T>) != null
        override fun toString(): String = ".removeLast()"
    }

    @Serializable()
    @SerialName("ListPerElement")
    public data class ListPerElement<T>(val condition: Condition<T>, val modification: Modification<T>) :
        Modification<List<T>>() {
        override fun invoke(on: List<T>): List<T> = on.map { if (condition(it)) modification(it) else it }
        override fun toString(): String = ".onEach { if (it.$condition) it.$modification }"
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

    @Serializable(ModificationSetDropFirstSerializer::class)
    public class SetDropFirst<T> : Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.drop(1).toSet()
        override fun hashCode(): Int = 1

        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? SetDropFirst<T>) != null
        override fun toString(): String = ".removeFirst()"
    }

    @Serializable(ModificationSetDropLastSerializer::class)
    public class SetDropLast<T> : Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.toList().dropLast(1).toSet()
        override fun hashCode(): Int = 1

        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? SetDropLast<T>) != null
        override fun toString(): String = ".removeLast()"
    }

    @Serializable()
    @SerialName("SetPerElement")
    public data class SetPerElement<T>(val condition: Condition<T>, val modification: Modification<T>) :
        Modification<Set<T>>() {
        override fun invoke(on: Set<T>): Set<T> = on.map { if (condition(it)) modification(it) else it }.toSet()
        override fun toString(): String = ".onEach { if ($condition) $modification }"
    }

    @Serializable(ModificationCombineSerializer::class)
    public data class Combine<T>(val map: Map<String, T>) : Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on + map
        override fun toString(): String = " += $map"
    }

    @Serializable(ModificationModifyByKeySerializer::class)
    public data class ModifyByKey<T>(val map: Map<String, Modification<T>>) : Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> =
            on + map.mapValues { (on[it.key]?.let { e -> it.value(e) } ?: throw Exception()) }
    }

    @Serializable(ModificationRemoveKeysSerializer::class)
    public data class RemoveKeys<T>(val fields: Set<String>) : Modification<Map<String, T>>() {
        override fun invoke(on: Map<String, T>): Map<String, T> = on.filterKeys { it !in fields }
        override fun toString(): String = " -= $fields"
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
