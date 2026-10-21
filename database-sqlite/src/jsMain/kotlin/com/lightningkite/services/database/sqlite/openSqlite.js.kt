package com.lightningkite.services.database.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.open
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.w3c.dom.Worker
import org.w3c.dom.WorkerOptions
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

// The pool of files in the origin private file system can only be open in one worker, so every
// database in the page shares this one.
private val driver by lazy {
    val base = js("globalThis.document ? globalThis.document.baseURI : globalThis.location.href") as String
    val sqliteModule = URL("lightningkite-sqlite/index.mjs", base).href
    val source = workerSource.replace("SQLITE_MODULE_URL", JSON.stringify(sqliteModule))
    val worker = Worker(
        URL.createObjectURL(Blob(arrayOf(source), BlobPropertyBag(type = "text/javascript"))),
        js("({ type: \"module\" })").unsafeCast<WorkerOptions>(),
    )
    WebWorkerSQLiteDriver(worker)
}

internal actual suspend fun openSqlite(location: String): SQLiteConnection {
    require('/' !in location) { "On the web, native:// takes a file name without slashes; got '$location'" }
    return driver.open(location)
}

internal actual val sqliteDispatcher: CoroutineDispatcher = Dispatchers.Default

/**
 * The worker [WebWorkerSQLiteDriver] talks to, implementing the message protocol its KDoc describes
 * on top of SQLite's WebAssembly build, with databases stored in the origin private file system.
 */
private val workerSource = """
const ready = (async () => {
    const { default: sqlite3InitModule } = await import(SQLITE_MODULE_URL);
    const sqlite3 = await sqlite3InitModule();
    return await sqlite3.installOpfsSAHPoolVfs({ name: "lightningkite-sqlite" });
})();
const databases = new Map();
const statements = new Map();
let nextId = 1;

// SQLite's own type codes: integer, float, text, blob, null.
const typeOf = (value) =>
    value === null ? 5 : typeof value === "string" ? 3 : value instanceof Uint8Array ? 4 : Number.isInteger(value) ? 1 : 2;

// Requests are handled one at a time, in the order they arrive, even when handling one waits.
let queue = Promise.resolve();
onmessage = ({ data: { id, data: request } }) => {
    queue = queue.then(() => handle(id, request));
};

async function handle(id, request) {
    try {
        const pool = await ready;
        let response;
        switch (request.cmd) {
            case "open": {
                // A database takes up to two of the pool's files (itself and its journal), and the
                // pool doesn't grow by itself.
                await pool.reserveMinimumCapacity(pool.getFileCount() + 2);
                const databaseId = nextId++;
                databases.set(databaseId, new pool.OpfsSAHPoolDb(request.fileName));
                response = { databaseId };
                break;
            }
            case "prepare": {
                const statement = databases.get(request.databaseId).prepare(request.sql);
                const statementId = nextId++;
                statements.set(statementId, statement);
                // getColumnNames() throws for a statement without result columns.
                const columnNames = statement.columnCount > 0 ? statement.getColumnNames() : [];
                response = { statementId, parameterCount: statement.parameterCount, columnNames };
                break;
            }
            case "step": {
                const statement = statements.get(request.statementId);
                statement.reset(true);
                if (statement.parameterCount > 0) {
                    statement.bind(Array.from({ length: statement.parameterCount }, (_, i) => request.bindings[i] ?? null));
                }
                const rows = [];
                // Integers beyond 2^53 go back as their exact digits; SqliteValues reads them as text.
                const exact = (v) => (typeof v !== "bigint" ? v : Number.isSafeInteger(Number(v)) ? Number(v) : v.toString());
                while (statement.step()) rows.push(statement.get([]).map(exact));
                const columnTypes = rows.length > 0 ? rows[0].map(typeOf) : new Array(statement.columnCount).fill(5);
                response = { rows, columnTypes };
                break;
            }
            case "close": {
                // One-way: the driver doesn't wait for a reply.
                if (request.statementId != null) {
                    statements.get(request.statementId)?.finalize();
                    statements.delete(request.statementId);
                }
                if (request.databaseId != null) {
                    databases.get(request.databaseId)?.close();
                    databases.delete(request.databaseId);
                }
                return;
            }
            default:
                throw new Error("Unknown command " + request.cmd);
        }
        // The driver reads both keys, so both are always present.
        postMessage({ id, data: response, error: null });
    } catch (e) {
        postMessage({ id, data: null, error: String(e && e.message ? e.message : e) });
    }
}
"""
