package com.lightningkite.services.database.sqlite

import androidx.sqlite.SQLiteStatement
import com.lightningkite.services.database.mapformat.ValueConverter
import com.lightningkite.services.database.mapformat.ValueConverterRegistry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal enum class ColumnType(val declaration: String) {
    Boolean("INTEGER"), Integer("INTEGER"), Real("REAL"), Text("TEXT"), Blob("BLOB")
}

private class Converter<K : Any, D : Any>(
    override val descriptor: SerialDescriptor,
    val type: ColumnType,
    private val to: (K) -> D,
    private val from: (D) -> K,
) : ValueConverter<K, D> {
    override fun toDatabase(value: K): D = to(value)
    override fun fromDatabase(value: D): K = from(value)
}

// Each shift is the most negative value the type can hold, so the stored numbers are never negative
// and zero-padded text sorts in time order - including Instant.DISTANT_PAST and DISTANT_FUTURE.
private const val INSTANT_SECONDS_SHIFT = 31_557_014_167_219_200L
private const val EPOCH_DAYS_SHIFT = 400_000_000_000L

private val converters: List<Converter<*, *>> = listOf(
    Converter<Uuid, ByteArray>(Uuid.serializer().descriptor, ColumnType.Blob, { it.toByteArray() }, { Uuid.fromByteArray(it) }),
    Converter<Instant, String>(
        Instant.serializer().descriptor, ColumnType.Text,
        to = { (it.epochSeconds + INSTANT_SECONDS_SHIFT).toString().padStart(17, '0') + "." + it.nanosecondsOfSecond.toString().padStart(9, '0') },
        from = {
            val (seconds, nanos) = it.split('.')
            Instant.fromEpochSeconds(seconds.toLong() - INSTANT_SECONDS_SHIFT, nanos.toInt())
        },
    ),
    Converter<LocalDate, Long>(LocalDate.serializer().descriptor, ColumnType.Integer, { it.toEpochDays() }, { LocalDate.fromEpochDays(it) }),
    Converter<LocalTime, Long>(LocalTime.serializer().descriptor, ColumnType.Integer, { it.toNanosecondOfDay() }, { LocalTime.fromNanosecondOfDay(it) }),
    Converter<LocalDateTime, String>(
        LocalDateTime.serializer().descriptor, ColumnType.Text,
        to = { (it.date.toEpochDays() + EPOCH_DAYS_SHIFT).toString().padStart(12, '0') + "." + it.time.toNanosecondOfDay().toString().padStart(14, '0') },
        from = {
            val (days, nanos) = it.split('.')
            LocalDateTime(LocalDate.fromEpochDays(days.toLong() - EPOCH_DAYS_SHIFT), LocalTime.fromNanosecondOfDay(nanos.toLong()))
        },
    ),
    // Milliseconds as a Double, like the MongoDB driver: it holds INFINITE, and survives the web
    // driver, which passes integers through JavaScript numbers.
    Converter<Duration, Double>(
        Duration.serializer().descriptor, ColumnType.Real,
        to = { it.toDouble(DurationUnit.MILLISECONDS) },
        from = { it.milliseconds },
    ),
    Converter<ByteArray, ByteArray>(ByteArraySerializer().descriptor, ColumnType.Blob, { it }, { it }),
)

internal val sqliteConverters: ValueConverterRegistry = ValueConverterRegistry(converters)

/** The column type for a descriptor that is stored in a single column. */
@OptIn(ExperimentalSerializationApi::class)
internal fun columnTypeOf(descriptor: SerialDescriptor): ColumnType {
    (sqliteConverters.get(descriptor) as? Converter<*, *>)?.let { return it.type }
    return when (descriptor.kind) {
        PrimitiveKind.BOOLEAN -> ColumnType.Boolean
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> ColumnType.Integer
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> ColumnType.Real
        else -> ColumnType.Text
    }
}

internal fun SQLiteStatement.bindAll(args: List<Any?>) {
    args.forEachIndexed { i, value ->
        val index = i + 1
        when (value) {
            null -> bindNull(index)
            is Boolean -> bindLong(index, if (value) 1L else 0L)
            is Float -> bindDouble(index, value.toDouble())
            is Double -> bindDouble(index, value)
            is Number -> bindLong(index, value.toLong())
            is String -> bindText(index, value)
            is ByteArray -> bindBlob(index, value)
            else -> throw IllegalArgumentException("Can't store a ${value::class} in SQLite")
        }
    }
}

internal fun SQLiteStatement.read(index: Int, type: ColumnType): Any? =
    if (isNull(index)) null
    else when (type) {
        ColumnType.Boolean -> getLong(index) != 0L
        ColumnType.Integer -> getLong(index)
        ColumnType.Real -> getDouble(index)
        ColumnType.Text -> getText(index)
        ColumnType.Blob -> getBlob(index)
    }
