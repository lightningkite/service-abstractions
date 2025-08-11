package com.lightningkite.services.database.jsonfile

import com.lightningkite.services.database.Database
import com.lightningkite.services.database.Table
import com.lightningkite.services.SettingContext
import com.lightningkite.services.countMetric
import com.lightningkite.services.database.MetricsTable
import com.lightningkite.services.performanceMetric
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
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
    public val filesystem: FileSystem,
    public val folder: Path,
    override val context: SettingContext
) :
    Database {
    init {
        SystemFileSystem.createDirectories(folder)
    }

    public companion object {
        init {
            Database.Settings.register("ram-unsafe-persist") { name, url, context ->
                JsonFileDatabase(
                    name,
                    SystemFileSystem,
                    Path(url.substringAfter("://")), context
                )
            }
        }
    }

    public val collections: HashMap<Pair<KSerializer<*>, String>, Table<*>> = HashMap()

    private val waitMetric = performanceMetric("wait")
    private val callMetric = countMetric("call")
    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): Table<T> =
        synchronized(collections) {
            @Suppress("UNCHECKED_CAST")
            collections.getOrPut(serializer to name) {
                val fileName = name.filter { it.isLetterOrDigit() }
                val oldStyle = filesystem.resolve(Path(folder, fileName))
                val storage = filesystem.resolve(Path(folder, "$fileName.json"))
                if (filesystem.exists(oldStyle) && !filesystem.exists(storage))
                    filesystem.sink(storage, append = false).buffered().use { sink ->
                        filesystem.source(oldStyle).buffered().use { source ->
                            source.transferTo(sink)
                        }
                    }
                val json = Json { this.serializersModule = context.internalSerializersModule }
                JsonFileTable(
                    json,
                    serializer,
                    filesystem,
                    storage
                ).let {
                    MetricsTable(
                        it,
                        waitMetric,
                        callMetric,
                    )
                }
            } as Table<T>
        }
}