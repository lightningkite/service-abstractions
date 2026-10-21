package com.lightningkite.services.database.test

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Conditions, modifications, and sorts that project a sealed field onto one of its variants via `asType`.
 *
 * Modifications through a variant path must leave a value of any other variant untouched, exactly as they do in memory.
 *
 * Sorts follow the same standard. A backend may sort on the variant's field wherever it's stored, so another variant
 * storing a field of the same name can sort by that value instead of as null. Tests that sort on such a field select
 * the variant with a condition first.
 */
abstract class SealedPathTests {
    abstract val database: Database

    private val shapes = listOf(
        Shape.Circle(1),
        Shape.Circle(2),
        Shape.Circle(3),
        Shape.Square(2, "a"),
        Shape.Square(3, "b"),
        Shape.Empty,
    )

    private val payments = listOf(
        Payment.Card(10, "1111"),
        Payment.Card(20, "2222"),
        CashPayment(10, "USD"),
        CashPayment(30, "EUR"),
        Payment.Pending,
    )

    private suspend fun conditionTest(name: String, items: List<SealedPathTestModel>, condition: Condition<SealedPathTestModel>) {
        val collection = database.prepare(DatabaseTableDefinition<SealedPathTestModel>("SealedPathTestModel_$name"))
        collection.insert(items)
        val expected = items.filter { condition(it) }.sortedBy { it._id }
        val results = collection.find(condition).toList().sortedBy { it._id }
        assertEquals(expected, results)
    }

    private suspend fun modificationTest(item: SealedPathTestModel, name: String, modification: Modification<SealedPathTestModel>) {
        val collection = database.prepare(DatabaseTableDefinition<SealedPathTestModel>("SealedPathTestModel_$name"))
        collection.insertOne(item)
        collection.updateOneById(item._id, modification)
        assertEquals(modification(item), collection.get(item._id))
    }

    // region conditions

    @Test
    fun test_isType() = runTest {
        conditionTest(
            "test_isType",
            shapes.map { SealedPathTestModel(shape = it) },
            Condition { it.shape isType Shape.Circle.serializer() }
        )
    }

    @Test
    fun test_isType_object() = runTest {
        conditionTest(
            "test_isType_object",
            shapes.map { SealedPathTestModel(shape = it) },
            Condition { it.shape isType Shape.Empty.serializer() }
        )
    }

    @Test
    fun test_asType_field_eq() = runTest {
        conditionTest(
            "test_asType_field_eq",
            shapes.map { SealedPathTestModel(shape = it) },
            Condition { it.shape.asCircle.radius eq 2 }
        )
    }

    @Test
    fun test_asType_field_gt() = runTest {
        // Square(3) and Circle(3) share a value, so only the type check can tell them apart.
        conditionTest(
            "test_asType_field_gt",
            shapes.map { SealedPathTestModel(shape = it) },
            Condition { it.shape.asSquare.side gt 2 }
        )
    }

    @Test
    fun test_asType_eq() = runTest {
        conditionTest(
            "test_asType_eq",
            shapes.map { SealedPathTestModel(shape = it) },
            Condition { it.shape.asCircle eq Shape.Circle(2) }
        )
    }

    @Test
    fun test_asType_neq() = runTest {
        conditionTest(
            "test_asType_neq",
            shapes.map { SealedPathTestModel(shape = it) },
            Condition { it.shape.asCircle neq Shape.Circle(2) }
        )
    }

    // neq, notInside and not accept a value that doesn't have the field at all, so only the type check excludes other variants.
    @Test
    fun test_asType_field_neq() = runTest {
        conditionTest(
            "test_asType_field_neq",
            shapes.map { SealedPathTestModel(shape = it) },
            Condition { it.shape.asCircle.radius neq 2 }
        )
    }

    @Test
    fun test_asType_field_notInside() = runTest {
        conditionTest(
            "test_asType_field_notInside",
            shapes.map { SealedPathTestModel(shape = it) },
            Condition { it.shape.asSquare.label notInside listOf("a") }
        )
    }

    @Test
    fun test_asType_field_not() = runTest {
        conditionTest(
            "test_asType_field_not",
            shapes.map { SealedPathTestModel(shape = it) },
            Condition { it.shape.asCircle.radius.mapCondition(Condition.Not(Condition.Equal(1))) }
        )
    }

    @Test
    fun test_not_asType_field() = runTest {
        conditionTest(
            "test_not_asType_field",
            shapes.map { SealedPathTestModel(shape = it) },
            Condition { !(it.shape.asCircle.radius eq 1) }
        )
    }

    @Test
    fun test_notNull_asType_field_neq() = runTest {
        conditionTest(
            "test_notNull_asType_field_neq",
            (shapes + null).map { SealedPathTestModel(shapeNullable = it) },
            Condition { it.shapeNullable.notNull.asCircle.radius neq 2 }
        )
    }

