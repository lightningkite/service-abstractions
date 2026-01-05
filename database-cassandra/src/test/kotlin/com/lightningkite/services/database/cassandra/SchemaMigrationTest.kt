package com.lightningkite.services.database.cassandra

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// Version 1: Just name
@Serializable
data class PersonV1(
    override val _id: Uuid = Uuid.random(),
    val name: String
) : HasId<Uuid>

// Version 2: Added age field
@Serializable
data class PersonV2(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val age: Int? = null
) : HasId<Uuid>

// Version 3: Removed age, back to just name
@Serializable
data class PersonV3(
    override val _id: Uuid = Uuid.random(),
    val name: String
) : HasId<Uuid>

/**
 * Integration test for schema migration capabilities.
 */
class SchemaMigrationTest {

    @Test
    fun testAddingField() = runTest {
        val database = embeddedCassandra(
            name = "test_migration",
            context = TestSettingContext()
        )

        // Start with V1 schema
        val tableV1 = database.table<PersonV1>("person_add_field")
        tableV1.deleteManyIgnoringOld(Condition.Always)

        val alice = PersonV1(name = "Alice")
        tableV1.insertOne(alice)

        // Verify we can read it back
        val peopleV1 = tableV1.find(Condition.Always).toList()
        assertEquals(1, peopleV1.size)
        assertEquals("Alice", peopleV1[0].name)

        // Upgrade to V2 (adds age field)
        val tableV2 = database.table<PersonV2>("person_add_field")

        // Old data should be readable with null age
        val peopleV2 = tableV2.find(Condition.Always).toList()
        assertEquals(1, peopleV2.size)
        assertEquals("Alice", peopleV2[0].name)
        assertEquals(null, peopleV2[0].age, "Old data should have null age")

        // Insert new data with age
        val bob = PersonV2(name = "Bob", age = 30)
        tableV2.insertOne(bob)

        // Should have both records
        val allPeopleV2 = tableV2.find(Condition.Always).toList()
        assertEquals(2, allPeopleV2.size)

        println("✅ testAddingField passed!")
    }

    @Test
    fun testRemovingField() = runTest {
        val database = embeddedCassandra(
            name = "test_migration",
            context = TestSettingContext()
        )

        // Start with V2 schema (has age)
        val tableV2 = database.table<PersonV2>("person_remove_field")
        tableV2.deleteManyIgnoringOld(Condition.Always)

        val charlie = PersonV2(name = "Charlie", age = 25)
        tableV2.insertOne(charlie)

        // Verify we can read it
        val peopleV2 = tableV2.find(Condition.Always).toList()
        assertEquals(1, peopleV2.size)
        assertEquals("Charlie", peopleV2[0].name)
        assertEquals(25, peopleV2[0].age)

        // Downgrade to V3 (removes age field)
        val tableV3 = database.table<PersonV3>("person_remove_field")

        // Old data should still be readable (age column ignored)
        val peopleV3 = tableV3.find(Condition.Always).toList()
        assertEquals(1, peopleV3.size)
        assertEquals("Charlie", peopleV3[0].name)

        // Insert new data without age
        val diana = PersonV3(name = "Diana")
        tableV3.insertOne(diana)

        // Should have both records
        val allPeopleV3 = tableV3.find(Condition.Always).toList()
        assertEquals(2, allPeopleV3.size)

        println("✅ testRemovingField passed!")
    }
}
