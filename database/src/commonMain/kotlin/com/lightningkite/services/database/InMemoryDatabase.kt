package com.lightningkite.services.database

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.KFile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * A Database implementation that exists entirely in the applications Heap. There are no external connections.
 * It uses InMemoryFieldCollections in its implementation. This is NOT meant for persistent or long term storage.
 * This database will be completely erased everytime the application is stopped.
 * This is useful in places that persistent data is not needed and speed is desired such as Unit Tests.
 *
 * @param premadeData A JsonObject that contains data you wish to populate the database with on creation.
 */
public class InMemoryDatabase(
    override val name: String,
    private val premadeData: PreloadData? = null,
    override val context: SettingContext
) : Database {
    public val collections: HashMap<Pair<KSerializer<*>, String>, InMemoryTable<*>> = HashMap()

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
    override fun <T : Any> table(serializer: KSerializer<T>, name: String): Table<T> =
        (collections.getOrPut(serializer to name) {
            val made = InMemoryTable(serializer = serializer, tableName = name, tracer = context.openTelemetry)
            premadeData
                ?.get(context, json, serializer, name)
                ?.let { made.data.addAll(it) }
            made
        } as Table<T>)
}