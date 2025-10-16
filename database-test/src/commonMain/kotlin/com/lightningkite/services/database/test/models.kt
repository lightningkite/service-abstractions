
package com.lightningkite.services.database.test

import com.lightningkite.GeoCoordinate
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.uuid.Uuid
import kotlin.time.Instant

@GenerateDataClassPaths()
@Serializable
data class User(
    override val _id: Uuid = Uuid.random(),
    @Index(unique = true) override var email: String,
    @Index(unique = true) override val phoneNumber: String,
    var age: Long = 0,
    var friends: List<Uuid> = listOf()
) : HasId<Uuid>, HasEmail, HasPhoneNumber {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class ValidatedModel(
    @ExpectedPattern("[a-zA-Z ]+") @MaxLength(15) val name: String,
)

@GenerateDataClassPaths
@Serializable
data class HasWeirdMap(
    override val _id: Uuid = Uuid.random(),
    val map: Map<TestEnum, String> = mapOf(TestEnum.One to "One", TestEnum.Two to "Two")
): HasId<Uuid>

@GenerateDataClassPaths
@Serializable
data class CompoundKeyTestModel(
    override val _id: CompoundTestKey = CompoundTestKey("first", "second"),
    val value: Int = 0,
): HasId<CompoundTestKey>

@GenerateDataClassPaths
@Serializable
data class CompoundTestKey(
    val first: String,
    val second: String,
): Comparable<CompoundTestKey> {
    companion object {
        val comparator = compareBy<CompoundTestKey> { it.first }.thenBy { it.second }
    }
    override fun compareTo(other: CompoundTestKey): Int = comparator.compare(this, other)
}

@GenerateDataClassPaths()
@Serializable
data class Post(
    override val _id: Uuid = Uuid.random(),
    var author: Uuid,
    var content: String,
    var at: Long? = null
) : HasId<Uuid> {
    companion object
}

@GenerateDataClassPaths()
@Serializable
data class Employee(
    override val _id: Uuid = Uuid.random(),
    var dictionary: Map<String, Int> = mapOf(),
) : HasId<Uuid> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class EmbeddedObjectTest(
    override val _id: Uuid = Uuid.random(),
    var name: String = "",
    var embed1: ClassUsedForEmbedding = ClassUsedForEmbedding("value1", 1),
    var embed2: ClassUsedForEmbedding = ClassUsedForEmbedding("value2", 2),
) : HasId<Uuid> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class ClassUsedForEmbedding(
    var value1:String = "default",
    var value2:Int = 1
)

@Serializable
data class RecursiveEmbed(
    var embedded:RecursiveEmbed? = null,
    var value2:String = "value2"
)

@GenerateDataClassPaths
@Serializable
data class EmbeddedNullable(
    override val _id: Uuid = Uuid.random(),
    var name: String = "",
    var embed1: ClassUsedForEmbedding? = null,
) : HasId<Uuid> {
    companion object
}

@GenerateDataClassPaths
@Serializable
@TextIndex(["string", "embedded.value1"])
data class LargeTestModel(
    override val _id: Uuid = Uuid.random(),
    var boolean: Boolean = false,
    var byte: Byte = 0,
    var short: Short = 0,
    @Index var int: Int = 0,
    var long: Long = 0,
    var float: Float = 0f,
    var double: Double = 0.0,
    var char: Char = ' ',
    var string: String = "",
    var uuid: Uuid = Uuid.NIL,
    var instant: Instant = Instant.fromEpochMilliseconds(0L),
    var list: List<Int> = listOf(),
    var listEmbedded: List<ClassUsedForEmbedding> = listOf(),
    var set: Set<Int> = setOf(),
    var setEmbedded: Set<ClassUsedForEmbedding> = setOf(),
    var map: Map<String, Int> = mapOf(),
    var embedded: ClassUsedForEmbedding = ClassUsedForEmbedding(),
    var booleanNullable: Boolean? = null,
    var byteNullable: Byte? = null,
    var shortNullable: Short? = null,
    var intNullable: Int? = null,
    var longNullable: Long? = null,
    var floatNullable: Float? = null,
    var doubleNullable: Double? = null,
    var charNullable: Char? = null,
    var stringNullable: String? = null,
    var uuidNullable: Uuid? = null,
    var instantNullable: Instant? = null,
    var listNullable: List<Int>? = null,
    var mapNullable: Map<String, Int>? = null,
    var embeddedNullable: ClassUsedForEmbedding? = null,
) : HasId<Uuid> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class SimpleLargeTestModel(
    override val _id: Uuid = Uuid.random(),
    var boolean: Boolean = false,
    var byte: Byte = 0,
    var short: Short = 0,
    @Index var int: Int = 0,
    var long: Long = 0,
    var float: Float = 0f,
    var double: Double = 0.0,
    var char: Char = ' ',
    var string: String = "",
    var uuid: Uuid = Uuid.NIL,
    var instant: Instant = Instant.fromEpochMilliseconds(0L),
    var listEmbedded: List<ClassUsedForEmbedding> = listOf(),
) : HasId<Uuid> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class NestedEnumTestModel(
    override val _id: Uuid = Uuid.random(),
    val thing: NestedEnumHolder = NestedEnumHolder()
) : HasId<Uuid> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class NestedEnumHolder(
    val enumValue: TestEnum = TestEnum.One
)

@Serializable
enum class TestEnum { One, Two }

@GenerateDataClassPaths
@Serializable
data class GeoTest(
    override val _id: Uuid = Uuid.random(),
    @Index val geo: GeoCoordinate = GeoCoordinate(41.727019, -111.8443002)
) : HasId<Uuid> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class EmbeddedMap(
    override val _id: Uuid = Uuid.random(),
    var map: Map<String, RecursiveEmbed>,
) : HasId<Uuid>

@GenerateDataClassPaths
@Serializable
data class MetaTestModel(
    override val _id: Uuid = Uuid.random(),
    val condition: Condition<LargeTestModel>,
    val modification: Modification<LargeTestModel>
) : HasId<Uuid> {
}

@GenerateDataClassPaths
@Serializable
data class HasGenerics<T>(
    override val _id: Uuid,
    val value: T
) : HasId<Uuid>

@GenerateDataClassPaths
@Serializable
data class HasGenerics2<T>(
    override val _id: Uuid,
    val value: T
) : HasId<Uuid>