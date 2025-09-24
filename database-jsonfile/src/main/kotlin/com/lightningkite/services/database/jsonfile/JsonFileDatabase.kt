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
 * A Database implementation whose data manipulation is entirely in the application Heap, but it will attempt to store the data into a Folder on the system before shutdown.
 * On startup it will load in the Folder contents and populate the database.
 * It uses InMemoryUnsafePersistentFieldCollection in its implementation. This is NOT meant for long term storage.
 * It is NOT guaranteed that it will store the data before the application is shut down. There is a HIGH chance that the changes will not persist between runs.
 * This is useful in places that persistent data is not important and speed is desired.
 *
 * @param folder The File references a directory where you wish the data to be stored.
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