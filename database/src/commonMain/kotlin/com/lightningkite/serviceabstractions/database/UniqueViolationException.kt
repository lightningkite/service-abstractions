package com.lightningkite.serverabstractions.database

import com.lightningkite.serialization.*
class UniqueViolationException(
    cause: Throwable?,
    val key: String? = null,
    val collection: String? = null,
): Exception(
    key?.let { "Key $key already exists in $collection" } ?: collection?.let { "Unique violation in $collection" } ?: "Unique violation",
    cause
) {
    override val message: String
        get() = super.message!!
}