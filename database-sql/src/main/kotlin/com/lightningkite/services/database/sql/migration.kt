package com.lightningkite.services.database.sql

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata

/**
 * The statements needed to bring the database up to date with [tables] **without destroying anything**:
 * create missing tables, add missing columns, create missing indices. Anything that exists in the database
 * but not in the model is left alone.
 *
 * This is what a collection runs on first use. Exposed deprecated its own equivalent
 * (`SchemaUtils.statementsRequiredToActualizeScheme`) in favour of
 * `MigrationUtils.statementsRequiredForDatabaseMigration`, but that replacement also emits `DROP COLUMN`,
 * `DROP INDEX` and `DROP SEQUENCE` for anything absent from the model. Running it automatically would mean
 * a process whose model has drifted behind the database deletes columns at startup — silently and
 * irreversibly. Dropping belongs in a reviewed migration, so automatic preparation stays additive and the
 * destructive variant is offered separately as [migrationStatements].
 *
 * Composed from Exposed's own non-deprecated building blocks, mirroring what the deprecated function did.
 * Must be called inside a transaction: each step reads the connected database's metadata.
 */
internal fun JdbcTransaction.additiveSchemaStatements(
    tables: List<Table>,
    withLogs: Boolean = true,
): List<String> {
    val (toCreate, toAlter) = tables.partition { !it.exists() }
    // Indices are covered on both paths: createStatements appends each new table's declared indices,
    // and checkMappingConsistence emits CREATE INDEX for indices missing from tables that already exist.
    val create = SchemaUtils.createStatements(tables = toCreate.toTypedArray())
    val alter = SchemaUtils.addMissingColumnsStatements(tables = toAlter.toTypedArray(), withLogs = withLogs)
    val executed = create + alter
    val consistency = SchemaUtils.checkMappingConsistence(tables = toAlter.toTypedArray(), withLogs = withLogs)
        .filter { it !in executed }
    return executed + consistency
}

/**
 * The SQL statements that would bring the schema of [collections] in the connected database fully in line
 * with their Kotlin definitions, **including destructive `DROP` statements** for columns, indices, sequences
 * and constraints that exist in the database but no longer appear in the model.
 *
 * Nothing is executed. Review the statements, then apply them with your migration tool of choice.
 *
 * Pass every collection you care about in a single call: each call reads JDBC metadata, and Exposed resolves
 * cross-table references such as foreign keys and shared indices against the whole set it is given.
 *
 * @throws IllegalArgumentException if no collections are given, or if they do not all share one database.
 */
public suspend fun migrationStatements(
    vararg collections: SqlCollection<*>,
    withLogs: Boolean = true,
): List<String> {
    require(collections.isNotEmpty()) { "Pass at least one collection to generate migration statements for." }
    val db = collections.first().db
    require(collections.all { it.db == db }) {
        "All collections must belong to one database, but got ${collections.map { it.name }} spanning several."
    }
    val tables = collections.flatMap { it.exposedTables }.toTypedArray()
    return withContext(Dispatchers.IO) {
        inTopLevelSuspendTransaction(db = db) {
            // Schema preparation execs raw DDL strings, which does not invalidate Exposed's cached view
            // of the database's tables. Left stale, a table created earlier in this process still looks
            // absent, and the migration would be computed against a database that no longer matches.
            currentDialectMetadata.resetCaches()
            MigrationUtils.statementsRequiredForDatabaseMigration(tables = tables, withLogs = withLogs)
        }
    }
}
