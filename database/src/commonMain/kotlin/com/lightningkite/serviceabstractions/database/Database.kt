package com.lightningkite.serviceabstractions.database

import com.lightningkite.serviceabstractions.HealthStatus
import com.lightningkite.serviceabstractions.Service
import com.lightningkite.serviceabstractions.Setting
import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.UrlSettingParser
import com.lightningkite.serviceabstractions.database.InMemoryDatabase
import kotlinx.io.buffered
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline
import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * An abstracted model for communicating with a Database.
 * Every implementation will handle how to return a FieldCollection to perform actions on a collection/table in the underlying database system.
 */
interface Database : Service {
    /**
     * Settings that define what cache to use and how to connect to it.
     *
     * @param url Defines the type and connection to the cache. Built-in options are local.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "ram-unsafe-persist://${Path(SystemFileSystem.resolve(Path("")), "./local/database")}"
    ) : Setting<Database> {
        public companion object : UrlSettingParser<Database>() {
            init {
                register("ram") { _, context -> MetricsWrappedDatabase(InMemoryDatabase(context = context), "Database") }
                register("ram-preload") { url, context ->
                    val json = Json { this.serializersModule = context.serializersModule }
                    MetricsWrappedDatabase(InMemoryDatabase(
                        json.parseToJsonElement(
                            SystemFileSystem.source(Path(url.substringAfter("://"))).buffered().readByteString()
                                .decodeToString()
                        ) as? JsonObject,
                        context
                    ), "Database")
                }
                register("delay") { url, context ->
                    val x = url.substringAfter("://")
                    val delayString = x.substringBefore("/")
                    val delay = delayString.toLongOrNull()?.let { it.milliseconds..it.milliseconds }
                        ?: delayString.takeIf { it.contains('-') }?.let {
                            Duration.parse(it.substringBefore('-').trim())..
                                    Duration.parse(it.substringAfter('-').trim())
                        }
                        ?: delayString.takeUnless { it.isBlank() }?.let {
                            val duration = Duration.parse(it.trim())
                            duration.times(0.5)..duration
                        }
                        ?: 350.milliseconds..750.milliseconds
                    val wraps = x.substringAfter("/")
                    MetricsWrappedDatabase(parse(wraps.substringBefore("://"), context).delayed(delay), "Database")
                }
            }
        }

        override fun invoke(context: SettingContext): Database {
            return parse(url, context)
        }
    }

    /**
     * Returns a FieldCollection of type T that will access and manipulate data from a collection/table in the underlying database system.
     */
    fun <T : Any> collection(serializer: KSerializer<T>, name: String): FieldCollection<T>

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> collection(type: KType, name: String): FieldCollection<T> =
        collection(context.serializersModule.serializer(type) as KSerializer<T>, name)

    /**
     * Will attempt inserting data into the database to confirm that the connection is alive and available.
     */
    override suspend fun healthCheck(): HealthStatus {
        prepareModelsServerCore()
        try {
            val c = collection<HealthCheckTestModel>()
            val id = "HealthCheck"
            c.upsertOneById(id, HealthCheckTestModel(id))
            if (c.get(id) == null) throw AssertionError("Assertion Failed")
            return HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message ?: e::class.toString())
        }
    }
}

@GenerateDataClassPaths
@Serializable
data class HealthCheckTestModel(override val _id: String) : HasId<String>

/**
 * A Helper function for getting a collection from a database using generics.
 * This can make collection calls much cleaner and less wordy when the types can be inferred.
 */
inline fun <reified T : Any> Database.collection(name: String = T::class.simpleName!!): FieldCollection<T> {
    return collection(context.serializersModule.serializer<T>(), name)
}
