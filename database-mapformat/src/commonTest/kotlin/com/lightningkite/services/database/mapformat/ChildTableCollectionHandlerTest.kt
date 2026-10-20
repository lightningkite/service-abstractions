package com.lightningkite.services.database.mapformat

import kotlinx.serialization.Serializable
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

    // Child tables name map value columns `value__<field>`; rows must use the same names or the
    // values never reach a column.
    @Test
    fun structMapValuesUseValueColumns() {
        val value = Places(mapOf("home" to Point(1, 2)))
        val written = format.encode(Places.serializer(), value)
        assertEquals(mapOf("value__x" to 1, "value__y" to 2), written.children["byName"]!![0].values)
        assertEquals(value, format.decode(Places.serializer(), written))
    }
}
