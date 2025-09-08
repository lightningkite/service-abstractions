package com.lightningkite.services.database

import com.lightningkite.services.SettingContext
import com.lightningkite.services.countMetric
import com.lightningkite.services.performanceMetric
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
public class InMemoryDatabase(override val name: String, private val premadeData: JsonObject? = null, override val context: SettingContext) : Database {
    public val collections: HashMap<Pair<KSerializer<*>, String>, Table<*>> = HashMap()

    private val waitMetric = performanceMetric("wait")
    private val callMetric = countMetric("call")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> table(serializer: KSerializer<T>, name: String): Table<T> =
        (collections.getOrPut(serializer to name) {
            val made = InMemoryTable(serializer = serializer)
            premadeData?.get(name)?.let {
                val json = Json { this.serializersModule = context.internalSerializersModule }

                val data = json.decodeFromJsonElement(
                    ListSerializer(serializer),
                    it
                )
                made.data.addAll(data)
            }
            made
        } as Table<T>).let {
            MetricsTable(
                it,
                waitMetric,
                callMetric,
            )
        }

}