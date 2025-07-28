package com.lightningkite

import com.lightningkite.Length.Companion.feet
import com.lightningkite.Length.Companion.yards
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit



@JvmInline
@Serializable
public value class Length(public val meters: Double): Comparable<Length> {
    override fun compareTo(other: Length): Int = this.meters.compareTo(other.meters)
    public val astronomicalUnits: Double get() = meters / 1.495978707E11
    public val centimeters: Double get() = meters / 0.01
    public val feet: Double get() = meters / 0.3048
    public val inches: Double get() = meters / 0.025400000000000002
    public val kilometers: Double get() = meters / 1000.0
    public val lightYears: Double get() = meters / 9.4607304725808E15
    public val micrometer: Double get() = meters / 1.0E-6
    public val miles: Double get() = meters / 1609.344
    public val millimeter: Double get() = meters / 0.001
    public val nanometer: Double get() = meters / 1.0E-9
    public val yards: Double get() = meters / 0.9144
    public operator fun plus(other: Length): Length = Length(meters + other.meters)
    public operator fun minus(other: Length): Length = Length(meters - other.meters)
    public operator fun times(ratio: Double): Length = Length(meters * ratio)
    public operator fun times(ratio: Int): Length = Length(meters * ratio)
    public operator fun div(ratio: Double): Length = Length(meters / ratio)
    public operator fun div(ratio: Int): Length = Length(meters / ratio)

    public companion object {
        public val Int.astronomicalUnits: Length get() = Length(meters = this * 1.495978707E11)
        public val Long.astronomicalUnits: Length get() = Length(meters = this * 1.495978707E11)
        public val Double.astronomicalUnits: Length get() = Length(meters = this * 1.495978707E11)
        public val Int.centimeters: Length get() = Length(meters = this * 0.01)
        public val Long.centimeters: Length get() = Length(meters = this * 0.01)
        public val Double.centimeters: Length get() = Length(meters = this * 0.01)
        public val Int.feet: Length get() = Length(meters = this * 0.3048)
        public val Long.feet: Length get() = Length(meters = this * 0.3048)
        public val Double.feet: Length get() = Length(meters = this * 0.3048)
        public val Int.inches: Length get() = Length(meters = this * 0.025400000000000002)
        public val Long.inches: Length get() = Length(meters = this * 0.025400000000000002)
        public val Double.inches: Length get() = Length(meters = this * 0.025400000000000002)
        public val Int.kilometers: Length get() = Length(meters = this * 1000.0)
        public val Long.kilometers: Length get() = Length(meters = this * 1000.0)
        public val Double.kilometers: Length get() = Length(meters = this * 1000.0)
        public val Int.lightYears: Length get() = Length(meters = this * 9.4607304725808E15)
        public val Long.lightYears: Length get() = Length(meters = this * 9.4607304725808E15)
        public val Double.lightYears: Length get() = Length(meters = this * 9.4607304725808E15)
        public val Int.meters: Length get() = Length(meters = this * 1.0)
        public val Long.meters: Length get() = Length(meters = this * 1.0)
        public val Double.meters: Length get() = Length(meters = this * 1.0)
        public val Int.micrometer: Length get() = Length(meters = this * 1.0E-6)
        public val Long.micrometer: Length get() = Length(meters = this * 1.0E-6)
        public val Double.micrometer: Length get() = Length(meters = this * 1.0E-6)
        public val Int.miles: Length get() = Length(meters = this * 1609.344)
        public val Long.miles: Length get() = Length(meters = this * 1609.344)
        public val Double.miles: Length get() = Length(meters = this * 1609.344)
        public val Int.millimeter: Length get() = Length(meters = this * 0.001)
        public val Long.millimeter: Length get() = Length(meters = this * 0.001)
        public val Double.millimeter: Length get() = Length(meters = this * 0.001)
        public val Int.nanometer: Length get() = Length(meters = this * 1.0E-9)
        public val Long.nanometer: Length get() = Length(meters = this * 1.0E-9)
        public val Double.nanometer: Length get() = Length(meters = this * 1.0E-9)
        public val Int.yards: Length get() = Length(meters = this * 0.9144)
        public val Long.yards: Length get() = Length(meters = this * 0.9144)
        public val Double.yards: Length get() = Length(meters = this * 0.9144)
    }
    override fun toString(): String = toStringMetric()
    public fun toStringMetric(): String = when(meters) {
        in 0.0..<0.0000001 -> "$nanometer nm"
        in 0.0000001..<0.00001 -> "$micrometer mm"
        in 0.0001..<0.01 -> "$millimeter mm"
        in 0.01..<1.0 -> "$centimeters cm"
        in 1.0..<1000.0 -> "$meters m"
        in 1000.0..<9.4607304725808E13 -> "$kilometers km"
        else -> "$lightYears ly"
    }
    public fun toStringImperial(): String = when(meters) {
        in 0.0..<2.feet.meters -> "$inches in"
        in 1.feet.meters..<5.yards.meters -> "$feet ft"
        in 5.yards.meters..<600.yards.meters -> "$feet yd"
        in 600.yards.meters..<9.4607304725808E13 -> "$miles mi"
        else -> "$lightYears ly"
    }
}

