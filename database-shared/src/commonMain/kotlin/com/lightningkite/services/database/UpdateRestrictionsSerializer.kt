package com.lightningkite.services.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Serializes [UpdateRestrictions] as a single structure carrying both the current `perField`/`default` model and a
 * best-effort projection of it into the v1 `mode`/`fields` shape, so clients released against v1 (such as the admin
 * UI) keep reading the payload without a re-release.
 *
 * ## Outbound
 * All four elements are always written. `perField`/`default` are the real values. `mode` and `fields` are derived:
 * each `perField` entry becomes one [UpdateRestrictions.Part], and `mode` reflects whether [UpdateRestrictions.default]
 * is empty. The derivation is lossy in two ways, which is acceptable because it exists only for v1 readers:
 * - A field with more than one alternative (only reachable via `anyOf`) collapses to
 *   `Part(requires = Or(all ifCurrentValue), limitedTo = Or(all newValueMustBe))`, losing the independence between
 *   the alternatives -- a v1 `Part` cannot express "either of these two paired rules".
 * - A `default` other than "always" or "never" has no v1 equivalent at all; `mode` only distinguishes those two.
 *
 * ## Inbound
 * `perField`/`default` win whenever either is present, since `mode`/`fields` are only their lossy projection.
 * A payload carrying just the v1 elements -- the only shape ever actually released -- is converted through the v1
 * constructor. Elements are read through the ordinary `decodeElementIndex` loop and every element is optional, so
 * this behaves the same on any format, including binary ones that address elements by index rather than by name and
 * ones that decode sequentially.
 */
@OptIn(ExperimentalSerializationApi::class)
@Suppress("DEPRECATION")
public class UpdateRestrictionsSerializer<T>(private val inner: KSerializer<T>) : KSerializer<UpdateRestrictions<T>> {
    private val modeSerializer = UpdateRestrictions.Mode.serializer()
    private val fieldsSerializer = ListSerializer(UpdateRestrictions.Part.serializer(inner))
    private val optionsSerializer = ListSerializer(UpdateRestrictions.RestrictionOption.serializer(inner))
    private val perFieldSerializer = MapSerializer(DataClassPathSerializer(inner), optionsSerializer)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "com.lightningkite.services.database.UpdateRestrictions",
        inner.descriptor,
    ) {
        element("mode", modeSerializer.descriptor, isOptional = true)
        element("fields", fieldsSerializer.descriptor, isOptional = true)
        element("perField", perFieldSerializer.descriptor, isOptional = true)
        element("default", optionsSerializer.descriptor, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: UpdateRestrictions<T>) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, modeSerializer, value.mode)
            encodeSerializableElement(descriptor, 1, fieldsSerializer, value.perField.map(::toPart))
            encodeSerializableElement(descriptor, 2, perFieldSerializer, value.perField)
            encodeSerializableElement(descriptor, 3, optionsSerializer, value.default)
        }
    }

    override fun deserialize(decoder: Decoder): UpdateRestrictions<T> = decoder.decodeStructure(descriptor) {
        var mode = UpdateRestrictions.Mode.Blacklist
        var fields: List<UpdateRestrictions.Part<T>>? = null
        var perField: Map<DataClassPathPartial<T>, List<UpdateRestrictions.RestrictionOption<T>>>? = null
        var default: List<UpdateRestrictions.RestrictionOption<T>>? = null

        if (decodeSequentially()) {
            mode = decodeSerializableElement(descriptor, 0, modeSerializer)
            fields = decodeSerializableElement(descriptor, 1, fieldsSerializer)
            perField = decodeSerializableElement(descriptor, 2, perFieldSerializer)
            default = decodeSerializableElement(descriptor, 3, optionsSerializer)
        } else {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> mode = decodeSerializableElement(descriptor, 0, modeSerializer)
                    1 -> fields = decodeSerializableElement(descriptor, 1, fieldsSerializer)
                    2 -> perField = decodeSerializableElement(descriptor, 2, perFieldSerializer)
                    3 -> default = decodeSerializableElement(descriptor, 3, optionsSerializer)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> throw SerializationException("Unexpected element index $index for UpdateRestrictions")
                }
            }
        }

        if (perField != null || default != null) {
            UpdateRestrictions(
                perField = perField ?: emptyMap(),
                default = default ?: defaultFor(mode),
            )
        } else {
            UpdateRestrictions(mode = mode, fields = fields ?: emptyList())
        }
    }

    private fun toPart(
        entry: Map.Entry<DataClassPathPartial<T>, List<UpdateRestrictions.RestrictionOption<T>>>,
    ): UpdateRestrictions.Part<T> {
        val options = entry.value
        return when (options.size) {
            // v1's "unconditionally blocked", which is what `cannotBeModified()` produced there too.
            0 -> UpdateRestrictions.Part(entry.key, Condition.Never, Condition.Always)
            1 -> UpdateRestrictions.Part(entry.key, options[0].ifCurrentItem, options[0].newValueMustBe)
            else -> UpdateRestrictions.Part(
                entry.key,
                requires = Condition.Or(options.map { it.ifCurrentItem }),
                limitedTo = Condition.Or(options.map { it.newValueMustBe }),
            )
        }
    }

    private fun defaultFor(mode: UpdateRestrictions.Mode): List<UpdateRestrictions.RestrictionOption<T>> =
        if (mode == UpdateRestrictions.Mode.Whitelist) emptyList()
        else listOf(UpdateRestrictions.RestrictionOption(Condition.Always, Condition.Always))
}
