package com.lightningkite.services.database.jsonfile

import com.lightningkite.services.database.InMemoryFieldCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * An InMemoryFieldCollection with the added feature of loading data from a file at creation
 * and writing the collection data into a file when closing.
 */
internal class JsonFileFieldCollection<Model : Any>(
    val encoding: StringFormat,
    serializer: KSerializer<Model>,
    val filesystem: FileSystem,
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
                file.takeIf { filesystem.exists(it) }?.let { filesystem.source(it).buffered().readString() } ?: "[]"
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
        val temp = Path(file.parent!!, file.name + ".saving")
        filesystem.sink(temp).buffered().use {
            it.writeString(encoding.encodeToString(ListSerializer(serializer), data.toList()))
        }
        filesystem.atomicMove(temp, file)
        logger.debug("Saved $file")
    }
}