package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.UniqueViolationException
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.sql.SQLException

/** SQLSTATE class 23505: `unique_violation`. The only code Postgres uses for a duplicate key. */
private const val UNIQUE_VIOLATION = "23505"

/**
 * Translates a Postgres duplicate-key failure into the abstraction's [UniqueViolationException],
 * or returns null if [this] is some other database error.
 *
 * Callers otherwise see a driver-specific `PSQLException` and would have to match on SQLSTATE
 * themselves to tell "this key is taken" apart from a real failure — which defeats the point of
 * having a portable abstraction. MongoDB already translates its duplicate-key errors this way.
 *
 * The batch case matters: a failing `insertMany` surfaces as a `BatchUpdateException` whose SQLSTATE
 * is unset, carrying the real cause in its chain, so the whole chain is searched.
 */
internal fun ExposedSQLException.asUniqueViolationOrNull(table: String): UniqueViolationException? {
    val violation = generateSequence(this as Throwable) { it.cause }
        .filterIsInstance<SQLException>()
        .flatMap { generateSequence(it) { next -> next.nextException } }
        .firstOrNull { it.sqlState == UNIQUE_VIOLATION }
        ?: return null
    return UniqueViolationException(
        cause = this,
        key = violation.constraintName(),
        table = table,
    )
}

/**
 * The violated constraint's name, pulled from the driver's message.
 *
 * `PSQLException` exposes this through `getServerErrorMessage().getConstraint()`, but reaching it
 * would mean depending on the concrete driver type here. The message format is stable and the name
 * is only ever used for diagnostics, so a null result costs nothing.
 */
private fun SQLException.constraintName(): String? =
    Regex("""violates unique constraint "([^"]+)"""").find(message.orEmpty())?.groupValues?.get(1)
