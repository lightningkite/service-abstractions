@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*


@Deprecated(
    "Use projection, which works for any path and includes assignments to a value containing the field.",
    ReplaceWith("projection(field)"),
)
public fun <T, V> Modification<T>.forFieldOrNull(field: Field<T, V>): Modification<V>? {
    return when (this) {
        is Modification.Chain -> modifications.mapNotNull { it.forFieldOrNull(field) }.takeUnless { it.isEmpty() }
            ?.let { Modification.Chain(it) }

        is Modification.OnField<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            if (this.key == field) this.modification as Modification<V> else null
        }

        else -> null
    }
}

/**
 * Calls [onModification] with each part of this modification that affects the targets of [path].
 *
 * The parts of a [Modification.Chain] are reported separately, in order, and parts that don't affect a target are
 * not reported:
 * - An assignment to a value containing targets is reported as one assignment per target. A value with no target,
 *   such as a `null` parent or a different sealed variant, is not reported.
 * - A change to collection elements is reported as is, and appended elements are reported as assignments. Removing
 *   elements is not reported.
 * - A write to a field shared by the variants of a sealed type is made once per variant, so it is reported once per
 *   variant.
 *
 * ```kotlin
 * @Queryable
 * data class User(val name: String, val address: Address?, val tags: List<String>, val pet: Pet)
 *
 * @Queryable
 * data class Address(val city: String, val zip: String)
 *
 * @Queryable
 * sealed interface Pet {
 *     @Queryable data class Dog(val name: String) : Pet
 *     @Queryable data class Cat(val lives: Int) : Pet
 * }

 * // vet against User.name
 * Modification<User> { it.name assign "Ann" }                            // Assign("Ann")
 * Modification<User> { it.tags += "new" }                                // nothing
 *
 * // vet against User.address.notNull.city
 * Modification<User> { it.address.notNull.city assign "Provo" }          // Assign("Provo")
 * Modification<User> { it.address assign Address("Provo", "84601") }    // Assign("Provo")
 * Modification<User> { it.address.notNull.zip assign "84604" }          // nothing
 * Modification<User> { it.address assign null }                          // nothing
 *
 * // vet against User.tags.elements
 * Modification<User> { it.tags assign listOf("a", "b") }                // Assign("a"), Assign("b")
 * Modification<User> { it.tags += "c" }                                  // Assign("c")
 * Modification<User> { it.tags.forEach { tag -> tag assign "x" } }       // Assign("x")
 * Modification<User> { it.tags.removeAll { tag -> tag eq "a" } }         // nothing
 *
 * // vet against User.pet.asDog.name
 * Modification<User> { it.pet.asDog.name assign "Rex" }                  // Assign("Rex")
 * Modification<User> { it.pet assign Pet.Dog("Rex") }                    // Assign("Rex")
 * Modification<User> { it.pet assign Pet.Cat(9) }                        // nothing
 * ```
 */
public fun <K, V> Modification<K>.vet(path: Optic<K, V>, onModification: (Modification<V>) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    vetAgainst((path as Optic<Any?, Any?>).mapModification(vetTarget), onModification as (Modification<*>) -> Unit)
}

/**
 * Returns the parts of this modification that [vet] reports for [path] combined into one modification of the target,
 * or `null` if there are none.
 *
 * @throws IllegalArgumentException if [path] can have more than one target, such as collection elements or a field
 * shared by the variants of a sealed type, since their changes can't be combined into one modification.
 */
public fun <K, V> Modification<K>.projection(path: Optic<K, V>): Modification<V>? {
    @Suppress("UNCHECKED_CAST")
    require((path as Optic<Any?, Any?>).mapModification(vetTarget).hasOneTarget()) {
        "$path can have more than one target, so its changes can't be combined into one modification."
    }
    val parts = buildList { vet(path) { add(it) } }
    return when (parts.size) {
        0 -> null
        1 -> parts.single()
        else -> Modification.Chain(parts)
    }
}

private fun Modification<*>.hasOneTarget(): Boolean = when {
    this === vetTarget -> true
    this is Modification.OnField<*, *> -> modification.hasOneTarget()
    this is Modification.IfNotNull -> modification.hasOneTarget()
    this is Modification.IfIsType<*, *> -> modification.hasOneTarget()
    else -> false
}

// Claude: marks where the target sits in what path.mapModification returns, so vet works for any optic. Compared by identity.
private val vetTarget: Modification<Any?> = Modification.Assign(Any())