    @Test
    fun test_list_all_asType_field_neq() = runTest {
        conditionTest(
            "test_list_all_asType_field_neq",
            listOf(
                SealedPathTestModel(shapes = listOf(Shape.Circle(3), Shape.Circle(1))),
                SealedPathTestModel(shapes = listOf(Shape.Square(1), Shape.Circle(3))),
                SealedPathTestModel(shapes = listOf(Shape.Empty)),
                SealedPathTestModel(shapes = listOf()),
            ),
            Condition { it.shapes.all { it.asCircle.radius neq 1 } }
        )
    }

    @Test
    fun test_notNull_asType() = runTest {
        conditionTest(
            "test_notNull_asType",
            (shapes + null).map { SealedPathTestModel(shapeNullable = it) },
            Condition { it.shapeNullable.notNull.asCircle.radius eq 1 }
        )
    }

    @Test
    fun test_list_any_asType() = runTest {
        conditionTest(
            "test_list_any_asType",
            listOf(
                SealedPathTestModel(shapes = listOf(Shape.Circle(3), Shape.Empty)),
                SealedPathTestModel(shapes = listOf(Shape.Square(3), Shape.Circle(1))),
                SealedPathTestModel(shapes = listOf()),
            ),
            Condition { it.shapes.any { it.asCircle.radius eq 3 } }
        )
    }

    @Test
    fun test_list_all_isType() = runTest {
        conditionTest(
            "test_list_all_isType",
            listOf(
                SealedPathTestModel(shapes = listOf(Shape.Circle(3), Shape.Circle(1))),
                SealedPathTestModel(shapes = listOf(Shape.Square(3), Shape.Circle(1))),
                SealedPathTestModel(shapes = listOf()),
            ),
            Condition { it.shapes.all { it isType Shape.Circle.serializer() } }
        )
    }

    @Test
    fun test_sealedClass_isType() = runTest {
        conditionTest(
            "test_sealedClass_isType",
            payments.map { SealedPathTestModel(payment = it) },
            Condition { it.payment isType Payment.Card.serializer() }
        )
    }

    @Test
    fun test_sealedClass_isType_object() = runTest {
        conditionTest(
            "test_sealedClass_isType_object",
            payments.map { SealedPathTestModel(payment = it) },
            Condition { it.payment isType Payment.Pending.serializer() }
        )
    }

    @Test
    fun test_sealedClass_asType_field_eq() = runTest {
        // Card(10) and CashPayment(10) share an amount, so only the type check can tell them apart.
        conditionTest(
            "test_sealedClass_asType_field_eq",
            payments.map { SealedPathTestModel(payment = it) },
            Condition { it.payment.asCard.amount eq 10 }
        )
    }

    @Test
    fun test_sealedClass_topLevelVariant_field_eq() = runTest {
        conditionTest(
            "test_sealedClass_topLevelVariant_field_eq",
            payments.map { SealedPathTestModel(payment = it) },
            Condition { it.payment.asCashPayment.currency eq "EUR" }
        )
    }

    @Test
    fun test_sealedClass_asType_eq() = runTest {
        conditionTest(
            "test_sealedClass_asType_eq",
            payments.map { SealedPathTestModel(payment = it) },
            Condition { it.payment.asCashPayment eq CashPayment(10, "USD") }
        )
    }

    @Test
    fun test_sealedClass_list_any_asType() = runTest {
        conditionTest(
            "test_sealedClass_list_any_asType",
            listOf(
                SealedPathTestModel(payments = listOf(Payment.Card(20, "2222"), Payment.Pending)),
                SealedPathTestModel(payments = listOf(CashPayment(20), Payment.Card(10))),
                SealedPathTestModel(payments = listOf()),
            ),
            Condition { it.payments.any { it.asCard.amount eq 20 } }
        )
    }

    // endregion

    // region sorting

    // `_id` breaks ties between values that sort as null so the order is fully defined.
    private suspend fun sortTest(
        name: String,
        items: List<SealedPathTestModel>,
        orderBy: List<SortPart<SealedPathTestModel>>,
        condition: Condition<SealedPathTestModel> = Condition.Always,
    ) {
        val collection = database.prepare(DatabaseTableDefinition<SealedPathTestModel>("SealedPathTestModel_$name"))
        collection.insert(items)
        val fullOrder = orderBy + SortPart(Lens<SealedPathTestModel>()._id)
        val expected = items.filter { condition(it) }.sortedWith(fullOrder.comparator!!)
        assertEquals(expected, collection.find(condition, orderBy = fullOrder).toList())
    }

    @Test
    fun test_sort_asType_field() = runTest {
        sortTest(
            "test_sort_asType_field",
            shapes.map { SealedPathTestModel(shape = it) },
            listOf(SortPart(Lens<SealedPathTestModel>().shape.asCircle.radius))
        )
    }

