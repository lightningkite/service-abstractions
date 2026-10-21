package com.lightningkite.services.database

import com.lightningkite.services.database.path.*
import kotlinx.serialization.KSerializer

@Deprecated("Renamed to Optic", ReplaceWith("Optic<K, V>", "com.lightningkite.services.database.path.Optic"))
public typealias DataClassPath<K, V> = Optic<K, V>

@Deprecated("Renamed to OpticOn", ReplaceWith("OpticOn<K>", "com.lightningkite.services.database.path.OpticOn"))
public typealias DataClassPathPartial<K> = OpticOn<K>

@Deprecated("Renamed to IdentityLens", ReplaceWith("IdentityLens<K>", "com.lightningkite.services.database.path.IdentityLens"))
public typealias DataClassPathSelf<K> = IdentityLens<K>

@Deprecated("Renamed to ComposedOptic", ReplaceWith("ComposedOptic<K, M, V>", "com.lightningkite.services.database.path.ComposedOptic"))
public typealias DataClassPathAccess<K, M, V> = ComposedOptic<K, M, V>

@Deprecated("Compose with plus instead", ReplaceWith("first + second", "com.lightningkite.services.database.path.plus"))
public fun <K, M, V> DataClassPathAccess(first: Optic<K, M>, second: Field<M, V>): Optic<K, V> = first + second

@Deprecated("Renamed to NotNullOptic", ReplaceWith("NotNullOptic<K, V>", "com.lightningkite.services.database.path.NotNullOptic"))
public typealias DataClassPathNotNull<K, V> = NotNullOptic<K, V>

@Deprecated("Renamed to OpticOfType", ReplaceWith("OpticOfType<K, T, V>", "com.lightningkite.services.database.path.OpticOfType"))
public typealias DataClassPathOfType<K, T, V> = OpticOfType<K, T, V>

@Deprecated("Renamed to ListElementsOptic", ReplaceWith("ListElementsOptic<K, V>", "com.lightningkite.services.database.path.ListElementsOptic"))
public typealias DataClassPathList<K, V> = ListElementsOptic<K, V>

@Deprecated("Renamed to SetElementsOptic", ReplaceWith("SetElementsOptic<K, V>", "com.lightningkite.services.database.path.SetElementsOptic"))
public typealias DataClassPathSet<K, V> = SetElementsOptic<K, V>

@Deprecated("Renamed to OpticOnSerializer", ReplaceWith("OpticOnSerializer<T>", "com.lightningkite.services.database.path.OpticOnSerializer"))
public typealias DataClassPathSerializer<T> = OpticOnSerializer<T>

@Deprecated("Renamed to OpticWithValue", ReplaceWith("OpticWithValue<A, V>"))
public typealias DataClassPathWithValue<A, V> = OpticWithValue<A, V>

@Deprecated("Renamed to Field", ReplaceWith("Field<A, B>", "com.lightningkite.services.database.path.Field"))
public typealias SerializableProperty<A, B> = Field<A, B>

@Deprecated("Renamed to fields", ReplaceWith("fields", "com.lightningkite.services.database.path.fields"))
public val <T> KSerializer<T>.serializableProperties: Array<Field<T, *>>? get() = fields

@Deprecated("Renamed to fields", ReplaceWith("fields"))
public val OpticOn<*>.properties: List<Field<*, *>> get() = fields

@Deprecated("Renamed to isInline", ReplaceWith("isInline"))
public val Field<*, *>.inline: Boolean get() = isInline

@Deprecated("Use get instead", ReplaceWith("get(key)"))
public fun <K> OpticOn<K>.getAny(key: K): Any? = get(key)

@Suppress("UNCHECKED_CAST")
@Deprecated("Use set on an Optic instead", ReplaceWith("(coerceToOptic() as Optic<K, Any?>).set(key, any)", "com.lightningkite.services.database.path.coerceToOptic"))
public fun <K> OpticOn<K>.setAny(key: K, any: Any?): K = (coerceToOptic() as Optic<K, Any?>).set(key, any)

@Deprecated("Use coerceToOptic().serializer instead", ReplaceWith("coerceToOptic().serializer", "com.lightningkite.services.database.path.coerceToOptic"))
public val OpticOn<*>.serializerAny: KSerializer<*> get() = coerceToOptic().serializer

@Deprecated("Compose with plus instead", ReplaceWith("this + prop", "com.lightningkite.services.database.path.plus"))
public operator fun <K, V, V2> Optic<K, V>.get(prop: Field<V, V2>): Optic<K, V2> = this + prop

@Deprecated("Renamed to forOptic", ReplaceWith("forOptic(path)"))
public fun <K, V> Modification<K>.forDataClassPath(path: Optic<K, V>): Modification<V>? = forOptic(path)

@Deprecated("Renamed to valueSetForOptic", ReplaceWith("valueSetForOptic(path)"))
public fun <K, V> Modification<K>.valueSetForDataClassPath(path: Optic<K, V>): V? = valueSetForOptic(path)

