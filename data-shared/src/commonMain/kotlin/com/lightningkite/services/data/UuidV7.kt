package com.lightningkite.services.data

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
@JvmInline
public value class UuidV7 private constructor(public val raw: Uuid) : Comparable<UuidV7> {
    override fun compareTo(other: UuidV7): Int {
        return raw.compareTo(other.raw)
    }

    public fun timestamp(): Instant {
        val epochMillis = raw.toLongs { mostSignificantBits, _ -> mostSignificantBits ushr 16 }
        return Instant.fromEpochMilliseconds(epochMillis)
    }

    public companion object {
        @Unsafe("The provided uuid MUST be valid V7 format")
        /**
         * Creates a [UuidV7] from raw parts without any sort of check for safety.
         *
         * ## Safety
         * It is the caller's responsibility to ensure the provided `uuid` is a valid UUID V7 format.
         *
         * @param uuid The raw [Uuid] to be wrapped. Must be V7.
         * */
        public fun fromRaw(uuid: Uuid) : UuidV7 = UuidV7(uuid)

        /**
         * Generates a new [UuidV7]
         * @see Uuid.generateV7
         * */
        public fun generate(): UuidV7 = UuidV7(Uuid.generateV7())

        /**
        * Creates a new [UuidV7] for the specific timestamp
         * @see Uuid.generateV7NonMonotonicAt
        * */
        public fun generateNonMonotonicAt(instant: Instant): UuidV7 = UuidV7(Uuid.generateV7NonMonotonicAt(instant))
    }
}