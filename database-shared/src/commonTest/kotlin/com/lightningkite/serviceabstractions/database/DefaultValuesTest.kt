package com.lightningkite.serviceabstractions.database

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
data class ModelWithDefaults(
    val id: Uuid = Uuid.random(),
    val name: String,
    val age: Int = 18,
    val status: String = "active",
    val score: Double = 0.0,
    val enabled: Boolean = true,
    val tags: List<String> = emptyList()
)

@Serializable
@GenerateDataClassPaths
data class ModelWithoutDefaults(
    val id: Uuid,
    val name: String,
    val age: Int
)

@Serializable
@GenerateDataClassPaths
data class GenericModelWithDefaults<T>(
    val value: T,
    val count: Int = 42,
    val label: String = "default"
)

class DefaultValuesTest {

    @Test
    fun nope() {
        val ser: KSerializer<*> = ModelWithDefaults.serializer()
        assertTrue(ser.serializableProperties!!.any { it.default != null })
    }

    @Test
    fun testSimpleDefaults() {
        // Test using the generated top-level properties (which have defaults embedded)
        // id has complex default (Uuid.random())
        assertEquals("id", ModelWithDefaults_id.name)
        assertNull(ModelWithDefaults_id.default, "Complex defaults should have null value")
        assertNotNull(ModelWithDefaults_id.defaultCode, "Complex defaults should have code")
        assertEquals("Uuid.random()", ModelWithDefaults_id.defaultCode)

        // name has no default
        assertEquals("name", ModelWithDefaults_name.name)
        assertNull(ModelWithDefaults_name.default)
        assertNull(ModelWithDefaults_name.defaultCode)

        // age has simple Int default
        assertEquals("age", ModelWithDefaults_age.name)
        assertEquals(18, ModelWithDefaults_age.default)
        assertEquals("18", ModelWithDefaults_age.defaultCode)

        // status has simple String default
        assertEquals("status", ModelWithDefaults_status.name)
        assertEquals("active", ModelWithDefaults_status.default)
        assertEquals("\"active\"", ModelWithDefaults_status.defaultCode)

        // score has simple Double default
        assertEquals("score", ModelWithDefaults_score.name)
        assertEquals(0.0, ModelWithDefaults_score.default)
        assertEquals("0.0", ModelWithDefaults_score.defaultCode)

        // enabled has simple Boolean default
        assertEquals("enabled", ModelWithDefaults_enabled.name)
        assertEquals(true, ModelWithDefaults_enabled.default)
        assertEquals("true", ModelWithDefaults_enabled.defaultCode)

        // tags has complex default (emptyList())
        assertEquals("tags", ModelWithDefaults_tags.name)
        assertNull(ModelWithDefaults_tags.default, "Complex defaults should have null value")
        assertNotNull(ModelWithDefaults_tags.defaultCode, "Complex defaults should have code")
        assertEquals("emptyList()", ModelWithDefaults_tags.defaultCode)

        // Also verify we can access them via the custom array
        val properties = ModelWithDefaults__serializableProperties
        assertEquals(7, properties.size)
        assertEquals(18, properties[2].default)  // age
        assertEquals("active", properties[3].default)  // status
    }

    @Test
    fun testNoDefaults() {
        val properties = ModelWithoutDefaults.serializer().serializableProperties!!

        properties.forEach { prop ->
            assertNull(prop.default, "Property ${prop.name} should have no default")
            assertNull(prop.defaultCode, "Property ${prop.name} should have no default code")
        }
    }

    @Test
    fun testGenericTypeDefaults() {
        val serializer = GenericModelWithDefaults.serializer(String.serializer())

        // For generic types, properties come from the serializer's extension properties
        // Access via the generated fieldXxx properties
        val countProp = serializer.fieldCount
        assertEquals("count", countProp.name)
        assertEquals(42, countProp.default)
        assertEquals("42", countProp.defaultCode)

        val labelProp = serializer.fieldLabel
        assertEquals("label", labelProp.name)
        assertEquals("default", labelProp.default)
        assertEquals("\"default\"", labelProp.defaultCode)
    }
}
