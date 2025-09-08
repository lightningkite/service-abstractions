package com.lightningkite.services.database

import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
@GenerateDataClassPaths
public data class UpdateRestrictionsPart<T>(
    val path: DataClassPathPartial<T>,
    val limitedIf: Condition<T>,
    val limitedTo: Condition<T>
)

/**
 * Permission rules regarding updating items per-field.
 */
@GenerateDataClassPaths
@Serializable
public data class UpdateRestrictions<T>(
    /**
     * If the modification matches paths, then the condition is applied to the update
     */
    val fields: List<UpdateRestrictionsPart<T>> = listOf()
) {

    public operator fun invoke(on: Modification<T>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for (field in fields) {
            if (on.affects(field.path)) {
                totalConditions.add(field.limitedIf)
                if (field.limitedTo !is Condition.Always) {
                    if (!field.limitedTo.guaranteedAfter(on)) return Condition.Never
                }
            }
        }
        return when (totalConditions.size) {
            0 -> Condition.Always
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }

    public class Builder<T>(
        serializer: KSerializer<T>,
        public val fields: ArrayList<UpdateRestrictionsPart<T>> = ArrayList()
    ) {
        private val it = DataClassPathSelf(serializer)

        /**
         * Makes a field unmodifiable.
         */
        public fun DataClassPath<T, *>.cannotBeModified() {
            fields.add(UpdateRestrictionsPart(this, Condition.Never, Condition.Always))
        }

        /**
         * Makes a field only modifiable if the item matches the [condition].
         */
        public infix fun DataClassPath<T, *>.requires(condition: Condition<T>) {
            fields.add(UpdateRestrictionsPart(this, condition, Condition.Always))
        }

        /**
         * Makes a field only modifiable if the item matches the [condition].
         * In addition, the value it is being changed to must match [valueMust].
         */
        public inline fun <reified V> DataClassPath<T, V>.requires(
            requires: Condition<T>,
            valueMust: (DataClassPath<V, V>) -> Condition<V>
        ) {
            fields.add(UpdateRestrictionsPart(this, requires, this.condition(valueMust)))
        }

        /**
         * The value is only allowed to change to a value that matches [valueMust].
         */
        public inline fun <reified V> DataClassPath<T, V>.mustBe(valueMust: (DataClassPath<V, V>) -> Condition<V>) {
            fields.add(UpdateRestrictionsPart(this, Condition.Always, this.condition(valueMust)))
        }

        public fun build(): UpdateRestrictions<T> = UpdateRestrictions(fields)
        public fun include(mask: UpdateRestrictions<T>) {
            fields.addAll(mask.fields)
        }
    }
}

/**
 * DSL for defining [UpdateRestrictions]
 */
public inline fun <reified T> updateRestrictions(builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>) -> Unit): UpdateRestrictions<T> {
    return UpdateRestrictions.Builder<T>(serializer()).apply { builder(path<T>()) }.build()
}