@JvmInline
@Serializable
public value class Area(public val squareMeters: Double): Comparable<Area> {
    override fun compareTo(other: Area): Int = this.squareMeters.compareTo(other.squareMeters)
    public val acres: Double get() = squareMeters / 4046.8564224
    public val hectare: Double get() = squareMeters / 10000.0
    public val squareKilometers: Double get() = squareMeters / 1000000.0
    public val squareMillimeters: Double get() = squareMeters / 1.0E-6
    public val squareCentimeters: Double get() = squareMeters / 1.0E-4
    public operator fun plus(other: Area): Area = Area(squareMeters + other.squareMeters)
    public operator fun minus(other: Area): Area = Area(squareMeters - other.squareMeters)
    public operator fun times(ratio: Double): Area = Area(squareMeters * ratio)
    public operator fun times(ratio: Int): Area = Area(squareMeters * ratio)
    public operator fun div(ratio: Double): Area = Area(squareMeters / ratio)
    public operator fun div(ratio: Int): Area = Area(squareMeters / ratio)

    public companion object {
        public val Int.acres: Area get() = Area(squareMeters = this * 4046.8564224)
        public val Long.acres: Area get() = Area(squareMeters = this * 4046.8564224)
        public val Double.acres: Area get() = Area(squareMeters = this * 4046.8564224)
        public val Int.hectare: Area get() = Area(squareMeters = this * 10000.0)
        public val Long.hectare: Area get() = Area(squareMeters = this * 10000.0)
        public val Double.hectare: Area get() = Area(squareMeters = this * 10000.0)
        public val Int.squareKilometers: Area get() = Area(squareMeters = this * 1000000.0)
        public val Long.squareKilometers: Area get() = Area(squareMeters = this * 1000000.0)
        public val Double.squareKilometers: Area get() = Area(squareMeters = this * 1000000.0)
        public val Int.squareMeters: Area get() = Area(squareMeters = this * 1.0)
        public val Long.squareMeters: Area get() = Area(squareMeters = this * 1.0)
        public val Double.squareMeters: Area get() = Area(squareMeters = this * 1.0)
        public val Int.squareCentimeters: Area get() = Area(squareMeters = this * 1.0E-4)
        public val Long.squareCentimeters: Area get() = Area(squareMeters = this * 1.0E-4)
        public val Double.squareCentimeters: Area get() = Area(squareMeters = this * 1.0E-4)
        public val Int.squareMillimeters: Area get() = Area(squareMeters = this * 1.0E-6)
        public val Long.squareMillimeters: Area get() = Area(squareMeters = this * 1.0E-6)
        public val Double.squareMillimeters: Area get() = Area(squareMeters = this * 1.0E-6)
    }
    public override fun toString(): String = "$squareMeters m^2"
}

