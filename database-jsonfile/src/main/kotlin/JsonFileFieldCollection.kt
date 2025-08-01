import com.lightningkite.services.database.InMemoryFieldCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Files
import java.util.Collections
import kotlin.io.path.exists

/**
 * An InMemoryFieldCollection with the added feature of loading data from a file at creation
 * and writing the collection data into a file when closing.
 */
internal class JsonFileFieldCollection<Model : Any>(
    val encoding: StringFormat,
    serializer: KSerializer<Model>,
    val file: Path
) : InMemoryFieldCollection<Model>(
    data = Collections.synchronizedList(ArrayList()),
    serializer = serializer
), Closeable {

    companion object{
        val logger = LoggerFactory.getLogger(this::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @OptIn(ObsoleteCoroutinesApi::class)
    val saveScope = scope.actor<Unit>(start = CoroutineStart.LAZY) {
        handleCollectionDump()
    }

    init {
        data.addAll(
            encoding.decodeFromString(
                ListSerializer(serializer),
                file.takeIf { it.exists() }?.readText() ?: "[]"
            )
        )
        val shutdownHook = Thread {
            handleCollectionDump()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override fun close() {
        scope.launch {
            saveScope.send(Unit)
        }
    }

    fun handleCollectionDump() {
        val temp = file.parentFile!!.resolve(file.name + ".saving")
        temp.writeText(encoding.encodeToString(ListSerializer(serializer), data.toList()))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        logger.debug("Saved $file")
    }
}