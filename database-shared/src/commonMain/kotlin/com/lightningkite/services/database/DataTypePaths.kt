package com.lightningkite.services.database

import com.lightningkite.services.data.*
import kotlinx.serialization.KSerializer
import kotlin.jvm.JvmName

// What @GenerateDataClassPaths would emit for data-shared's value classes. data-shared can't run the
// generator itself, because the generated code needs this module.

@Suppress("UNCHECKED_CAST")
private fun <T, V> KSerializer<T>.wrapped(): SerializableProperty<T, V> =
    serializableProperties!!.single() as SerializableProperty<T, V>

@get:JvmName("pathIsRawString_raw") public val <K, T : IsRawString> DataClassPath<K, T>.raw: DataClassPath<K, String> get() = this[serializer.wrapped()]
@get:JvmName("pathUuidV7_raw") public val <K> DataClassPath<K, UuidV7>.raw: DataClassPath<K, kotlin.uuid.Uuid> get() = this[UuidV7.serializer().wrapped()]

public val <K> DataClassPath<K, DataSize>.bytes: DataClassPath<K, Long> get() = this[DataSize.serializer().wrapped()]
public val <K> DataClassPath<K, Length>.meters: DataClassPath<K, Double> get() = this[Length.serializer().wrapped()]
public val <K> DataClassPath<K, Area>.squareMeters: DataClassPath<K, Double> get() = this[Area.serializer().wrapped()]
public val <K> DataClassPath<K, Volume>.cubicMeters: DataClassPath<K, Double> get() = this[Volume.serializer().wrapped()]
public val <K> DataClassPath<K, Mass>.kilograms: DataClassPath<K, Double> get() = this[Mass.serializer().wrapped()]
public val <K> DataClassPath<K, Speed>.metersPerSecond: DataClassPath<K, Double> get() = this[Speed.serializer().wrapped()]
public val <K> DataClassPath<K, Acceleration>.metersPerSecondPerSecond: DataClassPath<K, Double> get() = this[Acceleration.serializer().wrapped()]
public val <K> DataClassPath<K, Force>.newtons: DataClassPath<K, Double> get() = this[Force.serializer().wrapped()]
public val <K> DataClassPath<K, Pressure>.pascals: DataClassPath<K, Double> get() = this[Pressure.serializer().wrapped()]
public val <K> DataClassPath<K, Energy>.joules: DataClassPath<K, Double> get() = this[Energy.serializer().wrapped()]
public val <K> DataClassPath<K, Power>.watts: DataClassPath<K, Double> get() = this[Power.serializer().wrapped()]
@get:JvmName("pathTemperature_celsius") public val <K> DataClassPath<K, Temperature>.celsius: DataClassPath<K, Double> get() = this[Temperature.serializer().wrapped()]
@get:JvmName("pathRelativeTemperature_celsius") public val <K> DataClassPath<K, RelativeTemperature>.celsius: DataClassPath<K, Double> get() = this[RelativeTemperature.serializer().wrapped()]
