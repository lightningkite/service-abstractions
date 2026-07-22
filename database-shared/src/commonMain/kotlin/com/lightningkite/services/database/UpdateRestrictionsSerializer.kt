package com.lightningkite.services.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

// The v1 wire shape (`{mode, fields}`), used only for encoding/decoding by UpdateRestrictionsSerializer. Never
// exposed outside this file -- callers always see the real UpdateRestrictions(perField, default) model.
@Suppress("DEPRECATION")
@Serializable
private data class UpdateRestrictionsV1Shape<T>(
    val mode: UpdateRestrictions.Mode = UpdateRestrictions.Mode.Blacklist,
    val fields: List<UpdateRestrictions.Part<T>>,
)

// The v2 wire shape (`{perField, default}`), accepted for inbound tolerance (e.g. reading back whatever this
// serializer itself last wrote before this v1-compat change existed) but never emitted.
@Serializable
private data class UpdateRestrictionsV2Shape<T>(
    val perField: Map<DataClassPathPartial<T>, List<UpdateRestrictions.RestrictionOption<T>>> = emptyMap(),
    val default: List<UpdateRestrictions.RestrictionOption<T>> =
        listOf(UpdateRestrictions.RestrictionOption(Condition.Always, Condition.Always)),
)

/**
 * Serializes [UpdateRestrictions] in the v1 `{mode, fields}` wire shape, so clients released against v1 (such as
 * the admin UI) keep working without a re-release, while the in-memory representation stays the v2
 * `perField`/`default` model.
 *
 * ## Outbound (serialize)
 * Always emits `{mode, fields}`. `mode` is derived from whether [UpdateRestrictions.default] is empty. Each
 * `perField` entry becomes one [UpdateRestrictions.Part]:
 * - Zero options (`cannotBeModified()`) -> `Part(requires = Never, limitedTo = Always)` -- identical to what v1
 *   itself produced.
 * - One option -> `Part(requires = option.ifCurrentValue, limitedTo = option.newValueMustBe)` -- also identical to
 *   v1, and since every v1-built restriction (and every `requires`/`mustBe` call, which AND-merge into a single
 *   option) has exactly one option per field, this covers all pre-existing data losslessly.
 * - More than one option (only reachable via the new `anyOf`) -> collapsed into one best-effort
 *   `Part(requires = Or(all ifCurrentValue), limitedTo = Or(all newValueMustBe))`. This loses the independence
 *   between alternatives (a v1 `Part` has no way to express "either of these two independent rules"); round-trip
 *   through the actual `perField`/`default` shape (e.g. via [SerializationRegistry] with a non-Json format, or
 *   in-memory) if full `anyOf` fidelity is needed.
 *
 * ## Inbound (deserialize)
 * Tolerantly accepts both shapes on JSON: v1 `{mode, fields}` (detected by a `fields` key) and v2
 * `{perField, default}`. Non-JSON formats have no keys to peek at, so they're read as the v1 shape -- the only
 * wire format ever actually released.
 */
@OptIn(ExperimentalSerializationApi::class)
public class UpdateRestrictionsSerializer<T>(private val inner: KSerializer<T>) : KSerializer<UpdateRestrictions<T>> {
    private val v1Serializer = UpdateRestrictionsV1Shape.serializer(inner)
    private val v2Serializer = UpdateRestrictionsV2Shape.serializer(inner)

    override val descriptor: SerialDescriptor =
        SerialDescriptor("com.lightningkite.services.database.UpdateRestrictions", v1Serializer.descriptor)

    @Suppress("DEPRECATION")
    override fun serialize(encoder: Encoder, value: UpdateRestrictions<T>) {
        val v1 = UpdateRestrictionsV1Shape(
            mode = value.mode,
            fields = value.perField.map { (path, options) -> toPart(path, options) },
        )
        encoder.encodeSerializableValue(v1Serializer, v1)
    }

    override fun deserialize(decoder: Decoder): UpdateRestrictions<T> {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            val obj = element as? JsonObject
                ?: throw SerializationException("Expected a JSON object for UpdateRestrictions, got $element")
            return if ("fields" in obj) {
                fromV1(decoder.json.decodeFromJsonElement(v1Serializer, element))
            } else {
                val v2 = decoder.json.decodeFromJsonElement(v2Serializer, element)
                UpdateRestrictions(perField = v2.perField, default = v2.default)
            }
        }
        // Non-JSON formats have no keys to peek at; the v1 shape is the only wire format ever actually released.
        return fromV1(decoder.decodeSerializableValue(v1Serializer))
    }

    @Suppress("DEPRECATION")
    private fun toPart(
        path: DataClassPathPartial<T>,
        options: List<UpdateRestrictions.RestrictionOption<T>>,
    ): UpdateRestrictions.Part<T> = when (options.size) {
        0 -> UpdateRestrictions.Part(path, Condition.Never, Condition.Always)
        1 -> UpdateRestrictions.Part(path, options[0].ifCurrentValue, options[0].newValueMustBe)
        else -> UpdateRestrictions.Part(
            path,
            requires = Condition.Or(options.map { it.ifCurrentValue }),
            limitedTo = Condition.Or(options.map { it.newValueMustBe }),
        )
    }

    @Suppress("DEPRECATION")
    private fun fromV1(v1: UpdateRestrictionsV1Shape<T>): UpdateRestrictions<T> =
        UpdateRestrictions(mode = v1.mode, fields = v1.fields)
}
