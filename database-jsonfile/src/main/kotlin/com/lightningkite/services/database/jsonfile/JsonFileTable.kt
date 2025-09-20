package com.lightningkite.services.database.jsonfile

import com.lightningkite.services.data.KFile
import com.lightningkite.services.database.InMemoryTable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.ListSerializer
import java.io.Closeable
import java.util.Collections

/**
 * An InMemoryFieldCollection with the added feature of loading data from a file at creation
 * and writing the collection data into a file when closing.
 */
internal class JsonFileTable<Model : Any>(
    val encoding: StringFormat,
    serializer: KSerializer<Model>,
    val file: KFile,
) : InMemoryTable<Model>(
    data = Collections.synchronizedList(ArrayList()),
    serializer = serializer
), Closeable {

    companion object {
        val logger = KotlinLogging.logger("com.lightningkite.services.database.jsonfile.JsonFileTable")
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
                file.readStringOrNull() ?: "[]"
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
        val temp = file.parent!!.then(file.name + ".saving")
        temp.writeString(encoding.encodeToString(ListSerializer(serializer), data.toList()))
        temp.atomicMove(file)
        logger.debug { "Saved $file" }
    }
}