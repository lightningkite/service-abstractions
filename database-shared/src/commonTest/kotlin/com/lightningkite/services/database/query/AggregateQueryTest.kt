@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.data.Length
import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.path.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.*

@Queryable
data class AggregateTestModel(
    val wrapped: LongWrapper = LongWrapper(0),
    val wrappedNullable: LongWrapper? = null,
    val length: Length = Length(0.0),
)

class AggregateQueryTest {
    private val numberPaths: List<Pair<Optic<LargeTestModel, out Number?>, String>> = listOf(
        LargeTestModel.byte to "byte",
        LargeTestModel.short to "short",
        LargeTestModel.int to "int",
        LargeTestModel.long to "long",
        LargeTestModel.float to "float",
        LargeTestModel.double to "double",
        LargeTestModel.intNullable to "intNullable",
        LargeTestModel.doubleNullable to "doubleNullable",
        LargeTestModel.embedded.value2 to "embedded.value2",
        LargeTestModel.embeddedNullable.notNull.value2 to "embeddedNullable?.value2",
    )

    private val valueClassPaths: List<Pair<Optic<AggregateTestModel, out Number?>, String>> = listOf(
        AggregateTestModel.wrapped + LongWrapper.long to "wrapped.long",
        AggregateTestModel.wrappedNullable.notNull + LongWrapper.long to "wrappedNullable?.long",
        AggregateTestModel.length.meters to "length.meters",
    )

    @Test
    fun aggregateQueryRoundTripsNumberPaths() {
        for ((path, string) in numberPaths) {
            val query = AggregateQuery(Aggregate.Sum, property = path)
            val json = Json.encodeToString(AggregateQuery.serializer(LargeTestModel.serializer()), query)
            assertEquals("""{"aggregate":"Sum","property":"$string"}""", json)
            assertEquals(query, Json.decodeFromString(AggregateQuery.serializer(LargeTestModel.serializer()), json))
        }
    }

    @Test
    fun groupAggregateQueryRoundTripsNumberPaths() {
        for ((path, string) in numberPaths) {
            val query = GroupAggregateQuery(Aggregate.Average, groupBy = LargeTestModel.string, property = path)
            val json = Json.encodeToString(GroupAggregateQuery.serializer(LargeTestModel.serializer()), query)
            assertEquals("""{"aggregate":"Average","groupBy":"string","property":"$string"}""", json)
            assertEquals(query, Json.decodeFromString(GroupAggregateQuery.serializer(LargeTestModel.serializer()), json))
        }
    }

    @Test
    fun queriesRoundTripPathsIntoValueClasses() {
        for ((path, string) in valueClassPaths) {
            val query = AggregateQuery(Aggregate.Sum, property = path)
            val json = Json.encodeToString(AggregateQuery.serializer(AggregateTestModel.serializer()), query)
            assertEquals("""{"aggregate":"Sum","property":"$string"}""", json)
            assertEquals(query, Json.decodeFromString(AggregateQuery.serializer(AggregateTestModel.serializer()), json))

            val grouped = GroupAggregateQuery(Aggregate.Sum, groupBy = AggregateTestModel.wrapped, property = path)
            val groupedJson = Json.encodeToString(GroupAggregateQuery.serializer(AggregateTestModel.serializer()), grouped)
            assertEquals(grouped, Json.decodeFromString(GroupAggregateQuery.serializer(AggregateTestModel.serializer()), groupedJson))
        }
    }

    @Test
    fun conditionSurvivesRoundTrip() {
        val query = AggregateQuery(Aggregate.Sum, condition = LargeTestModel.int gt 3, property = LargeTestModel.double)
        val json = Json.encodeToString(AggregateQuery.serializer(LargeTestModel.serializer()), query)
        assertEquals(query, Json.decodeFromString(AggregateQuery.serializer(LargeTestModel.serializer()), json))
    }

    @Test
    fun nonNumberPathsFailToDeserialize() {
        for (path in listOf("string", "embedded", "list", "this", "embeddedNullable?.value1")) {
            assertFailsWith<SerializationException>(path) {
                Json.decodeFromString(
                    AggregateQuery.serializer(LargeTestModel.serializer()),
                    """{"aggregate":"Sum","property":"$path"}""",
                )
            }
            assertFailsWith<SerializationException>(path) {
                Json.decodeFromString(
                    GroupAggregateQuery.serializer(LargeTestModel.serializer()),
                    """{"aggregate":"Sum","groupBy":"string","property":"$path"}""",
                )
            }
        }
    }

    @Test
    fun pathEndingAtValueClassFailsToDeserialize() {
        for (path in listOf("wrapped", "wrappedNullable", "length")) {
            assertFailsWith<SerializationException>(path) {
                Json.decodeFromString(
                    AggregateQuery.serializer(AggregateTestModel.serializer()),
                    """{"aggregate":"Sum","property":"$path"}""",
                )
            }
        }
    }
}