@JvmInline
@Serializable
public value class Volume(public val cubicMeters: Double): Comparable<Volume> {
    public override fun compareTo(other: Volume): Int = this.cubicMeters.compareTo(other.cubicMeters)
    public val cubicFeet: Double get() = cubicMeters / 0.028316846592000004
    public val cubicKilometers: Double get() = cubicMeters / 1.0E9
    public val cubicYards: Double get() = cubicMeters / 0.764554857984
    public val cups: Double get() = cubicMeters / 2.365882365000001E-4
    public val gallons: Double get() = cubicMeters / 0.0037854117840000014
    public val liquidOunces: Double get() = cubicMeters / 2.957352956250001E-5
    public val liters: Double get() = cubicMeters / 0.001
    public val milliliters: Double get() = cubicMeters / 1.0E-6
    public val pints: Double get() = cubicMeters / 4.731764730000002E-4
    public val quarts: Double get() = cubicMeters / 9.463529460000004E-4
    public val tablespoons: Double get() = cubicMeters / 1.4786764781250006E-5
    public val teaspoons: Double get() = cubicMeters / 4.928921593750002E-6
    public operator fun plus(other: Volume): Volume = Volume(cubicMeters + other.cubicMeters)
    public operator fun minus(other: Volume): Volume = Volume(cubicMeters - other.cubicMeters)
    public operator fun times(ratio: Double): Volume = Volume(cubicMeters * ratio)
    public operator fun times(ratio: Int): Volume = Volume(cubicMeters * ratio)
    public operator fun div(ratio: Double): Volume = Volume(cubicMeters / ratio)
    public operator fun div(ratio: Int): Volume = Volume(cubicMeters / ratio)

    public companion object {
        public val Int.cubicFeet: Volume get() = Volume(cubicMeters = this * 0.028316846592000004)
        public val Long.cubicFeet: Volume get() = Volume(cubicMeters = this * 0.028316846592000004)
        public val Double.cubicFeet: Volume get() = Volume(cubicMeters = this * 0.028316846592000004)
        public val Int.cubicKilometers: Volume get() = Volume(cubicMeters = this * 1.0E9)
        public val Long.cubicKilometers: Volume get() = Volume(cubicMeters = this * 1.0E9)
        public val Double.cubicKilometers: Volume get() = Volume(cubicMeters = this * 1.0E9)
        public val Int.cubicMeters: Volume get() = Volume(cubicMeters = this * 1.0)
        public val Long.cubicMeters: Volume get() = Volume(cubicMeters = this * 1.0)
        public val Double.cubicMeters: Volume get() = Volume(cubicMeters = this * 1.0)
        public val Int.cubicYards: Volume get() = Volume(cubicMeters = this * 0.764554857984)
        public val Long.cubicYards: Volume get() = Volume(cubicMeters = this * 0.764554857984)
        public val Double.cubicYards: Volume get() = Volume(cubicMeters = this * 0.764554857984)
        public val Int.cups: Volume get() = Volume(cubicMeters = this * 2.365882365000001E-4)
        public val Long.cups: Volume get() = Volume(cubicMeters = this * 2.365882365000001E-4)
        public val Double.cups: Volume get() = Volume(cubicMeters = this * 2.365882365000001E-4)
        public val Int.gallons: Volume get() = Volume(cubicMeters = this * 0.0037854117840000014)
        public val Long.gallons: Volume get() = Volume(cubicMeters = this * 0.0037854117840000014)
        public val Double.gallons: Volume get() = Volume(cubicMeters = this * 0.0037854117840000014)
        public val Int.liquidOunces: Volume get() = Volume(cubicMeters = this * 2.957352956250001E-5)
        public val Long.liquidOunces: Volume get() = Volume(cubicMeters = this * 2.957352956250001E-5)
        public val Double.liquidOunces: Volume get() = Volume(cubicMeters = this * 2.957352956250001E-5)
        public val Int.liters: Volume get() = Volume(cubicMeters = this * 0.001)
        public val Long.liters: Volume get() = Volume(cubicMeters = this * 0.001)
        public val Double.liters: Volume get() = Volume(cubicMeters = this * 0.001)
        public val Int.milliliters: Volume get() = Volume(cubicMeters = this * 1.0E-6)
        public val Long.milliliters: Volume get() = Volume(cubicMeters = this * 1.0E-6)
        public val Double.milliliters: Volume get() = Volume(cubicMeters = this * 1.0E-6)
        public val Int.pints: Volume get() = Volume(cubicMeters = this * 4.731764730000002E-4)
        public val Long.pints: Volume get() = Volume(cubicMeters = this * 4.731764730000002E-4)
        public val Double.pints: Volume get() = Volume(cubicMeters = this * 4.731764730000002E-4)
        public val Int.quarts: Volume get() = Volume(cubicMeters = this * 9.463529460000004E-4)
        public val Long.quarts: Volume get() = Volume(cubicMeters = this * 9.463529460000004E-4)
        public val Double.quarts: Volume get() = Volume(cubicMeters = this * 9.463529460000004E-4)
        public val Int.tablespoons: Volume get() = Volume(cubicMeters = this * 1.4786764781250006E-5)
        public val Long.tablespoons: Volume get() = Volume(cubicMeters = this * 1.4786764781250006E-5)
        public val Double.tablespoons: Volume get() = Volume(cubicMeters = this * 1.4786764781250006E-5)
        public val Int.teaspoons: Volume get() = Volume(cubicMeters = this * 4.928921593750002E-6)
        public val Long.teaspoons: Volume get() = Volume(cubicMeters = this * 4.928921593750002E-6)
        public val Double.teaspoons: Volume get() = Volume(cubicMeters = this * 4.928921593750002E-6)
    }
    public override fun toString(): String = "$cubicMeters m³"
}