// Claude: reports the parts of this modification that line up with vetTarget inside shape.
private fun Modification<*>.vetAgainst(shape: Modification<*>, report: (Modification<*>) -> Unit) {
    when {
        shape === vetTarget -> vet(report)
        this is Modification.Chain -> modifications.forEach { it.vetAgainst(shape, report) }
        this is Modification.Assign -> targetsOf(value, shape).forEach { report(Modification.Assign(it)) }

        shape is Modification.Chain -> shape.modifications.forEach { vetAgainst(it, report) }
        shape is Modification.OnField<*, *> ->
            if (this is Modification.OnField<*, *> && key == shape.key) modification.vetAgainst(shape.modification, report)

        shape is Modification.IfNotNull ->
            if (this is Modification.IfNotNull) modification.vetAgainst(shape.modification, report)

        shape is Modification.IfIsType<*, *> ->
            if (this is Modification.IfIsType<*, *> && discriminator == shape.discriminator) {
                modification.vetAgainst(shape.modification, report)
            }

        shape is Modification.ListPerElement<*> -> when (this) {
            is Modification.ListPerElement<*> -> modification.vetAgainst(shape.modification, report)
            is Modification.ListAppend<*> -> items.forEach { Modification.Assign(it).vetAgainst(shape.modification, report) }
            else -> {}
        }

        shape is Modification.SetPerElement<*> -> when (this) {
            is Modification.SetPerElement<*> -> modification.vetAgainst(shape.modification, report)
            is Modification.SetAppend<*> -> items.forEach { Modification.Assign(it).vetAgainst(shape.modification, report) }
            else -> {}
        }

        else -> throw IllegalArgumentException("vet can't follow $shape")
    }
}

// Claude: the values within value that line up with vetTarget inside shape; empty where there are none.
@Suppress("UNCHECKED_CAST")
private fun targetsOf(value: Any?, shape: Modification<*>): List<Any?> = when {
    shape === vetTarget -> listOf(value)
    value == null -> listOf()
    shape is Modification.Chain -> shape.modifications.flatMap { targetsOf(value, it) }
    shape is Modification.OnField<*, *> -> targetsOf((shape.key as Field<Any, Any?>).get(value), shape.modification)
    shape is Modification.IfNotNull -> targetsOf(value, shape.modification)
    shape is Modification.IfIsType<*, *> ->
        if ((shape.discriminator as SealedTypeDiscriminator<Any>).matches(value)) targetsOf(value, shape.modification)
        else listOf()

    shape is Modification.ListPerElement<*> -> (value as List<*>).flatMap { targetsOf(it, shape.modification) }
    shape is Modification.SetPerElement<*> -> (value as Set<*>).flatMap { targetsOf(it, shape.modification) }
    else -> throw IllegalArgumentException("vet can't follow $shape")
}

/** Calls [onModification] with each part of this modification, flattening any [Modification.Chain]. */
public fun <T> Modification<T>.vet(onModification: (Modification<T>) -> Unit) {
    when (this) {
        is Modification.Chain -> modifications.forEach { it.vet(onModification) }
        else -> onModification(this)
    }
}

@Suppress("UNCHECKED_CAST")
public fun <V> Modification<*>.vet(
    fieldChain: List<Field<*, *>>,
    onModification: (Modification<V>) -> Unit,
) {
    val field: Field<*, *> = fieldChain.firstOrNull() ?: run {
        onModification(this as Modification<V>)
        return
    }
    when (this) {
        is Modification.Assign -> {
            var value = this.value
            for (key in fieldChain) {
                value = (key as Field<Any?, Any?>).get(value)
            }
            onModification(Modification.Assign(value as V))
        }

        is Modification.Chain -> modifications.forEach { it.vet(fieldChain, onModification) }
        is Modification.OnField<*, *> -> if (this.key == field) this.modification.vet(
            fieldChain.drop(1),
            onModification
        )

        is Modification.IfNotNull -> this.modification.vet(fieldChain, onModification)
        else -> {}
    }
}

public fun <T, V> Modification<T>.map(
    field: Field<T, V>,
    onModification: (Modification<V>) -> Modification<V>,
): Modification<T> {
    return when (this) {
        is Modification.Chain -> modifications.map { it.map(field, onModification) }.let { Modification.Chain(it) }
        is Modification.OnField<*, *> -> if (this.key == field) {
            @Suppress("UNCHECKED_CAST")
            (this as Modification.OnField<T, V>).copy(
                modification = onModification(
                    modification
                )
            )
        } else {
            @Suppress("USELESS_CAST")
            this as Modification<T>
        }

        is Modification.Assign -> Modification.Assign(
            field.setCopy(
                this.value,
                onModification(Modification.Assign(field.get(this.value))).let { it as Modification.Assign<V> }.value
            )
        )

        else -> this
    }
}

public suspend fun <T, V> Modification<T>.mapSuspend(
    field: Field<T, V>,
    onModification: suspend (Modification<V>) -> Modification<V>,
): Modification<T> {
    return when (this) {
        is Modification.Chain -> modifications.map { it.mapSuspend(field, onModification) }
            .let { Modification.Chain(it) }

        is Modification.OnField<*, *> -> if (this.key == field) {
            @Suppress("UNCHECKED_CAST")
            (this as Modification.OnField<T, V>).copy(
                modification = onModification(
                    modification
                )
            )
        } else {
            @Suppress("USELESS_CAST")
            this as Modification<T>
        }

        is Modification.Assign -> Modification.Assign(
            field.setCopy(
                this.value,
                onModification(Modification.Assign(field.get(this.value))).let { it as Modification.Assign<V> }.value
            )
        )

        else -> this
    }
}