    @Test
    fun test_sort_asType_field_descending() = runTest {
        sortTest(
            "test_sort_asType_field_descending",
            shapes.map { SealedPathTestModel(shape = it) },
            listOf(SortPart(Lens<SealedPathTestModel>().shape.asCircle.radius, ascending = false))
        )
    }

    @Test
    fun test_sort_asType_sharedFieldName() = runTest {
        sortTest(
            "test_sort_asType_sharedFieldName",
            payments.map { SealedPathTestModel(payment = it) },
            listOf(SortPart(Lens<SealedPathTestModel>().payment.asCard.amount)),
            Condition { it.payment isType Payment.Card.serializer() }
        )
    }

    @Test
    fun test_sort_asType_sharedFieldName_descending() = runTest {
        sortTest(
            "test_sort_asType_sharedFieldName_descending",
            payments.map { SealedPathTestModel(payment = it) },
            listOf(SortPart(Lens<SealedPathTestModel>().payment.asCard.amount, ascending = false)),
            Condition { it.payment isType Payment.Card.serializer() }
        )
    }

    @Test
    fun test_sort_notNull_asType_field() = runTest {
        sortTest(
            "test_sort_notNull_asType_field",
            (shapes + null).map { SealedPathTestModel(shapeNullable = it) },
            listOf(SortPart(Lens<SealedPathTestModel>().shapeNullable.notNull.asCircle.radius, ascending = false))
        )
    }

