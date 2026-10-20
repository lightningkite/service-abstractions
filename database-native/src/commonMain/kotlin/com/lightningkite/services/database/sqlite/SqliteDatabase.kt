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
 * `context.getDatabasePath("app.db").path`.  On the web it is a file name without slashes.
 *
 * On the web the app must serve SQLite's WebAssembly build: `index.mjs` and `sqlite3.wasm` from the
 * `@sqlite.org/sqlite-wasm` npm package's `dist` folder, at `lightningkite-sqlite/` relative to the
 * page's base URL.  For example, in the app's build file:
 * ```kotlin
 * tasks.named<Copy>("jsProcessResources") {
 *     from(rootProject.layout.buildDirectory.dir("js/node_modules/@sqlite.org/sqlite-wasm/dist")) {
 *         include("index.mjs", "sqlite3.wasm")
 *         into("lightningkite-sqlite")
 *     }
 *     dependsOn(":kotlinNpmInstall")
 * }
 * ```
 * Only one tab can have the databases open at a time, and `Long` values beyond ±2^53 lose precision,
 * because the web driver passes integers through JavaScript numbers.
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
        table(tableDef).also { transaction(it) { } }

    override suspend fun disconnect() {
        lock.withLock {
            connection?.close()
            connection = null
            prepared.clear()
        }
    }

    /** Runs [block] in a transaction, after creating or extending [table]'s tables if this is their first use. */
    internal suspend fun <R> transaction(table: SqliteTable<*>, block: suspend SQLiteConnection.() -> R): R = lock.withLock {
        withContext(sqliteDispatcher) {
            val connection = connection ?: openSqlite(location).also {
                it.execute(Sql("PRAGMA foreign_keys = ON"))
                connection = it
            }
            connection.execute(Sql("BEGIN IMMEDIATE"))
            try {
                val created = table.name !in prepared
                if (created) table.migrate(connection)
                connection.block().also {
                    connection.execute(Sql("COMMIT"))
                    if (created) prepared += table.name
                }
            } catch (e: Throwable) {
                // SQLite rolls back by itself after some errors, and then ROLLBACK fails; the original error is the one to report.
                withContext(NonCancellable) {
                    runCatching { connection.execute(Sql("ROLLBACK")) }.exceptionOrNull()?.let { e.addSuppressed(it) }
                }
                throw e
            }
        }
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
