package com.lightningkite.services.cache.database

import com.lightningkite.services.SettingContext
import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.ExperimentalLightningServer
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.DataClassPath
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.and
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.deleteOneById
import com.lightningkite.services.database.eq
import com.lightningkite.services.database.get
import com.lightningkite.services.database.lte
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.neq
import com.lightningkite.services.database.not
import com.lightningkite.services.database.notNull
import com.lightningkite.services.database.or
import com.lightningkite.services.database.upsertOneById
import com.lightningkite.services.default
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

// leaving as internal until finalized and ready for use
internal class DatabaseCache(
    override val name: String,
    override val context: SettingContext,
    public val wraps: Database
) : Cache {
    internal companion object {
        fun Cache.Settings.Companion.database(database: Database.Settings = Database.Settings()): Cache.Settings =
            Cache.Settings("db://${database.url}")

        init {
            Cache.Settings.register("db") { name, url, context ->
                val dbUrl = url.substringAfter("://")
                DatabaseCache(
                    name,
                    context,
                    Database.Settings.parse("$name-database", dbUrl, context)
                )
            }
        }
    }

    @Serializable
    @GenerateDataClassPaths
    public data class Entry(
        override val _id: String,
        val arbitraryValue: String?,
        val integerValue: Long?,
        val floatValue: Double?,
        val expiration: Instant?
    ) : HasId<String>

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = context.internalSerializersModule
    }

    private suspend fun Entry.expired(): Boolean =
        expiration != null && expiration <= Clock.default().now()

    private suspend fun Duration.toExpiration(): Instant =
        Clock.default().now() + this


    private suspend fun <T> Entry(key: String, serializer: KSerializer<T>, value: T, timeToLive: Duration?): Entry {
        return when (serializer.descriptor.kind) {
            PrimitiveKind.BYTE,
            PrimitiveKind.SHORT,
            PrimitiveKind.INT,
            PrimitiveKind.LONG -> {
                val number = encodeNumber(context.internalSerializersModule, serializer, value).integer
                Entry(
                    _id = key,
                    arbitraryValue = if (number == null) json.encodeToString(serializer, value) else null,
                    integerValue = number,
                    floatValue = null,
                    expiration = timeToLive?.toExpiration()
                )
            }

            PrimitiveKind.FLOAT,
            PrimitiveKind.DOUBLE -> {
                val number = encodeNumber(context.internalSerializersModule, serializer, value).float
                Entry(
                    _id = key,
                    arbitraryValue = if (number == null) json.encodeToString(serializer, value) else null,
                    integerValue = null,
                    floatValue = number,
                    expiration = timeToLive?.toExpiration()
                )
            }

            else -> Entry(
                _id = key,
                arbitraryValue = json.encodeToString(serializer, value),
                integerValue = null,
                floatValue = null,
                expiration = timeToLive?.toExpiration()
            )
        }
    }

    private fun <T> Entry.decode(serializer: KSerializer<T>): T {
        when (serializer.descriptor.kind) {
            PrimitiveKind.BYTE,
            PrimitiveKind.SHORT,
            PrimitiveKind.INT,
            PrimitiveKind.LONG -> {
                integerValue?.let {
                    return json.decodeFromJsonElement(serializer, JsonPrimitive(it))
                }
            }

            PrimitiveKind.FLOAT,
            PrimitiveKind.DOUBLE -> {
                floatValue?.let {
                    return json.decodeFromJsonElement(serializer, JsonPrimitive(it))
                }
            }

            else -> {}
        }

        return json.decodeFromString(
            serializer,
            arbitraryValue ?: throw IllegalStateException("No value encoded for entry $_id (${serializer.descriptor.serialName}) (This shouldn't happen)")
        )
    }

    private suspend fun <K> DataClassPath<K, Entry>.expired(): Condition<K> =
        expiration.notNull.lte(Clock.default().now())



    private val table = wraps.table(Entry.serializer(), "database-cache")

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? =
        table.get(key)?.takeUnless { it.expired() }?.decode(serializer)

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        table.upsertOneById(
            key,
            Entry(key, serializer, value, timeToLive)
        )
    }

    override suspend fun <T> setIfNotExists(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?): Boolean {
        val mightInsert = Entry(key, serializer, value, timeToLive)

        val updated = table.upsertOne(
            condition { it._id.eq(key) and !it.expired() },
            Modification.Nothing(),
            mightInsert
        ).new

        return updated != null && updated == mightInsert
    }

    override suspend fun add(key: String, value: Long, timeToLive: Duration?): Long {
        val updated = table
            .upsertOne(
                condition {
                    Condition.And(
                        it._id eq key,
                        it.integerValue.neq(null) or it.floatValue.neq(null),
                        !it.expired()
                    )
                },
                modification {
                    it.integerValue.notNull += value
                    it.floatValue.notNull += value.toDouble()
                },
                Entry(
                    _id = key,
                    integerValue = value,
                    arbitraryValue = null,
                    floatValue = null,
                    expiration = timeToLive?.toExpiration()
                )
            )
            .new ?: throw IllegalStateException("Could not upsert entry")

        return updated.integerValue
            ?: updated.floatValue?.toLong()
            ?: throw IllegalStateException("Upserted entry is not of a numeric type")
    }

    override suspend fun <T> compareAndSet(key: String, serializer: KSerializer<T>, expected: T?, new: T?, timeToLive: Duration?): Boolean {
        if (expected == new) return true

        if (expected == null) return setIfNotExists(key, new!!, serializer, timeToLive)

        val expected = Entry(key, serializer, expected, null)

        val equalToExpected: Condition<Entry> = condition {
            Condition.And(
                it._id eq key,
                it.arbitraryValue eq expected.arbitraryValue,
                it.integerValue eq expected.integerValue,
                it.floatValue eq expected.floatValue,
                !it.expired()
            )
        }

        return if (new == null) table.deleteOneIgnoringOld(equalToExpected)
        else {
            val updated = table.upsertOne(
                equalToExpected,
                Modification.Nothing(),
                Entry(key, serializer, new, timeToLive)
            ).new

            updated != null && updated == new
        }
    }

    override suspend fun remove(key: String) {
        table.deleteOneById(key)
    }
}