@JvmInline
@Serializable
public value class Mass(public val kilograms: Double): Comparable<Mass> {
    public override fun compareTo(other: Mass): Int = this.kilograms.compareTo(other.kilograms)
    public val grains: Double get() = kilograms / 6.479891000000001E-5
    public val grams: Double get() = kilograms / 0.001
    public val milligrams: Double get() = kilograms / 1.0E-6
    public val pounds: Double get() = kilograms / 0.45359237
    public val tonnes: Double get() = kilograms / 1000.0
    public val tons: Double get() = kilograms / 907.18474
    public val weightOunces: Double get() = kilograms / 0.028349523125
    public operator fun plus(other: Mass): Mass = Mass(kilograms + other.kilograms)
    public operator fun minus(other: Mass): Mass = Mass(kilograms - other.kilograms)
    public operator fun times(ratio: Double): Mass = Mass(kilograms * ratio)
    public operator fun times(ratio: Int): Mass = Mass(kilograms * ratio)
    public operator fun div(ratio: Double): Mass = Mass(kilograms / ratio)
    public operator fun div(ratio: Int): Mass = Mass(kilograms / ratio)

    public companion object {
        public val Int.grains: Mass get() = Mass(kilograms = this * 6.479891000000001E-5)
        public val Long.grains: Mass get() = Mass(kilograms = this * 6.479891000000001E-5)
        public val Double.grains: Mass get() = Mass(kilograms = this * 6.479891000000001E-5)
        public val Int.grams: Mass get() = Mass(kilograms = this * 0.001)
        public val Long.grams: Mass get() = Mass(kilograms = this * 0.001)
        public val Double.grams: Mass get() = Mass(kilograms = this * 0.001)
        public val Int.kilograms: Mass get() = Mass(kilograms = this * 1.0)
        public val Long.kilograms: Mass get() = Mass(kilograms = this * 1.0)
        public val Double.kilograms: Mass get() = Mass(kilograms = this * 1.0)
        public val Int.milligrams: Mass get() = Mass(kilograms = this * 1.0E-6)
        public val Long.milligrams: Mass get() = Mass(kilograms = this * 1.0E-6)
        public val Double.milligrams: Mass get() = Mass(kilograms = this * 1.0E-6)
        public val Int.pounds: Mass get() = Mass(kilograms = this * 0.45359237)
        public val Long.pounds: Mass get() = Mass(kilograms = this * 0.45359237)
        public val Double.pounds: Mass get() = Mass(kilograms = this * 0.45359237)
        public val Int.tonnes: Mass get() = Mass(kilograms = this * 1000.0)
        public val Long.tonnes: Mass get() = Mass(kilograms = this * 1000.0)
        public val Double.tonnes: Mass get() = Mass(kilograms = this * 1000.0)
        public val Int.tons: Mass get() = Mass(kilograms = this * 907.18474)
        public val Long.tons: Mass get() = Mass(kilograms = this * 907.18474)
        public val Double.tons: Mass get() = Mass(kilograms = this * 907.18474)
        public val Int.weightOunces: Mass get() = Mass(kilograms = this * 0.028349523125)
        public val Long.weightOunces: Mass get() = Mass(kilograms = this * 0.028349523125)
        public val Double.weightOunces: Mass get() = Mass(kilograms = this * 0.028349523125)
    }
    public override fun toString(): String = "$kilograms kg"
}

