package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*
import kotlinx.serialization.KSerializer

@Deprecated("Renamed to Optic", ReplaceWith("Optic<K, V>", "com.lightningkite.services.database.optics.Optic"))
public typealias DataClassPath<K, V> = Optic<K, V>

@Deprecated("Renamed to OpticOn", ReplaceWith("OpticOn<K>", "com.lightningkite.services.database.optics.OpticOn"))
public typealias DataClassPathPartial<K> = AnyOptic<K>

@Deprecated("Renamed to IdentityLens", ReplaceWith("IdentityLens<K>", "com.lightningkite.services.database.optics.IdentityLens"))
public typealias DataClassPathSelf<K> = IdentityLens<K>

@Deprecated("Renamed to ComposedOptic", ReplaceWith("ComposedOptic<K, M, V>", "com.lightningkite.services.database.optics.ComposedOptic"))
public typealias DataClassPathAccess<K, M, V> = ComposedOptic<K, M, V>

@Deprecated("Compose with plus instead", ReplaceWith("first + second", "com.lightningkite.services.database.optics.plus"))
public fun <K, M, V> DataClassPathAccess(first: Optic<K, M>, second: Field<M, V>): Optic<K, V> = first + second

@Deprecated("Renamed to NotNullOptic", ReplaceWith("NotNullOptic<K, V>", "com.lightningkite.services.database.optics.NotNullOptic"))
public typealias DataClassPathNotNull<K, V> = NotNullOptic<K, V>

@Deprecated("Renamed to OpticOfType", ReplaceWith("OpticOfType<K, T, V>", "com.lightningkite.services.database.optics.OpticOfType"))
public typealias DataClassPathOfType<K, T, V> = OpticOfType<K, T, V>

@Deprecated("Renamed to ListElementsOptic", ReplaceWith("ListElementsOptic<K, V>", "com.lightningkite.services.database.optics.ListElementsOptic"))
public typealias DataClassPathList<K, V> = ListElementsOptic<K, V>

@Deprecated("Renamed to SetElementsOptic", ReplaceWith("SetElementsOptic<K, V>", "com.lightningkite.services.database.optics.SetElementsOptic"))
public typealias DataClassPathSet<K, V> = SetElementsOptic<K, V>

@Deprecated("Renamed to OpticOnSerializer", ReplaceWith("OpticOnSerializer<T>", "com.lightningkite.services.database.optics.OpticOnSerializer"))
public typealias DataClassPathSerializer<T> = AnyOpticSerializer<T>

@Deprecated("Renamed to OpticWithValue", ReplaceWith("OpticWithValue<A, V>"))
public typealias DataClassPathWithValue<A, V> = OpticWithValue<A, V>

@Deprecated("Renamed to Field", ReplaceWith("Field<A, B>", "com.lightningkite.services.database.optics.Field"))
public typealias SerializableProperty<A, B> = Field<A, B>

@Deprecated("Renamed to fields", ReplaceWith("fields", "com.lightningkite.services.database.optics.fields"))
public val <T> KSerializer<T>.serializableProperties: Array<Field<T, *>>? get() = fields

@Deprecated("Renamed to fields", ReplaceWith("fields"))
public val AnyOptic<*>.properties: List<Field<*, *>> get() = fields

@Deprecated("Renamed to isInline", ReplaceWith("isInline"))
public val Field<*, *>.inline: Boolean get() = isInline

@Deprecated("Use get instead", ReplaceWith("get(key)"))
public fun <K> AnyOptic<K>.getAny(key: K): Any? = get(key)

@Suppress("UNCHECKED_CAST")
@Deprecated(
    "Use set on an Optic instead",
    ReplaceWith(
        "(coerceToOptic() as Optic<K, Any?>).set(key, any)",
        "com.lightningkite.services.database.optics.coerceToOptic"
    ),
    DeprecationLevel.ERROR
)
public fun <K> AnyOptic<K>.setAny(key: K, any: Any?): K = (coerceToOptic() as Optic<K, Any?>).set(key, any)

@Deprecated("Use coerceToOptic().serializer instead", ReplaceWith("coerceToOptic().serializer", "com.lightningkite.services.database.optics.coerceToOptic"))
public val AnyOptic<*>.serializerAny: KSerializer<*> get() = coerceToOptic().serializer

@Deprecated("Compose with plus instead", ReplaceWith("this + prop", "com.lightningkite.services.database.optics.*"))
public operator fun <K, V, V2> Optic<K, V>.get(prop: Field<V, V2>): Optic<K, V2> = this + prop

@Deprecated("Renamed to forOptic", ReplaceWith("forOptic(path)"))
public fun <K, V> Modification<K>.forDataClassPath(path: Optic<K, V>): Modification<V>? = forOptic(path)

@Deprecated("Renamed to valueSetForOptic", ReplaceWith("valueSetForOptic(path)"))
public fun <K, V> Modification<K>.valueSetForDataClassPath(path: Optic<K, V>): V? = valueSetForOptic(path)

@Deprecated("Renamed to Condition to match kotlin naming conventions", ReplaceWith("Condition(setup)", "com.lightningkite.services.database.Condition"))
public inline fun <reified T> condition(setup: (Lens<T, T>) -> Condition<T>): Condition<T> =
    Condition(setup)

@Deprecated("Renamed to Condition to match kotlin naming conventions", ReplaceWith("Condition(setup)", "com.lightningkite.services.database.Condition"))
public fun condition(boolean: Boolean): Condition<Any?> = Condition(boolean)

@Deprecated("Renamed to Modification to match kotlin naming conventions", ReplaceWith("Modification(setup)", "com.lightningkite.services.database.Modification"))
public inline fun <reified T> modification(setup: ModificationBuilder<T>.(Lens<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path())
    }.build()
}

@Deprecated("Renamed to Modification to match kotlin naming conventions", ReplaceWith("Modification(path, setup)", "com.lightningkite.services.database.Modification"))
public inline fun <T> modification(
    path: Lens<T, T>,
    setup: ModificationBuilder<T>.(Lens<T, T>) -> Unit,
): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path)
    }.build()
}
