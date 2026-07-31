package com.lightningkite.services.database

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes [UpdateRestriction] as a single-key object naming the variant, the same shape [Condition] uses.
 *
 * The options are built lazily, which is what stops the recursion: [UpdateRestriction.All]'s generated serializer
 * needs an `UpdateRestriction` serializer for its elements, which constructs one of these, whose own options are
 * not touched until something is actually read or written.
 */
public class UpdateRestrictionSerializer<T>(inner: KSerializer<T>) : KSerializer<UpdateRestriction<T>> {
    private val wrapped = MySealedClassSerializer<UpdateRestriction<T>>(
        "com.lightningkite.services.database.UpdateRestriction",
        {
            listOf(
                MySealedClassSerializer.Option(
                    UpdateRestriction.All.serializer(inner), "All", priority = 20
                ) { it is UpdateRestriction.All },
                MySealedClassSerializer.Option(
                    UpdateRestriction.AnyOf.serializer(inner), "AnyOf", priority = 20
                ) { it is UpdateRestriction.AnyOf },
                MySealedClassSerializer.Option(
                    UpdateRestriction.OnCurrentItem.serializer(inner), "OnCurrentItem", priority = 20
                ) { it is UpdateRestriction.OnCurrentItem },
                MySealedClassSerializer.Option(
                    UpdateRestriction.Preserves.serializer(inner), "Preserves", priority = 20
                ) { it is UpdateRestriction.Preserves },
                MySealedClassSerializer.Option(
                    UpdateRestriction.Untouched.serializer(inner), "Untouched", priority = 20
                ) { it is UpdateRestriction.Untouched },
                MySealedClassSerializer.Option(
                    UpdateRestriction.OnlyTouches.serializer(inner), "OnlyTouches", priority = 20
                ) { it is UpdateRestriction.OnlyTouches },
            )
        }
    )

    override val descriptor: SerialDescriptor get() = wrapped.descriptor
    override fun serialize(encoder: Encoder, value: UpdateRestriction<T>): Unit = wrapped.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): UpdateRestriction<T> = wrapped.deserialize(decoder)
}