@JvmInline
@Serializable
public value class Speed(public val metersPerSecond: Double): Comparable<Speed> {
    public override fun compareTo(other: Speed): Int = this.metersPerSecond.compareTo(other.metersPerSecond)
    public val feetPerSecond: Double get() = metersPerSecond / 0.3048
    public val kilometersPerHour: Double get() = metersPerSecond / 0.2777777777777778
    public val milesPerHour: Double get() = metersPerSecond / 0.44704
    public operator fun plus(other: Speed): Speed = Speed(metersPerSecond + other.metersPerSecond)
    public operator fun minus(other: Speed): Speed = Speed(metersPerSecond - other.metersPerSecond)
    public operator fun times(ratio: Double): Speed = Speed(metersPerSecond * ratio)
    public operator fun times(ratio: Int): Speed = Speed(metersPerSecond * ratio)
    public operator fun div(ratio: Double): Speed = Speed(metersPerSecond / ratio)
    public operator fun div(ratio: Int): Speed = Speed(metersPerSecond / ratio)

    public companion object {
        public val Int.feetPerSecond: Speed get() = Speed(metersPerSecond = this * 0.3048)
        public val Long.feetPerSecond: Speed get() = Speed(metersPerSecond = this * 0.3048)
        public val Double.feetPerSecond: Speed get() = Speed(metersPerSecond = this * 0.3048)
        public val Int.kilometersPerHour: Speed get() = Speed(metersPerSecond = this * 0.2777777777777778)
        public val Long.kilometersPerHour: Speed get() = Speed(metersPerSecond = this * 0.2777777777777778)
        public val Double.kilometersPerHour: Speed get() = Speed(metersPerSecond = this * 0.2777777777777778)
        public val Int.metersPerSecond: Speed get() = Speed(metersPerSecond = this * 1.0)
        public val Long.metersPerSecond: Speed get() = Speed(metersPerSecond = this * 1.0)
        public val Double.metersPerSecond: Speed get() = Speed(metersPerSecond = this * 1.0)
        public val Int.milesPerHour: Speed get() = Speed(metersPerSecond = this * 0.44704)
        public val Long.milesPerHour: Speed get() = Speed(metersPerSecond = this * 0.44704)
        public val Double.milesPerHour: Speed get() = Speed(metersPerSecond = this * 0.44704)
    }
    public override fun toString(): String = "$metersPerSecond m/s"
}

@JvmInline
@Serializable
public value class Acceleration(public val metersPerSecondPerSecond: Double): Comparable<Acceleration> {
    public override fun compareTo(other: Acceleration): Int = this.metersPerSecondPerSecond.compareTo(other.metersPerSecondPerSecond)
    public val feetPerSecondPerSecond: Double get() = metersPerSecondPerSecond / 0.3048
    public val kilometersPerHourPerSecond: Double get() = metersPerSecondPerSecond / 0.2777777777777778
    public val milesPerHourPerSecond: Double get() = metersPerSecondPerSecond / 0.44704
    public operator fun plus(other: Acceleration): Acceleration = Acceleration(metersPerSecondPerSecond + other.metersPerSecondPerSecond)
    public operator fun minus(other: Acceleration): Acceleration = Acceleration(metersPerSecondPerSecond - other.metersPerSecondPerSecond)
    public operator fun times(ratio: Double): Acceleration = Acceleration(metersPerSecondPerSecond * ratio)
    public operator fun times(ratio: Int): Acceleration = Acceleration(metersPerSecondPerSecond * ratio)
    public operator fun div(ratio: Double): Acceleration = Acceleration(metersPerSecondPerSecond / ratio)
    public operator fun div(ratio: Int): Acceleration = Acceleration(metersPerSecondPerSecond / ratio)

    public companion object {
        public val Int.feetPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.3048)
        public val Long.feetPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.3048)
        public val Double.feetPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.3048)
        public val Int.kilometersPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.2777777777777778)
        public val Long.kilometersPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.2777777777777778)
        public val Double.kilometersPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.2777777777777778)
        public val Int.metersPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 1.0)
        public val Long.metersPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 1.0)
        public val Double.metersPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 1.0)
        public val Int.milesPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.44704)
        public val Long.milesPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.44704)
        public val Double.milesPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.44704)
    }
    public override fun toString(): String = "$metersPerSecondPerSecond m/s²"
}

@JvmInline
@Serializable
public value class Force(public val newtons: Double): Comparable<Force> {
    public override fun compareTo(other: Force): Int = this.newtons.compareTo(other.newtons)
    public val poundForce: Double get() = newtons / 4.448222
    public operator fun plus(other: Force): Force = Force(newtons + other.newtons)
    public operator fun minus(other: Force): Force = Force(newtons - other.newtons)
    public operator fun times(ratio: Double): Force = Force(newtons * ratio)
    public operator fun times(ratio: Int): Force = Force(newtons * ratio)
    public operator fun div(ratio: Double): Force = Force(newtons / ratio)
    public operator fun div(ratio: Int): Force = Force(newtons / ratio)

    public companion object {
        public val Int.newtons: Force get() = Force(newtons = this * 1.0)
        public val Long.newtons: Force get() = Force(newtons = this * 1.0)
        public val Double.newtons: Force get() = Force(newtons = this * 1.0)
        public val Int.poundForce: Force get() = Force(newtons = this * 4.448222)
        public val Long.poundForce: Force get() = Force(newtons = this * 4.448222)
        public val Double.poundForce: Force get() = Force(newtons = this * 4.448222)
    }
    public override fun toString(): String = "$newtons N"
}

