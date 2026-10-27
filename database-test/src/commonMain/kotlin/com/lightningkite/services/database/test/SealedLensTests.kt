package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import com.lightningkite.services.database.optics.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Conditions, modifications and sorts through a lens that a sealed type's variants all share, such as
 * `Notification.title`.
 *
 * The expected results always come from applying the same condition or modification in memory.
 */
abstract class SealedLensTests {
    abstract val database: Database

    private val notifications = listOf(
        Notification.Email("welcome", 1, true, "a@example.com"),
        Notification.Email("reset", 5, false, "b@example.com"),
        Notification.Sms("welcome", 5, "555-0100"),
        Notification.Sms("code", 2, "555-0101"),
        Notification.Push("welcome", 3, false),
        Notification.Push("promo", 1, true),
    )

    private suspend fun conditionTest(name: String, items: List<SealedLensTestModel>, condition: Condition<SealedLensTestModel>) {
        val collection = database.prepare(DatabaseTableDefinition<SealedLensTestModel>("SealedLensTestModel_$name"))
        collection.insert(items)
        val expected = items.filter { condition(it) }.sortedBy { it._id }
        val results = collection.find(condition).toList().sortedBy { it._id }
        assertEquals(expected, results)
    }

    private suspend fun modificationTest(item: SealedLensTestModel, name: String, modification: Modification<SealedLensTestModel>) {
        val collection = database.prepare(DatabaseTableDefinition<SealedLensTestModel>("SealedLensTestModel_$name"))
        collection.insertOne(item)
        collection.updateOneById(item._id, modification)
        assertEquals(modification(item), collection.get(item._id))
    }

    // region conditions

    @Test
    fun test_shared_eq() = runTest {
        conditionTest(
            "test_shared_eq",
            notifications.map { SealedLensTestModel(notification = it) },
            Condition { it.notification.title eq "welcome" }
        )
    }

    @Test
    fun test_shared_gt() = runTest {
        conditionTest(
            "test_shared_gt",
            notifications.map { SealedLensTestModel(notification = it) },
            Condition { it.notification.priority gt 2 }
        )
    }

    @Test
    fun test_shared_neq() = runTest {
        conditionTest(
            "test_shared_neq",
            notifications.map { SealedLensTestModel(notification = it) },
            Condition { it.notification.title neq "welcome" }
        )
    }

    @Test
    fun test_shared_inside() = runTest {
        conditionTest(
            "test_shared_inside",
            notifications.map { SealedLensTestModel(notification = it) },
            Condition { it.notification.title inside listOf("code", "promo") }
        )
    }

    @Test
    fun test_shared_not() = runTest {
        conditionTest(
            "test_shared_not",
            notifications.map { SealedLensTestModel(notification = it) },
            Condition { !(it.notification.priority eq 5) }
        )
    }

    @Test
    fun test_shared_and() = runTest {
        conditionTest(
            "test_shared_and",
            notifications.map { SealedLensTestModel(notification = it) },
            Condition { (it.notification.title eq "welcome") and (it.notification.priority gte 3) }
        )
    }

    @Test
    fun test_shared_or() = runTest {
        conditionTest(
            "test_shared_or",
            notifications.map { SealedLensTestModel(notification = it) },
            Condition { (it.notification.title eq "promo") or (it.notification.priority eq 5) }
        )
    }

    @Test
    fun test_shared_withVariantCondition() = runTest {
        conditionTest(
            "test_shared_withVariantCondition",
            notifications.map { SealedLensTestModel(notification = it) },
            Condition { (it.notification isType Notification.Sms.serializer()) and (it.notification.title eq "welcome") }
        )
    }

    @Test
    fun test_shared_stringContains() = runTest {
        conditionTest(
            "test_shared_stringContains",
            notifications.map { SealedLensTestModel(notification = it) },
            Condition { it.notification.title.contains("e", ignoreCase = true) }
        )
    }

    @Test
    fun test_shared_notNull() = runTest {
        conditionTest(
            "test_shared_notNull",
            (notifications + null).map { SealedLensTestModel(notificationNullable = it) },
            Condition { it.notificationNullable.notNull.title eq "welcome" }
        )
    }

    @Test
    fun test_shared_list_any() = runTest {
        conditionTest(
            "test_shared_list_any",
            listOf(
                SealedLensTestModel(notifications = listOf(Notification.Sms("code", 2), Notification.Push("promo", 1))),
                SealedLensTestModel(notifications = listOf(Notification.Email("welcome", 1), Notification.Push("promo", 1))),
                SealedLensTestModel(notifications = listOf()),
            ),
            Condition { it.notifications.any { it.title eq "promo" } }
        )
    }

