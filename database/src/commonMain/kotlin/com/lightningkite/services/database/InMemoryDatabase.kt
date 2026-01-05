package com.lightningkite.services.database

import com.lightningkite.services.SettingContext
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

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
    private var premadeData: JsonObject? = null,
    override val context: SettingContext
) : Database, Exportable, Importable {
    public val collections: HashMap<Pair<KSerializer<*>, String>, InMemoryTable<*>> = HashMap()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> table(serializer: KSerializer<T>, name: String): Table<T> =
        (collections.getOrPut(serializer to name) {
            val made = InMemoryTable(serializer = serializer, tableName = name, tracer = context.openTelemetry)
            premadeData?.get(name)?.let {
                val json = Json { this.serializersModule = context.internalSerializersModule }

                val data = json.decodeFromJsonElement(
                    ListSerializer(serializer),
                    it
                )
                made.data.addAll(data)
            }
            made
        } as Table<T>)

    override fun export(): DatabaseExport = flow {
        for ((_, table) in collections)
            emit(table.export(context.internalSerializersModule))

        val premade = premadeData ?: return@flow
        val exported = collections.keys.map { it.second }.toSet()

        for ((name, element) in premade.filterKeys { it !in exported })
            emit(TableExport(name, element.jsonArray.asFlow()))
    }

    override suspend fun import(data: DatabaseExport) {
        val preloadData = premadeData
            ?.mapValues { it.value.jsonArray.toMutableList() }
            ?.toMutableMap()
            ?: mutableMapOf()

        var addedToPreload = false

        data.collect { tableExport ->
            val table = collections.entries
                .find { it.key.second == tableExport.tableName }
                ?.value

            if (table != null) table.import(
                tableExport,
                context.internalSerializersModule
            )
            else {
                preloadData
                    .getOrPut(tableExport.tableName, ::mutableListOf)
                    .addAll(tableExport.items.toList())
                addedToPreload = true
            }
        }

        if (addedToPreload) premadeData = buildJsonObject {
            for ((name, items) in preloadData) putJsonArray(name) {
                @OptIn(ExperimentalSerializationApi::class)
                addAll(items)
            }
        }
    }
}