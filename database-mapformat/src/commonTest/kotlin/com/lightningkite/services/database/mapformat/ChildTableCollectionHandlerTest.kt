package com.lightningkite.services.database.mapformat

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals

class ChildTableCollectionHandlerTest {
    @Serializable
    data class Item(val name: String, val tags: List<String>, val counts: Map<String, Int>)

    @Serializable
    data class Holder(val items: List<Item>)

    private val format: MapFormat = run {
        lateinit var config: MapFormatConfig
        config = MapFormatConfig(collectionHandler = ChildTableCollectionHandler { config })
        MapFormat(config)
    }

    // Collections inside a child row used to be written to a child table of their own that was
    // then thrown away, so they silently came back empty.
    @Test
    fun collectionsNestedInElementsRoundTrip() {
        val value = Holder(listOf(Item("a", listOf("x", "y"), mapOf("k" to 1)), Item("b", listOf(), mapOf())))
        val written = format.encode(Holder.serializer(), value)
        assertEquals("""["x","y"]""", written.children["items"]!![0].values["tags"])
        assertEquals(value, format.decode(Holder.serializer(), written))
    }

    @Serializable
    data class Point(val x: Int, val y: Int)

    @Serializable
    data class Places(val byName: Map<String, Point>)

    @Serializable
    data class Optionals(val points: List<Point?>, val byName: Map<String, Point?>, val ids: List<Int?>)

    // Collection serializers hand a nullable element's NullableSerializer to encodeSerializableElement,
    // so present struct elements used to be written without their exists marker and read back as null.
    @Test
    fun nullableElementsRoundTrip() {
        val value = Optionals(listOf(null, Point(1, 2)), mapOf("none" to null, "some" to Point(3, 4)), listOf(null, 5))
        assertEquals(value, format.decode(Optionals.serializer(), format.encode(Optionals.serializer(), value)))
    }

    // Child tables name map value columns `value__<field>`; rows must use the same names or the
    // values never reach a column.
    @Test
    fun structMapValuesUseValueColumns() {
        val value = Places(mapOf("home" to Point(1, 2)))
        val written = format.encode(Places.serializer(), value)
        assertEquals(mapOf("value__x" to 1, "value__y" to 2), written.children["byName"]!![0].values)
        assertEquals(value, format.decode(Places.serializer(), written))
    }

    @Serializable
    @JvmInline
    value class RowId(val raw: String)

    @Serializable
    data class Leaf(val id: RowId, val text: String)

    @Serializable
    data class Node(val leaf: Leaf, val tail: String)

    @Serializable
    data class Forest(val nodes: List<Node>)

    // A value class inside a nested struct double-popped the struct's field-path tag: the encoder
    // wrote `text` instead of `leaf__text` (a name no column matches, so the value was dropped),
    // and the decoder lost the `leaf` prefix and read a key that isn't there.
    @Test
    fun valueClassesInsideNestedStructsKeepTheirPrefix() {
        val value = Forest(listOf(Node(Leaf(RowId("r1"), "hello"), "after")))
        val written = format.encode(Forest.serializer(), value)
        assertEquals(
            mapOf("leaf__id" to "r1", "leaf__text" to "hello", "tail" to "after"),
            written.children["nodes"]!![0].values,
        )
        assertEquals(value, format.decode(Forest.serializer(), written))
    }

    @Serializable
    data class WithList(val title: String, val tags: List<String>, val tail: String)

    @Serializable
    data class Wrap(val w: WithList, val after: String)

    @Serializable
    data class Bundle(val items: List<Wrap>)

    // Collections consume their own tag (beginStructure/JSON), so decoding one used to pop the
    // enclosing struct's tag too and the next field read an unprefixed key that isn't there.
    @Test
    fun listsInsideNestedStructsKeepTheirPrefix() {
        val value = Bundle(listOf(Wrap(WithList("t", listOf("a"), "tail"), "after")))
        assertEquals(value, format.decode(Bundle.serializer(), format.encode(Bundle.serializer(), value)))
    }
}
