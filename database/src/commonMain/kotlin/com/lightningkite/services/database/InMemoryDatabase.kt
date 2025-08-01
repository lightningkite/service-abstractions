package com.lightningkite.services.database

import com.lightningkite.services.SettingContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A Database implementation that exists entirely in the applications Heap. There are no external connections.
 * It uses InMemoryFieldCollections in its implementation. This is NOT meant for persistent or long term storage.
 * This database will be completely erased everytime the application is stopped.
 * This is useful in places that persistent data is not needed and speed is desired such as Unit Tests.
 *
 * @param premadeData A JsonObject that contains data you wish to populate the database with on creation.
 */
class InMemoryDatabase(val premadeData: JsonObject? = null, override val context: SettingContext) : Database {
    val collections: HashMap<Pair<KSerializer<*>, String>, FieldCollection<*>> = HashMap()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): FieldCollection<T> =
        collections.getOrPut(serializer to name) {
            val made = InMemoryFieldCollection(serializer = serializer)
            premadeData?.get(name)?.let {
                val json = Json { this.serializersModule = context.serializersModule }

                val data = json.decodeFromJsonElement(
                    ListSerializer(serializer),
                    it
                )
                made.data.addAll(data)
            }
            made
        } as FieldCollection<T>

}