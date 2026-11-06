package com.lightningkite.services.database.optics

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SealedTypeDiscriminator
import com.lightningkite.services.database.matches
import com.lightningkite.services.database.serializableOptions
import kotlinx.serialization.KSerializer

/**
 * A [Lens] to the field [name] that every variant of the sealed type [A] has, with the same type.
 *
 * Reads and writes go to the field of whichever variant the value is. Conditions and modifications apply to every
 * variant's field, each guarded by that variant's type. It serializes as [name].
 */
@Suppress("UNCHECKED_CAST")
public class SealedSharedFieldLens<A, B>(
    public val parent: KSerializer<A>,
    public val name: String,
) : Lens<A, B> {
    private val variants: List<Pair<SealedTypeDiscriminator<A>, Field<A, B>>> by lazy {
        val options = parent.serializableOptions
            ?: throw IllegalArgumentException("${parent.descriptor.serialName} is not a sealed type")
        options.map { option ->
            val field = option.serializer.fields?.find { it.name == name }
                ?: throw IllegalArgumentException("${option.serializer.descriptor.serialName} has no field '$name'")
            SealedTypeDiscriminator(option.serializer) to field as Field<A, B>
        }
    }

    override val serializer: KSerializer<B> by lazy { variants.first().second.serializer }
    override val fields: List<Field<*, *>> get() = listOf()

    private fun variantOf(source: A): Field<A, B> =
        variants.first { (discriminator, _) -> discriminator.matches(source) }.second

    override fun get(source: A): B = variantOf(source).get(source)
    override fun set(source: A, value: B): A = variantOf(source).set(source, value)

    override fun mapCondition(condition: Condition<B>): Condition<A> =
        Condition.Or(variants.map { (discriminator, field) ->
            Condition.IfIsType(discriminator, Condition.OnField(field, condition))
        })

    override fun mapModification(modification: Modification<B>): Modification<A> =
        Modification.Chain(variants.map { (discriminator, field) ->
            Modification.IfIsType(discriminator, Modification.OnField(field, modification))
        })

    override fun toString(): String = name
    override fun hashCode(): Int = parent.descriptor.serialName.hashCode() * 31 + name.hashCode()
    override fun equals(other: Any?): Boolean =
        other is SealedSharedFieldLens<*, *> && other.name == name && other.parent.descriptor.serialName == parent.descriptor.serialName
}
