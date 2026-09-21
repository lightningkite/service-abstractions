package com.lightningkite.services.database.mongodb

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.serializableProperties
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.Document
import com.lightningkite.services.database.mongodb.bson.KBson
import com.lightningkite.services.database.test.LargeTestModel
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BsonTest {
    private fun <T> KBson.roundTrip(serializer: KSerializer<T>, value: T) {
        assertEquals(value, parse(serializer, stringify(serializer, value).also(::println)))
        assertEquals(value, decodeFromByteArray(serializer, encodeToByteArray(serializer, value)))
    }

    @Test
    fun test() {
        val ser = LargeTestModel.serializer()
        val bson = KBson()
        val sample = LargeTestModel()
        bson.roundTrip(ser, sample)
    }

    @Test
    fun stringifyAnyTest() {
        println(KBson().stringifyAny(Int.serializer(), 1))
    }

    @Test
    fun testPolymorphic() {
        val bson = KBson()

        bson.roundTrip(Polymorphic.serializer(), Polymorphic.Object)
        bson.roundTrip(Polymorphic.serializer(), Polymorphic.Class())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testIfIsTypeCondition() {
        val bson = KBson()
        val holderSer = PolymorphicHolder.serializer()
        val valueProp = holderSer.serializableProperties!!
            .first { it.name == "value" } as SerializableProperty<PolymorphicHolder, Polymorphic>
        val innerProp = Polymorphic.Class.serializer().serializableProperties!!
            .first { it.name == "inner" } as SerializableProperty<Polymorphic.Class, Int>

        assertEquals(
            Document("value._t", Document("\$eq", Polymorphic.Object.serializer().descriptor.serialName)),
            Condition.OnField(
                valueProp,
                Condition.IfIsType(Polymorphic.Object.serializer(), Condition.Always)
            ).bson(holderSer, bson = bson)
        )

        assertEquals(
            Document("value._t", Document("\$eq", Polymorphic.Class.serializer().descriptor.serialName))
                .append("value.inner", Document("\$gt", BsonInt32(3))),
            Condition.OnField(
                valueProp,
                Condition.IfIsType(
                    Polymorphic.Class.serializer(),
                    Condition.OnField(innerProp, Condition.GreaterThan(3))
                )
            ).bson(holderSer, bson = bson)
        )

        val classSerialName = Polymorphic.Class.serializer().descriptor.serialName
        val encodedClass = BsonDocument("_t", BsonString(classSerialName)).append("inner", BsonInt32(5))

        assertEquals(
            Document("value", Document("\$eq", encodedClass)),
            Condition.OnField(
                valueProp,
                Condition.IfIsType(Polymorphic.Class.serializer(), Condition.Equal(Polymorphic.Class(5)))
            ).bson(holderSer, bson = bson)
        )

        assertEquals(
            Document("value._t", Document("\$eq", classSerialName))
                .append("value", Document("\$ne", encodedClass)),
            Condition.OnField(
                valueProp,
                Condition.IfIsType(Polymorphic.Class.serializer(), Condition.NotEqual(Polymorphic.Class(5)))
            ).bson(holderSer, bson = bson)
        )

        assertFailsWith<IllegalArgumentException> {
            Condition.IfIsType<Polymorphic, Polymorphic.Class>(
                Polymorphic.Class.serializer(),
                Condition.NotEqual(Polymorphic.Class(5))
            ).bson(Polymorphic.serializer(), bson = bson)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testIfIsTypeModification() {
        val bson = KBson()
        val holderSer = PolymorphicHolder.serializer()
        val valueProp = holderSer.serializableProperties!!
            .first { it.name == "value" } as SerializableProperty<PolymorphicHolder, Polymorphic>
        val innerProp = Polymorphic.Class.serializer().serializableProperties!!
            .first { it.name == "inner" } as SerializableProperty<Polymorphic.Class, Int>
        val classSerialName = Polymorphic.Class.serializer().descriptor.serialName

        // Field modifications apply at the same key using the variant's serializer.
        assertEquals(
            Document("\$inc", Document("value.inner", BsonInt32(1))),
            Modification.OnField(
                valueProp,
                Modification.IfIsType(
                    Polymorphic.Class.serializer(),
                    Modification.OnField(innerProp, Modification.Increment(1))
                )
            ).bson(holderSer, bson = bson).document
        )

        // Whole-value assignment keeps the discriminator.
        assertEquals(
            Document("\$set", Document("value", BsonDocument("_t", BsonString(classSerialName)).append("inner", BsonInt32(5)))),
            Modification.OnField(
                valueProp,
                Modification.IfIsType(Polymorphic.Class.serializer(), Modification.Assign(Polymorphic.Class(5)))
            ).bson(holderSer, bson = bson).document
        )

        // Each part of a chain is handled on its own, so an assignment inside one still keeps the discriminator.
        assertEquals(
            Document("\$inc", Document("value.inner", BsonInt32(1)))
                .append("\$max", Document("value.inner", BsonInt32(3))),
            Modification.OnField(
                valueProp,
                Modification.IfIsType(
                    Polymorphic.Class.serializer(),
                    Modification.Chain(
                        listOf(
                            Modification.OnField(innerProp, Modification.Increment(1)),
                            Modification.OnField(innerProp, Modification.CoerceAtLeast(3)),
                        )
                    )
                )
            ).bson(holderSer, bson = bson).document
        )
    }
}

@Serializable
data class PolymorphicHolder(val value: Polymorphic)


@Serializable
@GenerateDataClassPaths
data class ObjectIdTest(
    @Contextual val _id: ObjectId,
)

@Serializable
sealed interface Polymorphic {
    @Serializable
    @GenerateDataClassPaths
    data object Object : Polymorphic

    @Serializable
    @GenerateDataClassPaths
    data class Class(val inner: Int = 0) : Polymorphic
}