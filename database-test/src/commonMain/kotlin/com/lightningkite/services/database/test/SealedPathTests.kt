package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Conditions, modifications, and sorts that project a sealed field onto one of its variants via `asType`.
 *
 * Modifications through a variant path follow the same standard as [DataClassPathNotNull]: backends aren't required
 * to enforce the type check, so modification tests only apply them to values that already are that variant (or
 * select those values with a condition first).
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
            condition { it.shape isType Shape.Circle.serializer() }
        )
    }

    @Test
    fun test_isType_object() = runTest {
        conditionTest(
            "test_isType_object",
            shapes.map { SealedPathTestModel(shape = it) },
            condition { it.shape isType Shape.Empty.serializer() }
        )
    }

    @Test
    fun test_asType_field_eq() = runTest {
        conditionTest(
            "test_asType_field_eq",
            shapes.map { SealedPathTestModel(shape = it) },
            condition { it.shape.asCircle.radius eq 2 }
        )
    }

    @Test
    fun test_asType_field_gt() = runTest {
        // Square(3) and Circle(3) share a value, so only the type check can tell them apart.
        conditionTest(
            "test_asType_field_gt",
            shapes.map { SealedPathTestModel(shape = it) },
            condition { it.shape.asSquare.side gt 2 }
        )
    }

    @Test
    fun test_asType_eq() = runTest {
        conditionTest(
            "test_asType_eq",
            shapes.map { SealedPathTestModel(shape = it) },
            condition { it.shape.asCircle eq Shape.Circle(2) }
        )
    }

    @Test
    fun test_asType_neq() = runTest {
        conditionTest(
            "test_asType_neq",
            shapes.map { SealedPathTestModel(shape = it) },
            condition { it.shape.asCircle neq Shape.Circle(2) }
        )
    }

    @Test
    fun test_notNull_asType() = runTest {
        conditionTest(
            "test_notNull_asType",
            (shapes + null).map { SealedPathTestModel(shapeNullable = it) },
            condition { it.shapeNullable.notNull.asCircle.radius eq 1 }
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
            condition { it.shapes.any { it.asCircle.radius eq 3 } }
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
            condition { it.shapes.all { it isType Shape.Circle.serializer() } }
        )
    }

    @Test
    fun test_sealedClass_isType() = runTest {
        conditionTest(
            "test_sealedClass_isType",
            payments.map { SealedPathTestModel(payment = it) },
            condition { it.payment isType Payment.Card.serializer() }
        )
    }

    @Test
    fun test_sealedClass_isType_object() = runTest {
        conditionTest(
            "test_sealedClass_isType_object",
            payments.map { SealedPathTestModel(payment = it) },
            condition { it.payment isType Payment.Pending.serializer() }
        )
    }

    @Test
    fun test_sealedClass_asType_field_eq() = runTest {
        // Card(10) and CashPayment(10) share an amount, so only the type check can tell them apart.
        conditionTest(
            "test_sealedClass_asType_field_eq",
            payments.map { SealedPathTestModel(payment = it) },
            condition { it.payment.asCard.amount eq 10 }
        )
    }

    @Test
    fun test_sealedClass_topLevelVariant_field_eq() = runTest {
        conditionTest(
            "test_sealedClass_topLevelVariant_field_eq",
            payments.map { SealedPathTestModel(payment = it) },
            condition { it.payment.asCashPayment.currency eq "EUR" }
        )
    }

    @Test
    fun test_sealedClass_asType_eq() = runTest {
        conditionTest(
            "test_sealedClass_asType_eq",
            payments.map { SealedPathTestModel(payment = it) },
            condition { it.payment.asCashPayment eq CashPayment(10, "USD") }
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
            condition { it.payments.any { it.asCard.amount eq 20 } }
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
        val fullOrder = orderBy + SortPart(path<SealedPathTestModel>()._id)
        val expected = items.filter { condition(it) }.sortedWith(fullOrder.comparator!!)
        assertEquals(expected, collection.find(condition, orderBy = fullOrder).toList())
    }

    @Test
    fun test_sort_asType_field() = runTest {
        sortTest(
            "test_sort_asType_field",
            shapes.map { SealedPathTestModel(shape = it) },
            listOf(SortPart(path<SealedPathTestModel>().shape.asCircle.radius))
        )
    }

    @Test
    fun test_sort_asType_field_descending() = runTest {
        sortTest(
            "test_sort_asType_field_descending",
            shapes.map { SealedPathTestModel(shape = it) },
            listOf(SortPart(path<SealedPathTestModel>().shape.asCircle.radius, ascending = false))
        )
    }

    @Test
    fun test_sort_asType_sharedFieldName() = runTest {
        sortTest(
            "test_sort_asType_sharedFieldName",
            payments.map { SealedPathTestModel(payment = it) },
            listOf(SortPart(path<SealedPathTestModel>().payment.asCard.amount)),
            condition { it.payment isType Payment.Card.serializer() }
        )
    }

    @Test
    fun test_sort_asType_sharedFieldName_descending() = runTest {
        sortTest(
            "test_sort_asType_sharedFieldName_descending",
            payments.map { SealedPathTestModel(payment = it) },
            listOf(SortPart(path<SealedPathTestModel>().payment.asCard.amount, ascending = false)),
            condition { it.payment isType Payment.Card.serializer() }
        )
    }

    @Test
    fun test_sort_notNull_asType_field() = runTest {
        sortTest(
            "test_sort_notNull_asType_field",
            (shapes + null).map { SealedPathTestModel(shapeNullable = it) },
            listOf(SortPart(path<SealedPathTestModel>().shapeNullable.notNull.asCircle.radius, ascending = false))
        )
    }

    @Test
    fun test_updateOne_sorted_asType_sharedFieldName() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<SealedPathTestModel>("SealedPathTestModel_test_updateOne_sorted_asType_sharedFieldName"))
        val items = payments.map { SealedPathTestModel(payment = it) }
        collection.insert(items)
        val orderBy = listOf(SortPart(path<SealedPathTestModel>().payment.asCard.amount, ascending = false))
        val modification = modification<SealedPathTestModel> { it.payment.asCard.last4 assign "9999" }
        // Only Cards are selected, so the unchecked modification only reaches the matching variant.
        val condition = condition<SealedPathTestModel> { it.payment isType Payment.Card.serializer() }
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
        val orderBy = listOf(SortPart(path<SealedPathTestModel>().payment.asCard.amount, ascending = false))
        val condition = condition<SealedPathTestModel> { it.payment isType Payment.Card.serializer() }
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
            modification { it.shape.asCircle.radius += 2 }
        )
    }

    @Test
    fun test_asType_field_assign() = runTest {
        modificationTest(
            SealedPathTestModel(shape = Shape.Square(2, "a")),
            "test_asType_field_assign",
            modification { it.shape.asSquare.label assign "changed" }
        )
    }

    @Test
    fun test_asType_assign() = runTest {
        modificationTest(
            SealedPathTestModel(shape = Shape.Circle(1)),
            "test_asType_assign",
            modification { it.shape.asCircle assign Shape.Circle(9) }
        )
    }

    @Test
    fun test_asType_multiple_fields() = runTest {
        modificationTest(
            SealedPathTestModel(shape = Shape.Square(2, "a")),
            "test_asType_multiple_fields",
            modification {
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
            modification { it.shapeNullable.notNull.asCircle.radius += 1 }
        )
    }

    @Test
    fun test_list_forEachIf_asType() = runTest {
        // The per-element condition selects the variant, so the unchecked modification only reaches matching elements.
        modificationTest(
            SealedPathTestModel(shapes = listOf(Shape.Circle(1), Shape.Square(1), Shape.Circle(5), Shape.Empty)),
            "test_list_forEachIf_asType",
            modification {
                it.shapes.forEachIf(
                    condition = { it isType Shape.Circle.serializer() },
                    modification = { it.asCircle.radius += 1 }
                )
            }
        )
    }

    @Test
    fun test_updateMany_selectedByType() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<SealedPathTestModel>("SealedPathTestModel_test_updateMany_selectedByType"))
        val items = shapes.map { SealedPathTestModel(shape = it) }
        collection.insert(items)
        val condition = condition<SealedPathTestModel> { it.shape isType Shape.Circle.serializer() }
        val modification = modification<SealedPathTestModel> { it.shape.asCircle.radius += 10 }
        collection.updateMany(condition, modification)
        val expected = items.map { if (condition(it)) modification(it) else it }.sortedBy { it._id }
        assertEquals(expected, collection.find(Condition.Always).toList().sortedBy { it._id })
    }

    @Test
    fun test_sealedClass_asType_field_increment() = runTest {
        modificationTest(
            SealedPathTestModel(payment = Payment.Card(10, "1111")),
            "test_sealedClass_asType_field_increment",
            modification { it.payment.asCard.amount += 5 }
        )
    }

    @Test
    fun test_sealedClass_topLevelVariant_field_assign() = runTest {
        modificationTest(
            SealedPathTestModel(payment = CashPayment(10, "USD")),
            "test_sealedClass_topLevelVariant_field_assign",
            modification { it.payment.asCashPayment.currency assign "EUR" }
        )
    }

    @Test
    fun test_sealedClass_asType_assign() = runTest {
        modificationTest(
            SealedPathTestModel(payment = CashPayment(10, "USD")),
            "test_sealedClass_asType_assign",
            modification { it.payment.asCashPayment assign CashPayment(99, "GBP") }
        )
    }

    @Test
    fun test_sealedClass_list_forEachIf_asType() = runTest {
        modificationTest(
            SealedPathTestModel(payments = listOf(Payment.Card(10), CashPayment(10), Payment.Pending, Payment.Card(3))),
            "test_sealedClass_list_forEachIf_asType",
            modification {
                it.payments.forEachIf(
                    condition = { it isType Payment.Card.serializer() },
                    modification = { it.asCard.amount += 1 }
                )
            }
        )
    }

    // endregion
}
