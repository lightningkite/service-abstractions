@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.optics.IdentityLens
import com.lightningkite.services.database.optics.Optic
import com.lightningkite.services.database.optics.elements
import com.lightningkite.services.database.optics.notNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VetTest {
    private fun <K, V> Modification<K>.vetted(path: Optic<K, V>): List<Modification<V>> =
        buildList { vet(path) { add(it) } }

    @Test
    fun assign_on_the_path_is_reported() {
        val mod = Modification<VetRoot> { it.name assign "new" }
        assertEquals(listOf(Modification.Assign("new")), mod.vetted(VetRoot.name))
    }

    @Test
    fun other_modifications_on_the_path_are_reported_as_is() {
        val mod = Modification<VetRoot> { it.inner.value += 2 }
        assertEquals(listOf(Modification.Increment(2)), mod.vetted(VetRoot.inner.value))
    }

    @Test
    fun modifications_on_other_fields_are_ignored() {
        val mod = Modification<VetRoot> {
            it.name assign "new"
            it.inner.label assign "other"
        }
        assertEquals(emptyList<Modification<Int>>(), mod.vetted(VetRoot.inner.value))
    }

    @Test
    fun nested_assign_on_the_path_is_reported() {
        val mod = Modification<VetRoot> { it.inner.value assign 3 }
        assertEquals(listOf(Modification.Assign(3)), mod.vetted(VetRoot.inner.value))
    }

    @Test
    fun assign_on_a_parent_reports_the_value_at_the_path() {
        val mod = Modification<VetRoot> { it.inner assign VetInner(value = 7, label = "seven") }
        assertEquals(listOf(Modification.Assign(7)), mod.vetted(VetRoot.inner.value))
    }

    @Test
    fun assign_on_the_whole_model_reports_the_value_at_the_path() {
        val mod: Modification<VetRoot> = Modification.Assign(VetRoot(name = "replaced", inner = VetInner(value = 4)))
        assertEquals(listOf(Modification.Assign(4)), mod.vetted(VetRoot.inner.value))
    }

    @Test
    fun every_modification_in_a_chain_is_reported_in_order() {
        val mod = Modification<VetRoot> {
            it.inner.value assign 1
            it.name assign "between"
            it.inner.value += 5
        }
        assertEquals(
            listOf(Modification.Assign(1), Modification.Increment(5)),
            mod.vetted(VetRoot.inner.value),
        )
    }

    @Test
    fun modifications_through_a_nullable_field_are_reported() {
        val mod = Modification<VetRoot> { it.maybe.notNull.value assign 9 }
        assertEquals(listOf(Modification.Assign(9)), mod.vetted(VetRoot.maybe.notNull.value))
    }

    @Test
    fun other_modifications_through_a_nullable_field_are_reported_as_is() {
        val mod = Modification<VetRoot> { it.maybe.notNull.value += 2 }
        assertEquals<List<Any?>>(listOf(Modification.Increment(2)), mod.vetted(VetRoot.maybe.notNull.value))
    }

    @Test
    fun assign_on_a_nullable_parent_reports_the_value_at_the_path() {
        val mod = Modification<VetRoot> { it.maybe assign VetInner(value = 6) }
        assertEquals(listOf(Modification.Assign(6)), mod.vetted(VetRoot.maybe.notNull.value))
    }

    @Test
    fun assigning_null_to_a_nullable_parent_is_not_reported() {
        val mod = Modification<VetRoot> { it.maybe assign null }
        assertEquals(listOf<Modification<Int>>(), mod.vetted(VetRoot.maybe.notNull.value))
    }

    @Test
    fun an_identity_path_reports_every_part() {
        val mod = Modification<VetRoot> {
            it.name assign "new"
            it.inner.value += 1
        }
        assertEquals((mod as Modification.Chain).modifications, mod.vetted(IdentityLens(VetRoot.serializer())))
    }

    @Test
    fun assigning_a_collection_reports_each_element() {
        val mod = Modification<VetRoot> { it.tags assign listOf("a", "b") }
        assertEquals(listOf(Modification.Assign("a"), Modification.Assign("b")), mod.vetted(VetRoot.tags.elements))
    }

    @Test
    fun a_change_to_every_element_is_reported() {
        val mod = VetRoot.tags.elements.mapModification(Modification.Assign("x"))
        assertEquals(listOf(Modification.Assign("x")), mod.vetted(VetRoot.tags.elements))
    }

    @Test
    fun appended_elements_are_reported_as_assignments() {
        val mod = VetRoot.tags.mapModification(Modification.ListAppend(listOf("c")))
        assertEquals(listOf(Modification.Assign("c")), mod.vetted(VetRoot.tags.elements))
    }

    @Test
    fun removed_elements_are_not_reported() {
        val mod = VetRoot.tags.mapModification(Modification.ListRemoveInstances(listOf("a")))
        assertEquals(listOf<Modification<String>>(), mod.vetted(VetRoot.tags.elements))
    }

    @Test
    fun set_elements_are_followed() {
        val mod = VetRoot.labels.mapModification(Modification.SetAppend(setOf("d")))
        assertEquals(listOf(Modification.Assign("d")), mod.vetted(VetRoot.labels.elements))
    }

    @Test
    fun writes_under_the_variant_are_reported() {
        val mod = Modification<Holder> { it.thing.asFoo.name assign "b" }
        assertEquals(listOf(Modification.Assign("b")), mod.vetted(Holder.path.thing.asFoo.name))
    }

    @Test
    fun writes_under_another_variant_are_ignored() {
        val mod = Modification<Holder> { it.thing.asBar.id assign 2 }
        assertEquals(listOf<Modification<String>>(), mod.vetted(Holder.path.thing.asFoo.name))
    }

    @Test
    fun assigning_a_variant_reports_its_value_and_another_variant_is_not_reported() {
        val foo = Modification<Holder> { it.thing assign Polymorphic.Foo("c") }
        val bar = Modification<Holder> { it.thing assign Polymorphic.Bar(1) }
        assertEquals(listOf(Modification.Assign("c")), foo.vetted(Holder.path.thing.asFoo.name))
        assertEquals(listOf<Modification<String>>(), bar.vetted(Holder.path.thing.asFoo.name))
    }

    @Test
    fun assigning_a_sealed_value_reports_its_shared_field() {
        val mod: Modification<Shape> = Modification.Assign(Shape.Circle("circ"))
        assertEquals(listOf(Modification.Assign("circ")), mod.vetted(Shape.label()))
    }

    @Test
    fun a_write_to_a_shared_field_is_reported_once_per_variant() {
        val mod = Shape.path.label.mapModification(Modification.Assign("x"))
        assertEquals(List(3) { Modification.Assign("x") }, mod.vetted(Shape.label()))
    }
}

