package com.lightningkite.services.database

import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.Serializable

/**
 * Defines permissions for accessing a model in a database.
 * Default constructor is 'whitelist' mode.
 */
@Serializable
@GenerateDataClassPaths
public data class ModelPermissions<Model>(
    /**
     * The user may only create an item if it matches this condition.
     */
    val create: Condition<Model> = Condition.Never,
    /**
     * The user may only read models that match this condition.
     */
    val read: Condition<Model> = Condition.Never,
    /**
     * The user may only read models masked as defined here.
     */
    val readMask: Mask<Model> = Mask(emptyList()),
    /**
     * The user may only update models that match this condition.
     */
    val update: Condition<Model> = Condition.Never,
    /**
     * Restrictions on what the user is allowed to update.
     *
     * Deliberately *not* named `updateRestrictions`: that key held the old per-field representation, which this
     * cannot be read as. Using a new name means a payload written by either version simply falls back to the
     * default for the key it doesn't recognize, rather than mis-reading the other's data.
     */
    val updateRestriction: UpdateRestriction<Model> = UpdateRestriction.unrestricted(),
    /**
     * The user may only delete models that match this condition.
     */
    val delete: Condition<Model> = Condition.Never,
    val maxQueryTimeMs: Long = 1_000L,
) {
    public companion object {
        /**
         * A full whitelist permission set.
         */
        public fun <Model> allowAll(): ModelPermissions<Model> = ModelPermissions(
            create = Condition.Always,
            read = Condition.Always,
            update = Condition.Always,
            delete = Condition.Always,
        )
    }

    public constructor(
        read: Condition<Model>,
        readMask: Mask<Model> = Mask(),
        manage: Condition<Model>,
        updateRestriction: UpdateRestriction<Model> = UpdateRestriction.unrestricted(),
    ) : this(
        create = manage,
        update = manage,
        updateRestriction = updateRestriction,
        read = read,
        readMask = readMask,
        delete = manage
    )

    public constructor(
        all: Condition<Model>,
        readMask: Mask<Model> = Mask(),
        updateRestriction: UpdateRestriction<Model> = UpdateRestriction.unrestricted(),
    ) : this(
        create = all,
        update = all,
        updateRestriction = updateRestriction,
        read = all,
        readMask = readMask,
        delete = all
    )

    /**
     * @return a condition defining under what circumstances the given [modification] is permitted in.
     */
    public fun allowed(modification: Modification<Model>): Condition<Model> =
        updateRestriction(modification) and update

    /**
     * Masks a single instance of the model.
     */
    public fun mask(model: Model): Model = readMask(model)

    /**
     * Masks a single instance of the model.
     */
    public fun mask(model: Partial<Model>): Partial<Model> = readMask(model)
}