@JvmInline
@Serializable
public value class Pressure(public val pascals: Double): Comparable<Pressure> {
    public override fun compareTo(other: Pressure): Int = this.pascals.compareTo(other.pascals)
    public val atmospheres: Double get() = pascals / 101325.0
    public val bars: Double get() = pascals / 100000.0
    public val millibars: Double get() = pascals / 100.0
    public val psi: Double get() = pascals / 6894.757889515778
    public operator fun plus(other: Pressure): Pressure = Pressure(pascals + other.pascals)
    public operator fun minus(other: Pressure): Pressure = Pressure(pascals - other.pascals)
    public operator fun times(ratio: Double): Pressure = Pressure(pascals * ratio)
    public operator fun times(ratio: Int): Pressure = Pressure(pascals * ratio)
    public operator fun div(ratio: Double): Pressure = Pressure(pascals / ratio)
    public operator fun div(ratio: Int): Pressure = Pressure(pascals / ratio)

    public companion object {
        public val Int.atmospheres: Pressure get() = Pressure(pascals = this * 101325.0)
        public val Long.atmospheres: Pressure get() = Pressure(pascals = this * 101325.0)
        public val Double.atmospheres: Pressure get() = Pressure(pascals = this * 101325.0)
        public val Int.bars: Pressure get() = Pressure(pascals = this * 100000.0)
        public val Long.bars: Pressure get() = Pressure(pascals = this * 100000.0)
        public val Double.bars: Pressure get() = Pressure(pascals = this * 100000.0)
        public val Int.millibars: Pressure get() = Pressure(pascals = this * 100.0)
        public val Long.millibars: Pressure get() = Pressure(pascals = this * 100.0)
        public val Double.millibars: Pressure get() = Pressure(pascals = this * 100.0)
        public val Int.pascals: Pressure get() = Pressure(pascals = this * 1.0)
        public val Long.pascals: Pressure get() = Pressure(pascals = this * 1.0)
        public val Double.pascals: Pressure get() = Pressure(pascals = this * 1.0)
        public val Int.psi: Pressure get() = Pressure(pascals = this * 6894.757889515778)
        public val Long.psi: Pressure get() = Pressure(pascals = this * 6894.757889515778)
        public val Double.psi: Pressure get() = Pressure(pascals = this * 6894.757889515778)
    }
    public override fun toString(): String = "$pascals Pa"
}

@JvmInline
@Serializable
public value class Energy(public val joules: Double): Comparable<Energy> {
    public override fun compareTo(other: Energy): Int = this.joules.compareTo(other.joules)
    public val btus: Double get() = joules / 9.484516526770049E-4
    public val kcal: Double get() = joules / 4.184
    public operator fun plus(other: Energy): Energy = Energy(joules + other.joules)
    public operator fun minus(other: Energy): Energy = Energy(joules - other.joules)
    public operator fun times(ratio: Double): Energy = Energy(joules * ratio)
    public operator fun times(ratio: Int): Energy = Energy(joules * ratio)
    public operator fun div(ratio: Double): Energy = Energy(joules / ratio)
    public operator fun div(ratio: Int): Energy = Energy(joules / ratio)

    public companion object {
        public val Int.btus: Energy get() = Energy(joules = this * 9.484516526770049E-4)
        public val Long.btus: Energy get() = Energy(joules = this * 9.484516526770049E-4)
        public val Double.btus: Energy get() = Energy(joules = this * 9.484516526770049E-4)
        public val Int.joules: Energy get() = Energy(joules = this * 1.0)
        public val Long.joules: Energy get() = Energy(joules = this * 1.0)
        public val Double.joules: Energy get() = Energy(joules = this * 1.0)
        public val Int.kcal: Energy get() = Energy(joules = this * 4.184)
        public val Long.kcal: Energy get() = Energy(joules = this * 4.184)
        public val Double.kcal: Energy get() = Energy(joules = this * 4.184)
    }
    public override fun toString(): String = "$joules J"
}

