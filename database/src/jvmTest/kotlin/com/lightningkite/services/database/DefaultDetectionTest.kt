// by Claude - Tests for dynamic default detection in SerializableProperty.Generated
package com.lightningkite.services.database

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Test model for default detection with various default types
 */
@Serializable
data class TestDefaultsModel(
    val id: Uuid = Uuid.random(),             // Dynamic default - should detect "Uuid.random()"
    val created: Instant = Clock.System.now(), // Dynamic default - should detect "Clock.System.now()"
    val name: String = "default",             // Static default - should have default="default"
    val count: Int = 42,                      // Static default - should have default=42
    val required: String,                     // No default - should have default=null
)

class DefaultDetectionTest {

    @Test
    fun `detect Uuid random default`() {
        val props = serializer<TestDefaultsModel>().serializableProperties!!
        val idProp = props.find { it.name == "id" }!!

        assertEquals("Uuid.random()", idProp.defaultCode, "Should detect Uuid.random() default")
        assertNull(idProp.default, "Dynamic defaults should not have a static value")
    }

    @Test
    fun `detect Clock System now default`() {
        val props = serializer<TestDefaultsModel>().serializableProperties!!
        val createdProp = props.find { it.name == "created" }!!

        assertEquals("Clock.System.now()", createdProp.defaultCode, "Should detect Clock.System.now() default")
        assertNull(createdProp.default, "Dynamic defaults should not have a static value")
    }

    @Test
    fun `detect static String default`() {
        val props = serializer<TestDefaultsModel>().serializableProperties!!
        val nameProp = props.find { it.name == "name" }!!

        assertNull(nameProp.defaultCode, "Static defaults should not have defaultCode")
        assertEquals("default", nameProp.default, "Should capture static String default value")
    }

    @Test
    fun `detect static Int default`() {
        val props = serializer<TestDefaultsModel>().serializableProperties!!
        val countProp = props.find { it.name == "count" }!!

        assertNull(countProp.defaultCode, "Static defaults should not have defaultCode")
        assertEquals(42, countProp.default, "Should capture static Int default value")
    }

    @Test
    fun `required field has no default`() {
        val props = serializer<TestDefaultsModel>().serializableProperties!!
        val requiredProp = props.find { it.name == "required" }!!

        assertNull(requiredProp.defaultCode, "Required fields should not have defaultCode")
        assertNull(requiredProp.default, "Required fields should not have default value")
    }
}
