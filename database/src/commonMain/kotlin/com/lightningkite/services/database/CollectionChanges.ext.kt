package com.lightningkite.services.database


// This will not convert well. Manually add the type argument to the return EntryChange on the swift side. "EntryChange<B>"
internal inline fun <T, B> CollectionChanges<T>.map(mapper: (T) -> B): CollectionChanges<B> {
    return CollectionChanges<B>(changes = changes.map { it.map(mapper) })
}