package com.lightningkite.services.database.path

import com.lightningkite.services.data.*
import com.lightningkite.services.database.path.Field
import kotlinx.serialization.KSerializer
import kotlin.jvm.JvmName

// Claude: written by hand because these value classes live in data-shared, which can't run the database processor:
// the generated code needs database-shared, which depends on data-shared.
// Each has a Lens overload too, so a path of fields stays statically a Lens.

@Suppress("UNCHECKED_CAST")
private fun <T, V> KSerializer<T>.wrapped(): Field<T, V> =
    fields!!.single() as Field<T, V>

@get:JvmName("pathIsRawString_raw") public val <K, T : IsRawString> Optic<K, T>.raw: Optic<K, String> get() = this + serializer.wrapped()
@get:JvmName("pathUuidV7_raw") public val <K> Optic<K, UuidV7>.raw: Optic<K, kotlin.uuid.Uuid> get() = this + UuidV7.serializer().wrapped()
public val <K> Optic<K, DataSize>.bytes: Optic<K, Long> get() = this + DataSize.serializer().wrapped()
public val <K> Optic<K, Length>.meters: Optic<K, Double> get() = this + Length.serializer().wrapped()
public val <K> Optic<K, Area>.squareMeters: Optic<K, Double> get() = this + Area.serializer().wrapped()
public val <K> Optic<K, Volume>.cubicMeters: Optic<K, Double> get() = this + Volume.serializer().wrapped()
public val <K> Optic<K, Mass>.kilograms: Optic<K, Double> get() = this + Mass.serializer().wrapped()
public val <K> Optic<K, Speed>.metersPerSecond: Optic<K, Double> get() = this + Speed.serializer().wrapped()
public val <K> Optic<K, Acceleration>.metersPerSecondPerSecond: Optic<K, Double> get() = this + Acceleration.serializer().wrapped()
public val <K> Optic<K, Force>.newtons: Optic<K, Double> get() = this + Force.serializer().wrapped()
public val <K> Optic<K, Pressure>.pascals: Optic<K, Double> get() = this + Pressure.serializer().wrapped()
public val <K> Optic<K, Energy>.joules: Optic<K, Double> get() = this + Energy.serializer().wrapped()
public val <K> Optic<K, Power>.watts: Optic<K, Double> get() = this + Power.serializer().wrapped()
@get:JvmName("pathTemperature_celsius") public val <K> Optic<K, Temperature>.celsius: Optic<K, Double> get() = this + Temperature.serializer().wrapped()
@get:JvmName("pathRelativeTemperature_celsius") public val <K> Optic<K, RelativeTemperature>.celsius: Optic<K, Double> get() = this + RelativeTemperature.serializer().wrapped()

@get:JvmName("lensIsRawString_raw") public val <K, T : IsRawString> Lens<K, T>.raw: Lens<K, String> get() = this + serializer.wrapped()
@get:JvmName("lensUuidV7_raw") public val <K> Lens<K, UuidV7>.raw: Lens<K, kotlin.uuid.Uuid> get() = this + UuidV7.serializer().wrapped()
public val <K> Lens<K, DataSize>.bytes: Lens<K, Long> get() = this + DataSize.serializer().wrapped()
public val <K> Lens<K, Length>.meters: Lens<K, Double> get() = this + Length.serializer().wrapped()
public val <K> Lens<K, Area>.squareMeters: Lens<K, Double> get() = this + Area.serializer().wrapped()
public val <K> Lens<K, Volume>.cubicMeters: Lens<K, Double> get() = this + Volume.serializer().wrapped()
public val <K> Lens<K, Mass>.kilograms: Lens<K, Double> get() = this + Mass.serializer().wrapped()
public val <K> Lens<K, Speed>.metersPerSecond: Lens<K, Double> get() = this + Speed.serializer().wrapped()
public val <K> Lens<K, Acceleration>.metersPerSecondPerSecond: Lens<K, Double> get() = this + Acceleration.serializer().wrapped()
public val <K> Lens<K, Force>.newtons: Lens<K, Double> get() = this + Force.serializer().wrapped()
public val <K> Lens<K, Pressure>.pascals: Lens<K, Double> get() = this + Pressure.serializer().wrapped()
public val <K> Lens<K, Energy>.joules: Lens<K, Double> get() = this + Energy.serializer().wrapped()
public val <K> Lens<K, Power>.watts: Lens<K, Double> get() = this + Power.serializer().wrapped()
@get:JvmName("lensTemperature_celsius") public val <K> Lens<K, Temperature>.celsius: Lens<K, Double> get() = this + Temperature.serializer().wrapped()
@get:JvmName("lensRelativeTemperature_celsius") public val <K> Lens<K, RelativeTemperature>.celsius: Lens<K, Double> get() = this + RelativeTemperature.serializer().wrapped()
