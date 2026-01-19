// by Claude
package com.lightningkite.services.database.mapformat

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapFormatTest {

    // ========== Test Models ==========

    @Serializable
    data class SimplePrimitives(
        val string: String,
        val int: Int,
        val long: Long,
        val double: Double,
        val boolean: Boolean,
    )

    @Serializable
    data class Address(
        val street: String,
        val city: String,
        val zip: String,
    )

    @Serializable
    data class Person(
        val name: String,
        val age: Int,
        val address: Address,
    )

    @Serializable
    @JvmInline
    value class UserId(val value: String)

    @Serializable
    @JvmInline
    value class Amount(val cents: Long)

    @Serializable
    data class UserWithValueClass(
        val id: UserId,
        val name: String,
        val balance: Amount,
    )

    @Serializable
    data class NullableFields(
        val required: String,
        val optional: String? = null,
        val optionalInt: Int? = null,
    )

    @Serializable
    data class NullableEmbedded(
        val name: String,
        val address: Address? = null,
    )

    @Serializable
    data class WithList(
        val name: String,
        val tags: List<String>,
    )

    @Serializable
    data class WithListOfObjects(
        val name: String,
        val addresses: List<Address>,
    )

    @Serializable
    data class WithMap(
        val name: String,
        val metadata: Map<String, String>,
    )

    @Serializable
    data class WithMapOfObjects(
        val name: String,
        val contacts: Map<String, Address>,
    )

    @Serializable
    enum class Status { ACTIVE, INACTIVE, PENDING }

    @Serializable
    data class WithEnum(
        val name: String,
        val status: Status,
    )

    @Serializable
    data class WithOptionalList(
        val name: String,
        val tags: List<String> = emptyList(),
    )

    // ========== Helper ==========

    private fun createFormat(): MapFormat {
        val config = MapFormatConfig(
            collectionHandler = ArrayListCollectionHandler(
                MapFormatConfig(
                    collectionHandler = ArrayListCollectionHandler(
                        MapFormatConfig(
                            collectionHandler = object : CollectionHandler {
                                override fun createListEncoder(fieldPath: String, elementDescriptor: SerialDescriptor, output: WriteTarget) =
                                    throw UnsupportedOperationException("Triple-nested collections not supported")
                                override fun createListDecoder(fieldPath: String, elementDescriptor: SerialDescriptor, input: ReadSource) =
                                    throw UnsupportedOperationException("Triple-nested collections not supported")
                                override fun createMapEncoder(fieldPath: String, keyDescriptor: SerialDescriptor, valueDescriptor: SerialDescriptor, output: WriteTarget) =
                                    throw UnsupportedOperationException("Triple-nested collections not supported")
                                override fun createMapDecoder(fieldPath: String, keyDescriptor: SerialDescriptor, valueDescriptor: SerialDescriptor, input: ReadSource) =
                                    throw UnsupportedOperationException("Triple-nested collections not supported")
                            }
                        )
                    )
                )
            )
        )
        return MapFormat(config)
    }

    // ========== Tests: Simple Primitives ==========

    @Test
    fun `encode simple primitives`() {
        val format = createFormat()
        val value = SimplePrimitives(
            string = "hello",
            int = 42,
            long = 123456789L,
            double = 3.14,
            boolean = true,
        )

        val result = format.encode(SimplePrimitives.serializer(), value)

        assertEquals("hello", result.mainRecord["string"])
        assertEquals(42, result.mainRecord["int"])
        assertEquals(123456789L, result.mainRecord["long"])
        assertEquals(3.14, result.mainRecord["double"])
        assertEquals(true, result.mainRecord["boolean"])
    }

    @Test
    fun `decode simple primitives`() {
        val format = createFormat()
        val map = mapOf<String, Any?>(
            "string" to "hello",
            "int" to 42,
            "long" to 123456789L,
            "double" to 3.14,
            "boolean" to true,
        )

        val result = format.decode(SimplePrimitives.serializer(), map)

        assertEquals("hello", result.string)
        assertEquals(42, result.int)
        assertEquals(123456789L, result.long)
        assertEquals(3.14, result.double)
        assertEquals(true, result.boolean)
    }

    @Test
    fun `round trip simple primitives`() {
        val format = createFormat()
        val original = SimplePrimitives(
            string = "test",
            int = 100,
            long = 999L,
            double = 2.718,
            boolean = false,
        )

        val encoded = format.encode(SimplePrimitives.serializer(), original)
        val decoded = format.decode(SimplePrimitives.serializer(), encoded)

        assertEquals(original, decoded)
    }

    // ========== Tests: Embedded Structs ==========

    @Test
    fun `encode embedded struct flattens with separator`() {
        val format = createFormat()
        val value = Person(
            name = "John",
            age = 30,
            address = Address(
                street = "123 Main St",
                city = "Springfield",
                zip = "12345",
            )
        )

        val result = format.encode(Person.serializer(), value)

        assertEquals("John", result.mainRecord["name"])
        assertEquals(30, result.mainRecord["age"])
        assertEquals("123 Main St", result.mainRecord["address__street"])
        assertEquals("Springfield", result.mainRecord["address__city"])
        assertEquals("12345", result.mainRecord["address__zip"])
    }

    @Test
    fun `decode flattened embedded struct`() {
        val format = createFormat()
        val map = mapOf<String, Any?>(
            "name" to "Jane",
            "age" to 25,
            "address__street" to "456 Oak Ave",
            "address__city" to "Portland",
            "address__zip" to "97201",
        )

        val result = format.decode(Person.serializer(), map)

        assertEquals("Jane", result.name)
        assertEquals(25, result.age)
        assertEquals("456 Oak Ave", result.address.street)
        assertEquals("Portland", result.address.city)
        assertEquals("97201", result.address.zip)
    }

    @Test
    fun `round trip embedded struct`() {
        val format = createFormat()
        val original = Person(
            name = "Bob",
            age = 45,
            address = Address(
                street = "789 Pine Rd",
                city = "Seattle",
                zip = "98101",
            )
        )

        val encoded = format.encode(Person.serializer(), original)
        val decoded = format.decode(Person.serializer(), encoded)

        assertEquals(original, decoded)
    }

    // ========== Tests: Value Classes ==========

    @Test
    fun `encode value classes unwraps to underlying value`() {
        val format = createFormat()
        val value = UserWithValueClass(
            id = UserId("user-123"),
            name = "Alice",
            balance = Amount(5000L),
        )

        val result = format.encode(UserWithValueClass.serializer(), value)

        assertEquals("user-123", result.mainRecord["id"])
        assertEquals("Alice", result.mainRecord["name"])
        assertEquals(5000L, result.mainRecord["balance"])
    }

    @Test
    fun `decode value classes wraps underlying value`() {
        val format = createFormat()
        val map = mapOf<String, Any?>(
            "id" to "user-456",
            "name" to "Bob",
            "balance" to 10000L,
        )

        val result = format.decode(UserWithValueClass.serializer(), map)

        assertEquals(UserId("user-456"), result.id)
        assertEquals("Bob", result.name)
        assertEquals(Amount(10000L), result.balance)
    }

    @Test
    fun `round trip value classes`() {
        val format = createFormat()
        val original = UserWithValueClass(
            id = UserId("user-789"),
            name = "Charlie",
            balance = Amount(25000L),
        )

        val encoded = format.encode(UserWithValueClass.serializer(), original)
        val decoded = format.decode(UserWithValueClass.serializer(), encoded)

        assertEquals(original, decoded)
    }

    // ========== Tests: Nullable Fields ==========

    @Test
    fun `encode nullable fields with null values`() {
        val format = createFormat()
        val value = NullableFields(
            required = "required",
            optional = null,
            optionalInt = null,
        )

        val result = format.encode(NullableFields.serializer(), value)

        assertEquals("required", result.mainRecord["required"])
        assertNull(result.mainRecord["optional"])
        assertNull(result.mainRecord["optionalInt"])
    }

    @Test
    fun `encode nullable fields with non-null values`() {
        val format = createFormat()
        val value = NullableFields(
            required = "required",
            optional = "present",
            optionalInt = 42,
        )

        val result = format.encode(NullableFields.serializer(), value)

        assertEquals("required", result.mainRecord["required"])
        assertEquals("present", result.mainRecord["optional"])
        assertEquals(42, result.mainRecord["optionalInt"])
    }

    @Test
    fun `decode nullable fields with null values`() {
        val format = createFormat()
        val map = mapOf<String, Any?>(
            "required" to "test",
            "optional" to null,
            "optionalInt" to null,
        )

        val result = format.decode(NullableFields.serializer(), map)

        assertEquals("test", result.required)
        assertNull(result.optional)
        assertNull(result.optionalInt)
    }

    @Test
    fun `round trip nullable fields`() {
        val format = createFormat()

        val withNulls = NullableFields(required = "a", optional = null, optionalInt = null)
        val withValues = NullableFields(required = "b", optional = "opt", optionalInt = 99)

        assertEquals(withNulls, format.decode(NullableFields.serializer(), format.encode(NullableFields.serializer(), withNulls)))
        assertEquals(withValues, format.decode(NullableFields.serializer(), format.encode(NullableFields.serializer(), withValues)))
    }

    // ========== Tests: Nullable Embedded Classes ==========

    @Test
    fun `encode nullable embedded class with null uses exists marker`() {
        val format = createFormat()
        val value = NullableEmbedded(
            name = "Test",
            address = null,
        )

        val result = format.encode(NullableEmbedded.serializer(), value)

        assertEquals("Test", result.mainRecord["name"])
        assertEquals(false, result.mainRecord["address__exists"])
    }

    @Test
    fun `encode nullable embedded class with value uses exists marker`() {
        val format = createFormat()
        val value = NullableEmbedded(
            name = "Test",
            address = Address("123 St", "City", "12345"),
        )

        val result = format.encode(NullableEmbedded.serializer(), value)

        assertEquals("Test", result.mainRecord["name"])
        assertEquals(true, result.mainRecord["address__exists"])
        assertEquals("123 St", result.mainRecord["address__street"])
        assertEquals("City", result.mainRecord["address__city"])
        assertEquals("12345", result.mainRecord["address__zip"])
    }

    @Test
    fun `decode nullable embedded class with null`() {
        val format = createFormat()
        val map = mapOf<String, Any?>(
            "name" to "Test",
            "address__exists" to false,
        )

        val result = format.decode(NullableEmbedded.serializer(), map)

        assertEquals("Test", result.name)
        assertNull(result.address)
    }

    @Test
    fun `decode nullable embedded class with value`() {
        val format = createFormat()
        val map = mapOf<String, Any?>(
            "name" to "Test",
            "address__exists" to true,
            "address__street" to "456 Ave",
            "address__city" to "Town",
            "address__zip" to "67890",
        )

        val result = format.decode(NullableEmbedded.serializer(), map)

        assertEquals("Test", result.name)
        assertEquals(Address("456 Ave", "Town", "67890"), result.address)
    }

    @Test
    fun `round trip nullable embedded class`() {
        val format = createFormat()

        val withNull = NullableEmbedded("A", null)
        val withValue = NullableEmbedded("B", Address("St", "City", "Zip"))

        assertEquals(withNull, format.decode(NullableEmbedded.serializer(), format.encode(NullableEmbedded.serializer(), withNull)))
        assertEquals(withValue, format.decode(NullableEmbedded.serializer(), format.encode(NullableEmbedded.serializer(), withValue)))
    }

    // ========== Tests: Lists ==========

    @Test
    fun `encode list of primitives`() {
        val format = createFormat()
        val value = WithList(
            name = "Test",
            tags = listOf("a", "b", "c"),
        )

        val result = format.encode(WithList.serializer(), value)

        assertEquals("Test", result.mainRecord["name"])
        assertEquals(listOf("a", "b", "c"), result.mainRecord["tags"])
    }

    @Test
    fun `decode list of primitives`() {
        val format = createFormat()
        val map = mapOf<String, Any?>(
            "name" to "Test",
            "tags" to listOf("x", "y", "z"),
        )

        val result = format.decode(WithList.serializer(), map)

        assertEquals("Test", result.name)
        assertEquals(listOf("x", "y", "z"), result.tags)
    }

    @Test
    fun `round trip list of primitives`() {
        val format = createFormat()
        val original = WithList("Test", listOf("one", "two", "three"))

        val encoded = format.encode(WithList.serializer(), original)
        val decoded = format.decode(WithList.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `encode list of objects`() {
        val format = createFormat()
        val value = WithListOfObjects(
            name = "Test",
            addresses = listOf(
                Address("St1", "City1", "11111"),
                Address("St2", "City2", "22222"),
            ),
        )

        val result = format.encode(WithListOfObjects.serializer(), value)

        assertEquals("Test", result.mainRecord["name"])
        @Suppress("UNCHECKED_CAST")
        val addresses = result.mainRecord["addresses"] as List<Map<String, Any?>>
        assertEquals(2, addresses.size)
        assertEquals("St1", addresses[0]["street"])
        assertEquals("City1", addresses[0]["city"])
        assertEquals("St2", addresses[1]["street"])
        assertEquals("City2", addresses[1]["city"])
    }

    @Test
    fun `round trip list of objects`() {
        val format = createFormat()
        val original = WithListOfObjects(
            name = "Test",
            addresses = listOf(
                Address("A St", "A City", "AAAAA"),
                Address("B St", "B City", "BBBBB"),
            ),
        )

        val encoded = format.encode(WithListOfObjects.serializer(), original)
        val decoded = format.decode(WithListOfObjects.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `encode empty list`() {
        val format = createFormat()
        val value = WithList("Empty", emptyList())

        val result = format.encode(WithList.serializer(), value)

        assertEquals("Empty", result.mainRecord["name"])
        assertEquals(emptyList<String>(), result.mainRecord["tags"])
    }

    @Test
    fun `round trip empty list`() {
        val format = createFormat()
        val original = WithList("Empty", emptyList())

        val encoded = format.encode(WithList.serializer(), original)
        val decoded = format.decode(WithList.serializer(), encoded)

        assertEquals(original, decoded)
    }

    // ========== Tests: Maps ==========

    @Test
    fun `encode map of primitives`() {
        val format = createFormat()
        val value = WithMap(
            name = "Test",
            metadata = mapOf("key1" to "value1", "key2" to "value2"),
        )

        val result = format.encode(WithMap.serializer(), value)

        assertEquals("Test", result.mainRecord["name"])
        @Suppress("UNCHECKED_CAST")
        val metadata = result.mainRecord["metadata"] as Map<String, String>
        assertEquals("value1", metadata["key1"])
        assertEquals("value2", metadata["key2"])
    }

    @Test
    fun `round trip map of primitives`() {
        val format = createFormat()
        val original = WithMap("Test", mapOf("a" to "1", "b" to "2", "c" to "3"))

        val encoded = format.encode(WithMap.serializer(), original)
        val decoded = format.decode(WithMap.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `round trip map of objects`() {
        val format = createFormat()
        val original = WithMapOfObjects(
            name = "Test",
            contacts = mapOf(
                "home" to Address("Home St", "Home City", "11111"),
                "work" to Address("Work St", "Work City", "22222"),
            ),
        )

        val encoded = format.encode(WithMapOfObjects.serializer(), original)
        val decoded = format.decode(WithMapOfObjects.serializer(), encoded)

        assertEquals(original, decoded)
    }

    // ========== Tests: Enums ==========

    @Test
    fun `encode enum as string`() {
        val format = createFormat()
        val value = WithEnum("Test", Status.ACTIVE)

        val result = format.encode(WithEnum.serializer(), value)

        assertEquals("Test", result.mainRecord["name"])
        assertEquals("ACTIVE", result.mainRecord["status"])
    }

    @Test
    fun `decode enum from string`() {
        val format = createFormat()
        val map = mapOf<String, Any?>(
            "name" to "Test",
            "status" to "PENDING",
        )

        val result = format.decode(WithEnum.serializer(), map)

        assertEquals("Test", result.name)
        assertEquals(Status.PENDING, result.status)
    }

    @Test
    fun `round trip enum`() {
        val format = createFormat()
        val original = WithEnum("Test", Status.INACTIVE)

        val encoded = format.encode(WithEnum.serializer(), original)
        val decoded = format.decode(WithEnum.serializer(), encoded)

        assertEquals(original, decoded)
    }

    // ========== Tests: Default Values ==========

    @Test
    fun `decode with missing optional fields uses defaults`() {
        val format = createFormat()
        val map = mapOf<String, Any?>(
            "name" to "Test",
            // tags is missing, should use default empty list
        )

        val result = format.decode(WithOptionalList.serializer(), map)

        assertEquals("Test", result.name)
        assertEquals(emptyList<String>(), result.tags)
    }

    // ========== Tests: Value Converters ==========

    // Value class wrapper for custom type conversion (proper use case for converters)
    @Serializable
    @JvmInline
    value class Timestamp(val millis: Long)

    @Serializable
    data class WithCustomType(
        val id: String,
        val timestamp: Timestamp, // Value class - converters work via encodeInlineElement
    )

    @Test
    fun `encode with value converter`() {
        // Create a converter that converts Timestamp to a String representation
        val timestampConverter = object : ValueConverter<Timestamp, String> {
            override val descriptor: SerialDescriptor = Timestamp.serializer().descriptor
            override fun toDatabase(value: Timestamp): String = "TS:${value.millis}"
            override fun fromDatabase(value: String): Timestamp = Timestamp(value.removePrefix("TS:").toLong())
        }

        val config = MapFormatConfig(
            converters = ValueConverterRegistry(listOf(timestampConverter)),
            collectionHandler = ArrayListCollectionHandler(
                MapFormatConfig(
                    collectionHandler = object : CollectionHandler {
                        override fun createListEncoder(fieldPath: String, elementDescriptor: SerialDescriptor, output: WriteTarget) =
                            throw UnsupportedOperationException()
                        override fun createListDecoder(fieldPath: String, elementDescriptor: SerialDescriptor, input: ReadSource) =
                            throw UnsupportedOperationException()
                        override fun createMapEncoder(fieldPath: String, keyDescriptor: SerialDescriptor, valueDescriptor: SerialDescriptor, output: WriteTarget) =
                            throw UnsupportedOperationException()
                        override fun createMapDecoder(fieldPath: String, keyDescriptor: SerialDescriptor, valueDescriptor: SerialDescriptor, input: ReadSource) =
                            throw UnsupportedOperationException()
                    }
                )
            )
        )
        val format = MapFormat(config)

        val value = WithCustomType("test-id", Timestamp(1234567890L))
        val result = format.encode(WithCustomType.serializer(), value)

        assertEquals("test-id", result.mainRecord["id"])
        assertEquals("TS:1234567890", result.mainRecord["timestamp"])
    }

    @Test
    fun `decode with value converter`() {
        val timestampConverter = object : ValueConverter<Timestamp, String> {
            override val descriptor: SerialDescriptor = Timestamp.serializer().descriptor
            override fun toDatabase(value: Timestamp): String = "TS:${value.millis}"
            override fun fromDatabase(value: String): Timestamp = Timestamp(value.removePrefix("TS:").toLong())
        }

        val config = MapFormatConfig(
            converters = ValueConverterRegistry(listOf(timestampConverter)),
            collectionHandler = ArrayListCollectionHandler(
                MapFormatConfig(
                    collectionHandler = object : CollectionHandler {
                        override fun createListEncoder(fieldPath: String, elementDescriptor: SerialDescriptor, output: WriteTarget) =
                            throw UnsupportedOperationException()
                        override fun createListDecoder(fieldPath: String, elementDescriptor: SerialDescriptor, input: ReadSource) =
                            throw UnsupportedOperationException()
                        override fun createMapEncoder(fieldPath: String, keyDescriptor: SerialDescriptor, valueDescriptor: SerialDescriptor, output: WriteTarget) =
                            throw UnsupportedOperationException()
                        override fun createMapDecoder(fieldPath: String, keyDescriptor: SerialDescriptor, valueDescriptor: SerialDescriptor, input: ReadSource) =
                            throw UnsupportedOperationException()
                    }
                )
            )
        )
        val format = MapFormat(config)

        val map = mapOf<String, Any?>(
            "id" to "test-id",
            "timestamp" to "TS:9876543210",
        )
        val result = format.decode(WithCustomType.serializer(), map)

        assertEquals("test-id", result.id)
        assertEquals(Timestamp(9876543210L), result.timestamp)
    }

    // ========== Tests: Complex Nested Structures ==========

    @Serializable
    data class Company(
        val name: String,
        val ceo: Person,
        val employees: List<Person>,
        val offices: Map<String, Address>,
    )

    @Test
    fun `round trip complex nested structure`() {
        val format = createFormat()
        val original = Company(
            name = "Acme Corp",
            ceo = Person("John CEO", 55, Address("CEO St", "CEO City", "00001")),
            employees = listOf(
                Person("Alice", 30, Address("A St", "A City", "11111")),
                Person("Bob", 35, Address("B St", "B City", "22222")),
            ),
            offices = mapOf(
                "hq" to Address("HQ St", "HQ City", "00000"),
                "branch" to Address("Branch St", "Branch City", "99999"),
            ),
        )

        val encoded = format.encode(Company.serializer(), original)
        val decoded = format.decode(Company.serializer(), encoded)

        assertEquals(original, decoded)
    }
}
