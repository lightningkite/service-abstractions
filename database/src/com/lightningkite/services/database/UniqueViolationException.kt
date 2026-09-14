package com.lightningkite.services.database

public class UniqueViolationException(
    cause: Throwable?,
    public val key: String? = null,
    public val table: String? = null,
) : Exception(
    key?.let { "Key $key already exists in $table" } ?: table?.let { "Unique violation in $table" }
    ?: "Unique violation",
    cause
) {
    override val message: String
        get() = super.message!!
}