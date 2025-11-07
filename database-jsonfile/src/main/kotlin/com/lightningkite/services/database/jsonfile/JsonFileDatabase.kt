package com.lightningkite.services.database.jsonfile

import com.lightningkite.services.database.Database
import com.lightningkite.services.database.Table
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.KFile
import com.lightningkite.services.data.root
import com.lightningkite.services.data.workingDirectory
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.collections.HashMap

/**
 * JSON file-based database implementation for development and testing.
 *
 * Provides a simple file-based database that stores each table as a JSON file on disk.
 * This is designed for development, testing, and scenarios where persistence is needed
 * but a full database server is overkill.
 *
 * ## Features
 *
 * - **File-based storage**: Each table stored as a separate JSON file
 * - **In-memory operations**: All data loaded into memory for fast queries
 * - **Automatic persistence**: Changes written to disk periodically
 * - **Development friendly**: Easy to inspect/edit data files manually
 * - **No server required**: Perfect for local development and CI tests
 * - **Migration support**: Automatically migrates old single-file format to new .json extension
 *
 * ## Supported URL Schemes
 *
 * - `json-files://path/to/folder` - Store database files in specified folder
 * - `json-files://./data` - Relative path from working directory
 *
 * Format: `json-files://[folder-path]`
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Development database in project folder
 * Database.Settings("json-files://./local-data")
 *
 * // Testing database in temp folder
 * Database.Settings("json-files:///tmp/test-db")
 *
 * // Using helper function
 * Database.Settings.Companion.jsonFile(workingDirectory.resolve("data"))
 * ```
 *
 * ## Implementation Notes
 *
 * - **In-memory first**: All data loaded into memory on table access
 * - **Lazy loading**: Tables loaded on first access, not on database creation
 * - **File naming**: Table names sanitized (only letters/digits) for filenames
 * - **JSON format**: Stores table data as JSON array of objects
 * - **Thread-safe**: Uses synchronized collections for concurrent access
 * - **Serialization**: Uses context.internalSerializersModule for custom types
 * - **Migration**: Automatically moves old files without .json extension to .json format
 *
 * ## Important Gotchas
 *
 * - **NOT production-ready**: This is for development/testing only
 * - **No persistence guarantee**: Changes may be lost if app crashes before write
 * - **Memory constraints**: Entire database must fit in memory
 * - **No transactions**: No ACID guarantees across operations
 * - **Single-process only**: No locking between processes (data corruption possible)
 * - **Performance**: Slow for large datasets (full table scans)
 * - **No concurrent writes**: Synchronization prevents true parallelism
 * - **File corruption**: Manual edits can corrupt data (invalid JSON)
 *
 * ## When to Use
 *
 * **Good for:**
 * - Local development without database setup
 * - Integration tests in CI pipelines
 * - Simple CLI tools with small datasets
 * - Prototyping and demos
 * - Configuration storage
 *
 * **Avoid for:**
 * - Production applications
 * - Large datasets (> 10MB per table)
 * - High concurrency scenarios
 * - Multi-process applications
 * - Critical data requiring ACID guarantees
 *
 * ## File Structure
 *
 * ```
 * data/
 * ├── Users.json       # Table: Users
 * ├── Posts.json       # Table: Posts
 * └── Comments.json    # Table: Comments
 * ```
 *
 * Each file contains a JSON array:
 * ```json
 * [
 *   {"_id": "...", "name": "Alice", "age": 30},
 *   {"_id": "...", "name": "Bob", "age": 25}
 * ]
 * ```
 *
 * @property name Service name for logging/metrics
 * @property folder Directory where JSON files are stored
 * @property context Service context with serializers
 */
public class JsonFileDatabase(
    override val name: String,
    public val folder: KFile,
    override val context: SettingContext
) :
    Database {
    init {
        folder.createDirectories()
    }

    public companion object {
        public fun Database.Settings.Companion.jsonFile(folder: KFile): Database.Settings = Database.Settings("json-files://$folder")
        init {
            Database.Settings.register("json-files") { name, url, context ->
                JsonFileDatabase(
                    name,
                    workingDirectory.resolve(url.substringAfter("://")),
                    context
                )
            }
        }
    }

    public val collections: HashMap<Pair<KSerializer<*>, String>, Table<*>> = HashMap()

    override fun <T : Any> table(serializer: KSerializer<T>, name: String): Table<T> =
        synchronized(collections) {
            @Suppress("UNCHECKED_CAST")
            collections.getOrPut(serializer to name) {
                val fileName = name.filter { it.isLetterOrDigit() }
                val oldStyle = folder.then(fileName)
                val storage = folder.then("$fileName.json")
                if (oldStyle.exists() && !storage.exists())
                    storage.sink(append = false).buffered().use { sink ->
                        oldStyle.source().buffered().use { source ->
                            source.transferTo(sink)
                        }
                    }
                val json = Json { this.serializersModule = context.internalSerializersModule }
                JsonFileTable(
                    json,
                    serializer,
                    storage
                )
            } as Table<T>
        }
}