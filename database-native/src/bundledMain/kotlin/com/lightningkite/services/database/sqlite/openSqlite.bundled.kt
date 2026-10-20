package com.lightningkite.services.database.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual suspend fun openSqlite(location: String): SQLiteConnection =
    BundledSQLiteDriver().open(databasePath(location)).also { it.execute(Sql("PRAGMA journal_mode = WAL")) }

internal actual val sqliteDispatcher: CoroutineDispatcher = Dispatchers.IO

internal expect fun databasePath(location: String): String