@JvmInline
@Serializable
public value class Power(public val watts: Double): Comparable<Power> {
    public override fun compareTo(other: Power): Int = this.watts.compareTo(other.watts)
    public val kilowatts: Double get() = watts / 1000.0
    public operator fun plus(other: Power): Power = Power(watts + other.watts)
    public operator fun minus(other: Power): Power = Power(watts - other.watts)
    public operator fun times(ratio: Double): Power = Power(watts * ratio)
    public operator fun times(ratio: Int): Power = Power(watts * ratio)
    public operator fun div(ratio: Double): Power = Power(watts / ratio)
    public operator fun div(ratio: Int): Power = Power(watts / ratio)

    public companion object {
        public val Int.kilowatts: Power get() = Power(watts = this * 1000.0)
        public val Long.kilowatts: Power get() = Power(watts = this * 1000.0)
        public val Double.kilowatts: Power get() = Power(watts = this * 1000.0)
        public val Int.watts: Power get() = Power(watts = this * 1.0)
        public val Long.watts: Power get() = Power(watts = this * 1.0)
        public val Double.watts: Power get() = Power(watts = this * 1.0)
    }
    public override fun toString(): String = "$watts W"
}

@JvmInline
@Serializable
public value class Temperature(public val celsius: Double): Comparable<Temperature> {
    public override fun compareTo(other: Temperature): Int = this.celsius.compareTo(other.celsius)
    public val fahrenheit: Double get() = celsius * 9 / 5 + 32
    public val kelvin: Double get() = celsius + 273.15
    public operator fun plus(other: RelativeTemperature): Temperature = Temperature(celsius + other.celsius)
    public operator fun minus(other: RelativeTemperature): Temperature = Temperature(celsius - other.celsius)
    public operator fun minus(other: Temperature): RelativeTemperature = RelativeTemperature(celsius - other.celsius)

    public companion object {
        public val Int.celsius: Temperature get() = Temperature(celsius = this * 1.0)
        public val Long.celsius: Temperature get() = Temperature(celsius = this * 1.0)
        public val Double.celsius: Temperature get() = Temperature(celsius = this * 1.0)
        public val Int.fahrenheit: Temperature get() = Temperature(celsius = (this - 32.0) * 5 / 9)
        public val Long.fahrenheit: Temperature get() = Temperature(celsius = (this - 32.0) * 5 / 9)
        public val Double.fahrenheit: Temperature get() = Temperature(celsius = (this - 32.0) * 5 / 9)
        public val Int.kelvin: Temperature get() = Temperature(celsius = this - 273.15)
        public val Long.kelvin: Temperature get() = Temperature(celsius = this - 273.15)
        public val Double.kelvin: Temperature get() = Temperature(celsius = this - 273.15)
    }
    public override fun toString(): String = "$celsius°C"
}

@JvmInline
@Serializable
public value class RelativeTemperature(public val celsius: Double): Comparable<Temperature> {
    public override fun compareTo(other: Temperature): Int = this.celsius.compareTo(other.celsius)
    public val fahrenheit: Double get() = celsius * 9 / 5
    public val kelvin: Double get() = celsius
    public operator fun plus(other: RelativeTemperature): RelativeTemperature = RelativeTemperature(celsius + other.celsius)
    public operator fun minus(other: RelativeTemperature): RelativeTemperature = RelativeTemperature(celsius - other.celsius)
    public operator fun times(other: Double): RelativeTemperature = RelativeTemperature(celsius * other)
    public operator fun div(other: Double): RelativeTemperature = RelativeTemperature(celsius / other)

    public companion object {
        public val Int.relativeCelsius: RelativeTemperature get() = RelativeTemperature(celsius = this.toDouble())
        public val Long.relativeCelsius: RelativeTemperature get() = RelativeTemperature(celsius = this.toDouble())
        public val Double.relativeCelsius: RelativeTemperature get() = RelativeTemperature(celsius = this)
        public val Int.relativeFahrenheit: RelativeTemperature get() = RelativeTemperature(celsius = this * 5.0 / 9)
        public val Long.relativeFahrenheit: RelativeTemperature get() = RelativeTemperature(celsius = this * 5.0 / 9)
        public val Double.relativeFahrenheit: RelativeTemperature get() = RelativeTemperature(celsius = (this) * 5 / 9)
        public val Int.relativeKelvin: RelativeTemperature get() = RelativeTemperature(celsius = this.toDouble())
        public val Long.relativeKelvin: RelativeTemperature get() = RelativeTemperature(celsius = this.toDouble())
        public val Double.relativeKelvin: RelativeTemperature get() = RelativeTemperature(celsius = this)
    }
    public override fun toString(): String = "$celsius°C"
}

