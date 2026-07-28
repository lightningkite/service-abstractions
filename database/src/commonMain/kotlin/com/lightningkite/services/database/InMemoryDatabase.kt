package com.lightningkite.services.database

import com.lightningkite.services.SettingContext
import com.lightningkite.services.kfile.KFile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A Database implementation that exists entirely in the application's heap.
 *
 * Each [Table] is backed by an [InMemoryTable] keyed by `_id`. The backing map
 * is produced by [mapFactory], which lets JVM users supply
 * `java.util.concurrent.ConcurrentHashMap` (or any other concrete map) instead
 * of the default [HashMap].
 *
 * Not intended for persistent storage; data is erased when the process stops.
 *
 * @param premadeData Optional preload source.
 * @param mapFactory Factory for the per-table backing map.
 */
public class InMemoryDatabase(
    override val name: String,
    private val premadeData: PreloadData? = null,
    override val context: SettingContext,
    private val mapFactory: () -> MutableMap<Any?, Any> = { HashMap() },
) : Database {
    public val collections: HashMap<DatabaseTableDefinition<*>, InMemoryTable<*>> = HashMap()

    private val json = Json {
        serializersModule = context.internalSerializersModule
        ignoreUnknownKeys = true
    }

    public interface PreloadData {
        public fun <T> get(context: SettingContext, json: Json, serializer: KSerializer<T>, tableName: String): List<T>?

        public data class InMemory(val data: JsonObject) : PreloadData {
            override fun <T> get(context: SettingContext, json: Json, serializer: KSerializer<T>, tableName: String): List<T>? =
                data[tableName]?.let {
                    json.decodeFromJsonElement(ListSerializer(serializer), it)
                }
        }

        public data class JsonFiles(val folder: KFile) : PreloadData {
            override fun <T> get(context: SettingContext, json: Json, serializer: KSerializer<T>, tableName: String): List<T>? {
                val file = folder.then("$tableName.json")

                return if (!file.exists()) null
                else json.decodeFromString(ListSerializer(serializer), file.readString())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> table(tableDef: DatabaseTableDefinition<T>): Table<T> =
        (collections.getOrPut(tableDef) {
            val backing = mapFactory() as MutableMap<Any?, T>
            val made = InMemoryTable(
                data = backing,
                serializer = tableDef.serializer,
                tableName = tableDef.name,
            )
            premadeData?.get(context, json, tableDef.serializer, tableDef.name)?.let { made.preload(it) }
            made
        } as Table<T>)

    // In-memory tables need no asynchronous setup, so preparation is just retrieval.
    override suspend fun <T : Any> prepare(tableDef: DatabaseTableDefinition<T>): Table<T> = table(tableDef)
}