    @Test
    fun test_updateOne_sorted_asType_sharedFieldName() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<SealedPathTestModel>("SealedPathTestModel_test_updateOne_sorted_asType_sharedFieldName"))
        val items = payments.map { SealedPathTestModel(payment = it) }
        collection.insert(items)
        val orderBy = listOf(SortPart(Lens<SealedPathTestModel>().payment.asCard.amount, ascending = false))
        val modification = Modification<SealedPathTestModel> { it.payment.asCard.last4 assign "9999" }
        val condition = Condition<SealedPathTestModel> { it.payment isType Payment.Card.serializer() }
        val target = items.filter { condition(it) }.sortedWith(orderBy.comparator!!).first()
        val change = collection.updateOne(condition, modification, orderBy)
        assertEquals(target, change.old)
        assertEquals(modification(target), collection.get(target._id))
    }

    @Test
    fun test_deleteOne_sorted_asType_sharedFieldName() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<SealedPathTestModel>("SealedPathTestModel_test_deleteOne_sorted_asType_sharedFieldName"))
        val items = payments.map { SealedPathTestModel(payment = it) }
        collection.insert(items)
        // CashPayment(30) has the largest amount, but the condition leaves only Cards to sort.
        val orderBy = listOf(SortPart(Lens<SealedPathTestModel>().payment.asCard.amount, ascending = false))
        val condition = Condition<SealedPathTestModel> { it.payment isType Payment.Card.serializer() }
        val target = items.filter { condition(it) }.sortedWith(orderBy.comparator!!).first()
        assertEquals(target, collection.deleteOne(condition, orderBy))
        assertEquals(null, collection.get(target._id))
    }

    // endregion

    // region modifications

    @Test
    fun test_asType_field_increment() = runTest {
        modificationTest(
            SealedPathTestModel(shape = Shape.Circle(1)),
            "test_asType_field_increment",
            Modification { it.shape.asCircle.radius += 2 }
        )
    }

    @Test
    fun test_asType_field_assign() = runTest {
        modificationTest(
            SealedPathTestModel(shape = Shape.Square(2, "a")),
            "test_asType_field_assign",
            Modification { it.shape.asSquare.label assign "changed" }
        )
    }

    @Test
    fun test_asType_assign() = runTest {
        modificationTest(
            SealedPathTestModel(shape = Shape.Circle(1)),
            "test_asType_assign",
            Modification { it.shape.asCircle assign Shape.Circle(9) }
        )
    }

    @Test
    fun test_asType_multiple_fields() = runTest {
        modificationTest(
            SealedPathTestModel(shape = Shape.Square(2, "a")),
            "test_asType_multiple_fields",
            Modification {
                it.shape.asSquare.side += 1
                it.shape.asSquare.label assign "changed"
            }
        )
    }

    @Test
    fun test_notNull_asType_increment() = runTest {
        modificationTest(
            SealedPathTestModel(shapeNullable = Shape.Circle(1)),
            "test_notNull_asType_increment",
            Modification { it.shapeNullable.notNull.asCircle.radius += 1 }
        )
    }

    @Test
    fun test_list_forEachIf_asType() = runTest {
        modificationTest(
            SealedPathTestModel(shapes = listOf(Shape.Circle(1), Shape.Square(1), Shape.Circle(5), Shape.Empty)),
            "test_list_forEachIf_asType",
            Modification {
                it.shapes.forEachIf(
                    condition = { it isType Shape.Circle.serializer() },
                    modification = { it.asCircle.radius += 1 }
                )
            }
        )
    }

    @Test
    fun test_asType_field_increment_otherVariant() = runTest {
        modificationTest(
            SealedPathTestModel(shape = Shape.Square(2, "a")),
            "test_asType_field_increment_otherVariant",
            Modification { it.shape.asCircle.radius += 2 }
        )
    }

    @Test
    fun test_asType_assign_otherVariant() = runTest {
        modificationTest(
            SealedPathTestModel(shape = Shape.Empty),
            "test_asType_assign_otherVariant",
            Modification { it.shape.asCircle assign Shape.Circle(9) }
        )
    }

    @Test
    fun test_notNull_asType_increment_null() = runTest {
        modificationTest(
            SealedPathTestModel(shapeNullable = null),
            "test_notNull_asType_increment_null",
            Modification { it.shapeNullable.notNull.asCircle.radius += 1 }
        )
    }

    @Test
    fun test_notNull_asType_increment_otherVariant() = runTest {
        modificationTest(
            SealedPathTestModel(shapeNullable = Shape.Square(2, "a")),
            "test_notNull_asType_increment_otherVariant",
            Modification { it.shapeNullable.notNull.asCircle.radius += 1 }
        )
    }

    @Test
    open fun test_asType_chain_otherVariant() = runTest {
        modificationTest(
            SealedPathTestModel(shape = Shape.Square(2, "a"), payment = Payment.Card(10, "1111")),
            "test_asType_chain_otherVariant",
            Modification {
                it.shape.asCircle.radius += 1
                it.shape.asSquare.label assign "changed"
                it.payment.asCard.amount += 5
                it.payment.asCashPayment.currency assign "EUR"
            }
        )
    }

    @Test
    fun test_list_forEach_asType() = runTest {
        modificationTest(
            SealedPathTestModel(shapes = listOf(Shape.Circle(1), Shape.Square(1), Shape.Circle(5), Shape.Empty)),
            "test_list_forEach_asType",
            Modification {
                it.shapes.forEach {
                    it.asCircle.radius += 1
                    it.asSquare.label assign "changed"
                }
            }
        )
    }

    @Test
    fun test_list_forEachIf_asType_partlySelected() = runTest {
        modificationTest(
            SealedPathTestModel(shapes = listOf(Shape.Circle(1), Shape.Square(1), Shape.Circle(5), Shape.Square(5), Shape.Empty)),
            "test_list_forEachIf_asType_partlySelected",
            Modification {
                it.shapes.forEachIf(
                    condition = { it neq Shape.Circle(1) },
                    modification = { it.asSquare.side += 1 }
                )
            }
        )
    }

    @Test
    fun test_updateMany_selectedByType() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<SealedPathTestModel>("SealedPathTestModel_test_updateMany_selectedByType"))
        val items = shapes.map { SealedPathTestModel(shape = it) }
        collection.insert(items)
        val condition = Condition<SealedPathTestModel> { it.shape isType Shape.Circle.serializer() }
        val modification = Modification<SealedPathTestModel> { it.shape.asCircle.radius += 10 }
        collection.updateMany(condition, modification)
        val expected = items.map { if (condition(it)) modification(it) else it }.sortedBy { it._id }
        assertEquals(expected, collection.find(Condition.Always).toList().sortedBy { it._id })
    }

    @Test
    fun test_sealedClass_asType_field_increment() = runTest {
        modificationTest(
            SealedPathTestModel(payment = Payment.Card(10, "1111")),
            "test_sealedClass_asType_field_increment",
            Modification { it.payment.asCard.amount += 5 }
        )
    }

    @Test
    fun test_sealedClass_topLevelVariant_field_assign() = runTest {
        modificationTest(
            SealedPathTestModel(payment = CashPayment(10, "USD")),
            "test_sealedClass_topLevelVariant_field_assign",
            Modification { it.payment.asCashPayment.currency assign "EUR" }
        )
    }

    @Test
    fun test_sealedClass_asType_assign() = runTest {
        modificationTest(
            SealedPathTestModel(payment = CashPayment(10, "USD")),
            "test_sealedClass_asType_assign",
            Modification { it.payment.asCashPayment assign CashPayment(99, "GBP") }
        )
    }

    @Test
    fun test_sealedClass_list_forEachIf_asType() = runTest {
        modificationTest(
            SealedPathTestModel(payments = listOf(Payment.Card(10), CashPayment(10), Payment.Pending, Payment.Card(3))),
            "test_sealedClass_list_forEachIf_asType",
            Modification {
                it.payments.forEachIf(
                    condition = { it isType Payment.Card.serializer() },
                    modification = { it.asCard.amount += 1 }
                )
            }
        )
    }

    // endregion
}