public operator fun Acceleration.times(other: Duration): Speed = Speed(metersPerSecond = this.metersPerSecondPerSecond * other.toDouble(DurationUnit.SECONDS))
public operator fun Acceleration.times(other: Mass): Force = Force(newtons = this.metersPerSecondPerSecond * other.kilograms)
public operator fun Area.div(other: Length): Length = Length(meters = this.squareMeters / other.meters)
public operator fun Area.times(other: Length): Volume = Volume(cubicMeters = this.squareMeters * other.meters)
public operator fun Area.times(other: Pressure): Force = Force(newtons = this.squareMeters * other.pascals)
public operator fun Duration.times(other: Acceleration): Speed = Speed(metersPerSecond = this.toDouble(DurationUnit.SECONDS) * other.metersPerSecondPerSecond)
public operator fun Duration.times(other: Power): Energy = Energy(joules = this.toDouble(DurationUnit.SECONDS) * other.watts)
public operator fun Duration.times(other: Speed): Length = Length(meters = this.toDouble(DurationUnit.SECONDS) * other.metersPerSecond)
public operator fun Energy.div(other: Duration): Power = Power(watts = this.joules / other.toDouble(DurationUnit.SECONDS))
public operator fun Energy.div(other: Force): Length = Length(meters = this.joules / other.newtons)
public operator fun Energy.div(other: Length): Force = Force(newtons = this.joules / other.meters)
public operator fun Energy.div(other: Power): Duration = (this.joules / other.watts).seconds
public operator fun Energy.div(other: Pressure): Volume = Volume(cubicMeters = this.joules / other.pascals)
public operator fun Energy.div(other: Volume): Pressure = Pressure(pascals = this.joules / other.cubicMeters)
public operator fun Force.div(other: Acceleration): Mass = Mass(kilograms = this.newtons / other.metersPerSecondPerSecond)
public operator fun Force.div(other: Area): Pressure = Pressure(pascals = this.newtons / other.squareMeters)
public operator fun Force.div(other: Mass): Acceleration = Acceleration(metersPerSecondPerSecond = this.newtons / other.kilograms)
public operator fun Force.div(other: Pressure): Area = Area(squareMeters = this.newtons / other.pascals)
public operator fun Force.times(other: Length): Energy = Energy(joules = this.newtons * other.meters)
public operator fun Force.times(other: Speed): Power = Power(watts = this.newtons * other.metersPerSecond)
public operator fun Length.div(other: Duration): Speed = Speed(metersPerSecond = this.meters / other.toDouble(DurationUnit.SECONDS))
public operator fun Length.div(other: Speed): Duration = (this.meters / other.metersPerSecond).seconds
public operator fun Length.times(other: Area): Volume = Volume(cubicMeters = this.meters * other.squareMeters)
public operator fun Length.times(other: Force): Energy = Energy(joules = this.meters * other.newtons)
public operator fun Length.times(other: Length): Area = Area(squareMeters = this.meters * other.meters)
public operator fun Mass.times(other: Acceleration): Force = Force(newtons = this.kilograms * other.metersPerSecondPerSecond)
public operator fun Power.div(other: Force): Speed = Speed(metersPerSecond = this.watts / other.newtons)
public operator fun Power.div(other: Speed): Force = Force(newtons = this.watts / other.metersPerSecond)
public operator fun Power.times(other: Duration): Energy = Energy(joules = this.watts * other.toDouble(DurationUnit.SECONDS))
public operator fun Pressure.times(other: Area): Force = Force(newtons = this.pascals * other.squareMeters)
public operator fun Pressure.times(other: Volume): Energy = Energy(joules = this.pascals * other.cubicMeters)
public operator fun Speed.div(other: Acceleration): Duration = (this.metersPerSecond / other.metersPerSecondPerSecond).seconds
public operator fun Speed.div(other: Duration): Acceleration = Acceleration(metersPerSecondPerSecond = this.metersPerSecond / other.toDouble(DurationUnit.SECONDS))
public operator fun Speed.times(other: Duration): Length = Length(meters = this.metersPerSecond * other.toDouble(DurationUnit.SECONDS))
public operator fun Speed.times(other: Force): Power = Power(watts = this.metersPerSecond * other.newtons)
public operator fun Volume.div(other: Area): Length = Length(meters = this.cubicMeters / other.squareMeters)
public operator fun Volume.div(other: Length): Area = Area(squareMeters = this.cubicMeters / other.meters)
public operator fun Volume.times(other: Pressure): Energy = Energy(joules = this.cubicMeters * other.pascals)