class projectionTest {
    @Test
    fun parts_are_combined_in_order() {
        val mod = Modification<VetRoot> {
            it.inner.value assign 1
            it.name assign "between"
            it.inner.value += 5
        }
        assertEquals(
            Modification.Chain(listOf(Modification.Assign(1), Modification.Increment(5))),
            mod.projection(VetRoot.inner.value),
        )
    }

    @Test
    fun a_single_part_is_returned_as_is() {
        val mod = Modification<VetRoot> { it.inner.value += 2 }
        assertEquals(Modification.Increment(2), mod.projection(VetRoot.inner.value))
    }

    @Test
    fun an_unaffected_path_returns_null() {
        val mod = Modification<VetRoot> { it.name assign "new" }
        assertEquals(null, mod.projection(VetRoot.inner.value))
    }

    @Test
    fun the_result_gives_the_new_value_of_the_target() {
        val before = VetRoot(inner = VetInner(value = 1))
        val mod = Modification<VetRoot> {
            it.inner assign VetInner(value = 7)
            it.inner.value += 3
        }
        assertEquals(mod(before).inner.value, mod.projection(VetRoot.inner.value)!!(before.inner.value))
    }

    @Test
    fun paths_through_a_variant_are_supported() {
        val mod = Modification<Holder> { it.thing.asFoo.name assign "b" }
        assertEquals(Modification.Assign("b"), mod.projection(Holder.path.thing.asFoo.name))
    }

    @Test
    fun collection_elements_are_rejected() {
        val mod = Modification<VetRoot> { it.tags assign listOf("a", "b") }
        assertFailsWith<IllegalArgumentException> { mod.projection(VetRoot.tags.elements) }
    }

    @Test
    fun shared_sealed_fields_are_rejected() {
        val mod: Modification<Shape> = Modification.Assign(Shape.Circle("circ"))
        assertFailsWith<IllegalArgumentException> { mod.projection(Shape.label()) }
    }
}

@Queryable
data class VetRoot(
    val name: String = "",
    val inner: VetInner = VetInner(),
    val maybe: VetInner? = null,
    val tags: List<String> = listOf(),
    val labels: Set<String> = setOf(),
)

@Queryable
data class VetInner(
    val value: Int = 0,
    val label: String = "",
)
