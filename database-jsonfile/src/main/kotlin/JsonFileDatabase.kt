import com.lightningkite.serviceabstractions.database.Database
import com.lightningkite.serviceabstractions.database.FieldCollection
import InMemoryUnsafePersistentFieldCollection
import com.lightningkite.serviceabstractions.SettingContext
import kotlinx.io.buffered
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
public class JsonFileDatabase(public val folder: Path, override val context: SettingContext) :
    Database {
    init {
        SystemFileSystem.createDirectories(folder)
    }

    public companion object {
        init {
            Database.Settings.register("ram-unsafe-persist") { url, context ->
                JsonFileDatabase(
                    Path(url.substringAfter("://")), context
                )
            }
        }
    }

    public val collections: HashMap<Pair<KSerializer<*>, String>, FieldCollection<*>> = HashMap<Pair<KSerializer<*>, String>, FieldCollection<*>>()

    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): FieldCollection<T> =
        synchronized(collections) {
            @Suppress("UNCHECKED_CAST")
            collections.getOrPut(serializer to name) {
                val fileName = name.filter { it.isLetterOrDigit() }
                val oldStyle = SystemFileSystem.resolve(Path(folder, fileName), )
                val storage = SystemFileSystem.resolve(Path(folder, "$fileName.json"))
                if (SystemFileSystem.exists(oldStyle) && !SystemFileSystem.exists(storage))
                    SystemFileSystem.sink(storage, append = false).buffered().use { sink ->
                        SystemFileSystem.source(oldStyle).buffered().use { source ->
                            source.transferTo(sink)
                        }
                    }
                val json = Json { this.serializersModule = context.serializersModule }
                InMemoryUnsafePersistentFieldCollection(
                    json,
                    serializer,
                    storage
                )
            } as FieldCollection<T>
        }
}