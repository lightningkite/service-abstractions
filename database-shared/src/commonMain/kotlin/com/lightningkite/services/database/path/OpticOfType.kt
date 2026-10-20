package com.lightningkite.services.database.path

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SealedTypeDiscriminator
import com.lightningkite.services.database.handle
import com.lightningkite.services.database.variantShortName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

public class OpticOfType<A, B, VARIANT : B>(
    public val wraps: Optic<A, B>,
    override val serializer: KSerializer<VARIANT>
) : Optic<A, VARIANT> {
    public val discriminator: SealedTypeDiscriminator<VARIANT> = SealedTypeDiscriminator(serializer)

    private val variantName
        get() = wraps.serializer.variantShortName(discriminator) ?: discriminator.serialName

    override val fields: List<Field<*, *>>
        get() = wraps.fields

    override fun get(source: A): VARIANT? = wraps.get(source)?.let { supertype ->
        discriminator.handle(
            supertype,
            matchFail = { null },
            matchSuccess = { it }
        )
    }

    override fun getOrThrow(source: A): VARIANT = wraps.getOrThrow(source).let { supertype ->
        discriminator.handle(
            supertype,
            matchFail = {
                throw IllegalArgumentException(
                    "OpticOfType expected $wraps to be of type $variantName, but was $supertype"
                )
            },
            matchSuccess = { it }
        )
    }

    override fun set(source: A, value: VARIANT): A = wraps.get(source)?.let { supertype ->
        discriminator.handle(
            supertype,
            matchFail = { source },
            matchSuccess = { wraps.set(source, value) }
        )
    } ?: source

    override fun mapCondition(condition: Condition<VARIANT>): Condition<A> =
        wraps.mapCondition(Condition.IfIsType(discriminator, condition))

    override fun mapModification(modification: Modification<VARIANT>): Modification<A> =
        wraps.mapModification(Modification.IfIsType(discriminator, modification))

    override fun toString(): String = "($wraps as? $variantName)?"

    // Claude: the serializer instance isn't stable for generic variants, so compare by discriminator instead.
    override fun equals(other: Any?): Boolean =
        other is OpticOfType<*, *, *> && other.wraps == wraps && other.discriminator == discriminator

    override fun hashCode(): Int = wraps.hashCode() * 31 + discriminator.hashCode()
}

public fun <A, B, VARIANT : B> Optic<A, B>.asType(serializer: KSerializer<VARIANT>): OpticOfType<A, B, VARIANT> =
    OpticOfType(this, serializer)

public inline fun <A, B, reified VARIANT : B> Optic<A, B>.asType(): OpticOfType<A, B, VARIANT> =
    OpticOfType(this, serializer())
