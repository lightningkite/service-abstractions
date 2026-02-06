package com.lightningkite.services.database

import com.lightningkite.EmailAddress
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.TextIndex
import com.lightningkite.toEmailAddress
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid


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
    var email: EmailAddress = "test@test.com".toEmailAddress(),
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
data class UnreasonablyComplex(
    val string: String = "",
    val int: Int = 0,
    val list: List<String> = listOf(),
    val map: Map<String, Int> = mapOf(),
    val nested: UnreasonablyComplex? = null,
    val listNested: List<UnreasonablyComplex> = listOf(),
    val mapNested: Map<String, UnreasonablyComplex> = mapOf(),
)

@Serializable
data class StupidGenericBox<T>(val value: T)

@Serializable
data class ContextualCopyCheck(val date: LocalDate)