    @Test
    fun test_shared_list_all() = runTest {
        conditionTest(
            "test_shared_list_all",
            listOf(
                SealedLensTestModel(notifications = listOf(Notification.Sms("a", 2), Notification.Push("b", 3))),
                SealedLensTestModel(notifications = listOf(Notification.Email("c", 1), Notification.Push("d", 3))),
                SealedLensTestModel(notifications = listOf()),
            ),
            Condition { it.notifications.all { it.priority gte 2 } }
        )
    }

    // endregion

    // region sorting

    private suspend fun sortTest(
        name: String,
        items: List<SealedLensTestModel>,
        orderBy: List<SortPart<SealedLensTestModel>>,
    ) {
        val collection = database.prepare(DatabaseTableDefinition<SealedLensTestModel>("SealedLensTestModel_$name"))
        collection.insert(items)
        // `_id` breaks ties so the order is fully defined.
        val fullOrder = orderBy + SortPart(Lens<SealedLensTestModel>()._id)
        val expected = items.sortedWith(fullOrder.comparator!!)
        assertEquals(expected, collection.find(Condition.Always, orderBy = fullOrder).toList())
    }

    @Test
    fun test_sort_shared() = runTest {
        sortTest(
            "test_sort_shared",
            notifications.map { SealedLensTestModel(notification = it) },
            listOf(SortPart(Lens<SealedLensTestModel>().notification.priority))
        )
    }

    @Test
    fun test_sort_shared_descending() = runTest {
        sortTest(
            "test_sort_shared_descending",
            notifications.map { SealedLensTestModel(notification = it) },
            listOf(SortPart(Lens<SealedLensTestModel>().notification.title, ascending = false))
        )
    }

    // endregion

    // region modifications

    @Test
    fun test_shared_assign() = runTest {
        for ((index, notification) in notifications.withIndex()) {
            modificationTest(
                SealedLensTestModel(notification = notification),
                "test_shared_assign_$index",
                Modification { it.notification.title assign "changed" }
            )
        }
    }

    @Test
    fun test_shared_increment() = runTest {
        for ((index, notification) in notifications.withIndex()) {
            modificationTest(
                SealedLensTestModel(notification = notification),
                "test_shared_increment_$index",
                Modification { it.notification.priority += 10 }
            )
        }
    }

    @Test
    fun test_shared_multiple_fields() = runTest {
        modificationTest(
            SealedLensTestModel(notification = Notification.Sms("code", 2, "555-0101")),
            "test_shared_multiple_fields",
            Modification {
                it.notification.title assign "changed"
                it.notification.priority += 1
            }
        )
    }

    @Test
    fun test_shared_withVariantModification() = runTest {
        modificationTest(
            SealedLensTestModel(notification = Notification.Email("a", 1, true, "x@example.com")),
            "test_shared_withVariantModification",
            Modification {
                it.notification.title assign "changed"
                it.notification.asEmail.address assign "y@example.com"
            }
        )
    }

    @Test
    fun test_shared_notNull_assign() = runTest {
        modificationTest(
            SealedLensTestModel(notificationNullable = Notification.Push("promo", 1)),
            "test_shared_notNull_assign",
            Modification { it.notificationNullable.notNull.title assign "changed" }
        )
    }

    @Test
    fun test_shared_notNull_assign_null() = runTest {
        modificationTest(
            SealedLensTestModel(notificationNullable = null),
            "test_shared_notNull_assign_null",
            Modification { it.notificationNullable.notNull.title assign "changed" }
        )
    }

    @Test
    fun test_shared_list_forEach() = runTest {
        modificationTest(
            SealedLensTestModel(notifications = notifications),
            "test_shared_list_forEach",
            Modification { it.notifications.forEach { it.priority += 1 } }
        )
    }

    @Test
    fun test_shared_list_forEachIf() = runTest {
        modificationTest(
            SealedLensTestModel(notifications = notifications),
            "test_shared_list_forEachIf",
            Modification {
                it.notifications.forEachIf(
                    condition = { it.title eq "welcome" },
                    modification = { it.priority assign 0 }
                )
            }
        )
    }

    @Test
    fun test_shared_updateMany_selectedByShared() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<SealedLensTestModel>("SealedLensTestModel_test_shared_updateMany_selectedByShared"))
        val items = notifications.map { SealedLensTestModel(notification = it) }
        collection.insert(items)
        val condition = Condition<SealedLensTestModel> { it.notification.title eq "welcome" }
        val modification = Modification<SealedLensTestModel> { it.notification.priority += 100 }
        collection.updateMany(condition, modification)
        val expected = items.map { if (condition(it)) modification(it) else it }.sortedBy { it._id }
        assertEquals(expected, collection.find(Condition.Always).toList().sortedBy { it._id })
    }

    // endregion
}
