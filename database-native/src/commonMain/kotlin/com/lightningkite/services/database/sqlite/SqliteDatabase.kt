package com.lightningkite.services.database.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import com.lightningkite.services.ConcurrentMutableMap
import com.lightningkite.services.SettingContext
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.DatabaseTableDefinition
import com.lightningkite.services.database.Table
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A [Database] kept in a local SQLite file: on Android, iOS, macOS, and the JVM, and in the browser's
 * origin private file system on the web.  Each model gets a table with a column per field and a child
 * table per collection; conditions, modifications, sorting, and aggregates run as SQL.
 *
 * URL: `native://<location>`.  On the JVM the location is a file path.  On iOS and macOS a relative
 * path is inside Application Support.  On Android it must be absolute, e.g.
 * `context.getDatabasePath("app.db").path`.  On the web it is a file name without slashes; only one
 * tab can have the database open at a time.
 */
public class SqliteDatabase(
    override val name: String,
    public val location: String,
    override val context: SettingContext,
) : Database {
    public companion object {
        init {
            Database.Settings.register("native") { name, url, context ->
                SqliteDatabase(name, url.substringAfter("://"), context)
            }
        }
    }

    private val tables = ConcurrentMutableMap<DatabaseTableDefinition<*>, SqliteTable<*>>()

    // One connection, used by one operation at a time: connections aren't thread-safe, and the web's
    // file system only allows one.  That also makes every operation atomic.
    private val lock = Mutex()
    private var connection: SQLiteConnection? = null
    private val prepared = HashSet<String>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> table(tableDef: DatabaseTableDefinition<T>): SqliteTable<T> =
        tables.computeIfAbsent(tableDef) { SqliteTable(this, tableDef) } as SqliteTable<T>

    override suspend fun <T : Any> prepare(tableDef: DatabaseTableDefinition<T>): Table<T> =
        table(tableDef).also { transaction(it.schema) { } }

    override suspend fun disconnect() {
        lock.withLock {
            connection?.close()
            connection = null
            prepared.clear()
        }
    }

    /** Runs [block] in a transaction, after creating or extending [schema]'s tables if this is their first use. */
    internal suspend fun <R> transaction(schema: SqliteSchema, block: suspend SQLiteConnection.() -> R): R = lock.withLock {
        withContext(sqliteDispatcher) {
            val connection = connection ?: openSqlite(location).also {
                it.execute(Sql("PRAGMA foreign_keys = ON"))
                connection = it
            }
            connection.execute(Sql("BEGIN IMMEDIATE"))
            try {
                val created = schema.tableName !in prepared
                if (created) connection.migrate(schema)
                connection.block().also {
                    connection.execute(Sql("COMMIT"))
                    if (created) prepared += schema.tableName
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { connection.execute(Sql("ROLLBACK")) }
                throw e
            }
        }
    }

    // Only adds: tables, columns for fields a newer model introduced, and indexes.  Nothing is dropped.
    private suspend fun SQLiteConnection.migrate(schema: SqliteSchema) {
        schema.createTables().forEach { execute(Sql(it)) }
        for ((table, columns) in schema.expectedColumns()) {
            val existing = query(Sql("SELECT name FROM pragma_table_info(?)", listOf(table))) { it.getText(0) }.toSet()
            for (column in columns) {
                if (column.name !in existing) execute(Sql("ALTER TABLE ${q(table)} ADD COLUMN ${q(column.name)} ${column.type.declaration}"))
            }
        }
        schema.createIndexes().forEach { execute(Sql(it)) }
    }
}

internal suspend fun SQLiteConnection.execute(sql: Sql) {
    prepare(sql.text).use {
        it.bindAll(sql.args)
        it.step()
    }
}

internal suspend fun <R> SQLiteConnection.query(sql: Sql, read: (SQLiteStatement) -> R): List<R> =
    prepare(sql.text).use {
        it.bindAll(sql.args)
        buildList { while (it.step()) add(read(it)) }
    }

internal expect suspend fun openSqlite(location: String): SQLiteConnection

/** Where blocking SQLite calls run. */
internal expect val sqliteDispatcher: CoroutineDispatcher
