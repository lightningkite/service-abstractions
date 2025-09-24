package com.lightningkite.serviceabstractions.files

import com.lightningkite.services.files.ServerFile
import kotlin.test.Test

class SerializationTest {
    @Test fun test() {
        println(ServerFile.serializer().descriptor.serialName)
    }
}