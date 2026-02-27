package com.lightningkite.services.files

import com.lightningkite.services.files.ServerFile
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SerializationTest {
    @Test fun test() {
        println(ServerFile.serializer().descriptor.serialName)
        val f = ServerFile("test")
        assertEquals(f, Json.decodeFromString(ServerFile.serializer(), Json.encodeToString(ServerFile.serializer(), f)))
    }
}