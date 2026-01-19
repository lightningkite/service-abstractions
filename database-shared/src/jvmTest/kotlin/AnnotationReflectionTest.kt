// by Claude - Test for reflection-based annotation parsing
import com.lightningkite.services.data.*
import com.lightningkite.services.database.SerializableAnnotation
import com.lightningkite.services.database.SerializableAnnotationValue
import com.lightningkite.services.database.VectorIndex
import com.lightningkite.services.database.SimilarityMetric
import com.lightningkite.services.database.SerializationRegistry
import com.lightningkite.services.database.VirtualStruct
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AnnotationReflectionTest {

    @Serializable
    @Description("A test model with various annotations")
    @NaturalSort(["name", "-createdAt"])
    data class AnnotatedModel(
        @Index(unique = IndexUniqueness.Unique)
        val id: String,

        @Description("The name field")
        @MaxLength(size = 100)
        val name: String,

        @IntegerRange(min = 0, max = 150)
        val age: Int,

        @AdminHidden
        val internalField: String = ""
    )

    @Test
    fun `parseOrNull returns annotation for Index`() {
        val descriptor = serializer<AnnotatedModel>().descriptor
        val idFieldAnnotations = descriptor.getElementAnnotations(0) // id field

        val indexAnnotation = idFieldAnnotations.find { it.toString().contains("Index") }
        assertNotNull(indexAnnotation, "Index annotation should exist on id field")

        val parsed = SerializableAnnotation.parseOrNull(indexAnnotation)
        assertNotNull(parsed, "parseOrNull should return non-null for @Index")
        assertEquals("com.lightningkite.services.data.Index", parsed.fqn)
        assertTrue(parsed.values.containsKey("unique"), "Should have 'unique' property")
        assertEquals(
            SerializableAnnotationValue.StringValue("Unique"),
            parsed.values["unique"],
            "unique should be 'Unique'"
        )
    }

    @Test
    fun `parseOrNull returns annotation for Description`() {
        val descriptor = serializer<AnnotatedModel>().descriptor
        val classAnnotations = descriptor.annotations

        val descriptionAnnotation = classAnnotations.find { it.toString().contains("Description") }
        assertNotNull(descriptionAnnotation, "Description annotation should exist on class")

        val parsed = SerializableAnnotation.parseOrNull(descriptionAnnotation)
        assertNotNull(parsed, "parseOrNull should return non-null for @Description")
        assertEquals("com.lightningkite.services.data.Description", parsed.fqn)
        assertEquals(
            SerializableAnnotationValue.StringValue("A test model with various annotations"),
            parsed.values["text"]
        )
    }

    @Test
    fun `parseOrNull returns annotation for IntegerRange`() {
        val descriptor = serializer<AnnotatedModel>().descriptor
        val ageFieldAnnotations = descriptor.getElementAnnotations(2) // age field

        val rangeAnnotation = ageFieldAnnotations.find { it.toString().contains("IntegerRange") }
        assertNotNull(rangeAnnotation, "IntegerRange annotation should exist on age field")

        val parsed = SerializableAnnotation.parseOrNull(rangeAnnotation)
        assertNotNull(parsed, "parseOrNull should return non-null for @IntegerRange")
        assertEquals("com.lightningkite.services.data.IntegerRange", parsed.fqn)
        assertEquals(SerializableAnnotationValue.LongValue(0), parsed.values["min"])
        assertEquals(SerializableAnnotationValue.LongValue(150), parsed.values["max"])
    }

    @Test
    fun `parseOrNull returns annotation for MaxLength`() {
        val descriptor = serializer<AnnotatedModel>().descriptor
        val nameFieldAnnotations = descriptor.getElementAnnotations(1) // name field

        val maxLengthAnnotation = nameFieldAnnotations.find { it.toString().contains("MaxLength") }
        assertNotNull(maxLengthAnnotation, "MaxLength annotation should exist on name field")

        val parsed = SerializableAnnotation.parseOrNull(maxLengthAnnotation)
        assertNotNull(parsed, "parseOrNull should return non-null for @MaxLength")
        assertEquals("com.lightningkite.services.data.MaxLength", parsed.fqn)
        assertEquals(SerializableAnnotationValue.IntValue(100), parsed.values["size"])
    }

    @Test
    fun `parseOrNull returns annotation for AdminHidden (no properties)`() {
        val descriptor = serializer<AnnotatedModel>().descriptor
        val internalFieldAnnotations = descriptor.getElementAnnotations(3) // internalField

        val hiddenAnnotation = internalFieldAnnotations.find { it.toString().contains("AdminHidden") }
        assertNotNull(hiddenAnnotation, "AdminHidden annotation should exist on internalField")

        val parsed = SerializableAnnotation.parseOrNull(hiddenAnnotation)
        assertNotNull(parsed, "parseOrNull should return non-null for @AdminHidden")
        assertEquals("com.lightningkite.services.data.AdminHidden", parsed.fqn)
        assertTrue(parsed.values.isEmpty(), "AdminHidden should have no properties")
    }

    @Test
    fun `parseOrNull returns annotation for NaturalSort (array property)`() {
        val descriptor = serializer<AnnotatedModel>().descriptor
        val classAnnotations = descriptor.annotations

        val naturalSortAnnotation = classAnnotations.find { it.toString().contains("NaturalSort") }
        assertNotNull(naturalSortAnnotation, "NaturalSort annotation should exist on class")

        val parsed = SerializableAnnotation.parseOrNull(naturalSortAnnotation)
        assertNotNull(parsed, "parseOrNull should return non-null for @NaturalSort")
        assertEquals("com.lightningkite.services.data.NaturalSort", parsed.fqn)

        val fieldsValue = parsed.values["fields"]
        assertTrue(fieldsValue is SerializableAnnotationValue.ArrayValue, "fields should be an ArrayValue")
        val arrayValue = fieldsValue as SerializableAnnotationValue.ArrayValue
        assertEquals(2, arrayValue.value.size)
        assertEquals(SerializableAnnotationValue.StringValue("name"), arrayValue.value[0])
        assertEquals(SerializableAnnotationValue.StringValue("-createdAt"), arrayValue.value[1])
    }

    // by Claude - Test models for References annotation
    @Serializable
    data class ReferencedModel(
        override val _id: Uuid = Uuid.random(),
        val name: String
    ) : HasId<Uuid>

    @Serializable
    data class ModelWithForeignKey(
        override val _id: Uuid = Uuid.random(),
        @References(ReferencedModel::class) val referencedId: Uuid,
        val description: String
    ) : HasId<Uuid>

    // by Claude - Test that serializableProperties includes References annotation
    @Test
    fun `serializableProperties includes References annotation`() {
        val ser = serializer<ModelWithForeignKey>()
        val props = ser.serializableProperties
        assertNotNull(props, "serializableProperties should not be null")

        val referencedIdProp = props.find { it.name == "referencedId" }
        assertNotNull(referencedIdProp, "referencedId property should exist")

        val annotations = referencedIdProp.serializableAnnotations
        assertTrue(annotations.isNotEmpty(), "referencedId should have annotations")

        val referencesAnnotation = annotations.find { it.fqn == "com.lightningkite.services.data.References" }
        assertNotNull(referencesAnnotation, "References annotation should exist on referencedId")
        assertTrue(referencesAnnotation.values.containsKey("references"), "Should have 'references' property")
    }

    // by Claude - Test that registerVirtualDeep includes References annotation in VirtualStruct fields
    @Test
    fun `registerVirtualDeep includes References annotation in VirtualStruct fields`() {
        val registry = SerializationRegistry(EmptySerializersModule())
        val ser = serializer<ModelWithForeignKey>()
        registry.registerVirtualDeep(ser)

        // Use the actual serial name as the key
        val serialName = ser.descriptor.serialName
        val virtualType = registry.virtualTypes[serialName]
        assertNotNull(virtualType, "VirtualType should be registered with key '$serialName'. Available keys: ${registry.virtualTypes.keys}")
        assertTrue(virtualType is VirtualStruct, "Should be registered as VirtualStruct")
        val struct = virtualType as VirtualStruct

        val referencedIdField = struct.fields.find { it.name == "referencedId" }
        assertNotNull(referencedIdField, "referencedId field should exist in VirtualStruct")

        val annotations = referencedIdField.annotations
        assertTrue(annotations.isNotEmpty(), "referencedId field should have annotations in VirtualStruct")

        val referencesAnnotation = annotations.find { it.fqn == "com.lightningkite.services.data.References" }
        assertNotNull(referencesAnnotation, "References annotation should exist in VirtualStruct field")

        val referencesValue = referencesAnnotation.values["references"]
        assertTrue(referencesValue is SerializableAnnotationValue.ClassValue, "references should be a ClassValue")
        assertTrue(
            (referencesValue as SerializableAnnotationValue.ClassValue).fqn.contains("ReferencedModel"),
            "references should point to ReferencedModel"
        )
    }
}
