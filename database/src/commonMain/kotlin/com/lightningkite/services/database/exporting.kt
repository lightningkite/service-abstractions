package com.lightningkite.services.database

import com.lightningkite.services.data.KFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

public suspend fun Database.importTablesFrom(
    source: Database,
    tables: List<Pair<String, KSerializer<*>>>,
) {
    @Suppress("UNCHECKED_CAST")
    tables as List<Pair<String, KSerializer<Any>>>

    for ((name, serializer) in tables) {
        val sauce = source.table(serializer, name)
        val dest = this.table(serializer, name)
        dest.insert(sauce.all().toList())
    }
}

public data class TableExport(
    val tableName: String,
    val items: Flow<JsonElement>
)

public typealias DatabaseExport = Flow<TableExport>

public interface Exportable {
    public fun export(): DatabaseExport
}

public interface Importable {
    public suspend fun import(data: DatabaseExport)
}

public suspend fun Importable.import(data: Exportable) {
    import(data.export())
}

@OptIn(ExperimentalSerializationApi::class)
public suspend fun DatabaseExport.toJsonObject(): JsonObject = buildJsonObject {
    collect { tableExport ->
        val items = tableExport.items.toList()
        putJsonArray(tableExport.tableName) { addAll(items) }
    }
}

private suspend fun Flow<JsonElement>.toJsonArray(): JsonArray = buildJsonArray {
    collect { add(it) }
}

public suspend fun DatabaseExport.writeToJsonFiles(
    folder: KFile,
    json: Json
) {
    folder.createDirectories()

    collect { tableExport ->
        val filename = tableExport.tableName.filter { it.isLetterOrDigit() } + ".json"
        val file = folder.then(filename)
        val temp = folder.then("$filename.saving")
        temp.writeString(
            json.encodeToString(tableExport.items.toJsonArray())
        )
        temp.atomicMove(file)
    }
}

public suspend fun Importable.importFromJsonFiles(
    files: List<KFile>,
    json: Json
) {
    import(
        files.map { file ->
            TableExport(
                tableName = file.nameWithoutExtension,
                items = json.decodeFromString(
                    JsonArray.serializer(),
                    file.readString()
                ).asFlow()
            )
        }.asFlow()
    )
}