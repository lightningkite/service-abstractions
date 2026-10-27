package com.lightningkite.services.embedding.bedrock

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CohereWireTest {
    @Test
    fun v4RequestAsksForFloatsAtTheChosenDimension() {
        val body = bedrockEmbeddingJson.parseToJsonElement(buildCohereV4RequestBody(listOf("a \"quoted\" text"), 1024)).jsonObject
        assertEquals("a \"quoted\" text", body["texts"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("search_document", body["input_type"]!!.jsonPrimitive.content)
        assertEquals(listOf("float"), body["embedding_types"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1024, body["output_dimension"]!!.jsonPrimitive.int)
        assertEquals("RIGHT", body["truncate"]!!.jsonPrimitive.content)
    }

    @Test
    fun v4RequestWithoutDimensionsLeavesTheModelDefault() {
        val body = bedrockEmbeddingJson.parseToJsonElement(buildCohereV4RequestBody(listOf("x"), null)).jsonObject
        assertFalse("output_dimension" in body)
    }

    @Test
    fun parsesFloatArrayResponses() {
        val parsed = parseCohereResponse("""{"id":"1","embeddings":[[0.5,-1.0],[2.0,0.25]],"response_type":"embeddings_floats"}""")
        assertEquals(listOf(listOf(0.5f, -1.0f), listOf(2.0f, 0.25f)), parsed.embeddings)
    }

    @Test
    fun parsesByTypeResponses() {
        val parsed = parseCohereResponse("""{"id":"1","embeddings":{"float":[[0.5,-1.0]],"int8":[[7,-3]]},"response_type":"embeddings_by_type"}""")
        assertEquals(listOf(listOf(0.5f, -1.0f)), parsed.embeddings)
    }
}
