package com.lightningkite.services.database

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KType


/**
 * Returns a FieldCollection of type T that will access and manipulate data from a collection/table in the underlying database system.
 */
@Deprecated("collection has been replaced with table", ReplaceWith("table"))
public fun <T : Any> Database.collection(serializer: KSerializer<T>, name: String): Table<T> = table(serializer, name)

@Suppress("UNCHECKED_CAST")
@Deprecated("collection has been replaced with table", ReplaceWith("table"))
public fun <T : Any> Database.collection(type: KType, name: String): Table<T> =
    table(context.internalSerializersModule.serializer(type) as KSerializer<T>, name)


/**
 * A Helper function for getting a collection from a database using generics.
 * This can make collection calls much cleaner and less wordy when the types can be inferred.
 */
@Deprecated("collection has been replaced with table", ReplaceWith("table"))
public inline fun <reified T : Any> Database.collection(name: String = T::class.simpleName!!): Table<T> {
    return table(context.internalSerializersModule.serializer<T>(), name)
}