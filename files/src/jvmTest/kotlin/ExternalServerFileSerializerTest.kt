package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.workingDirectory
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.modules.serializersModuleOf
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class ExternalServerFileSerializerTest {
    @Test
    fun testDirectVerify() {
        val context = TestSettingContext()
        val fs = KotlinxIoPublicFileSystem("test", context, workingDirectory.then("build/test-files"))
        val ser = ExternalServerFileSerializer(
            clock = Clock.System,
            scanners = listOf(),
            fileSystems = listOf(fs),
            onUse = {},
            key = CryptographyProvider.Default.get(HMAC).keyGenerator().generateKeyBlocking()
        )
        val key: String = "test.txt"
        val fo = ser.jail.then(key)
        runBlocking {
            fo.put(TypedData.text("TEST CONTENT", MediaType.Text.Plain))
            ser.verifyUrl(ser.certifyForUse(key, 1.minutes).also { println(it) })
            ser.verifyUrl(ser.scan(ser.certifyForUse(key, 1.minutes), 1.minutes).also { println(it) })
        }
    }
    @Test
    fun testSerialization() {
        val context = TestSettingContext()
        val fs = KotlinxIoPublicFileSystem("test", context, workingDirectory.then("build/test-files"))
        val ser = ExternalServerFileSerializer(
            clock = Clock.System,
            scanners = listOf(),
            fileSystems = listOf(fs),
            onUse = {},
            key = CryptographyProvider.Default.get(HMAC).keyGenerator().generateKeyBlocking()
        )
        val key: String = "test.txt"
        val fo = ser.jail.then(key)
        val json = Json { serializersModule = serializersModuleOf(ser) }
        runBlocking {
            fo.put(TypedData.text("TEST CONTENT", MediaType.Text.Plain))
            JsonPrimitive(ser.certifyForUse(key, 1.minutes).also { println(it) }).let {
                json.decodeFromJsonElement(ser, it).also { println(it) }
                json.decodeFromJsonElement<ServerFile>(it).also { println(it) }
            }
            JsonPrimitive(ser.scan(ser.certifyForUse(key, 1.minutes), 1.minutes).also { println(it) }).let {
                json.decodeFromJsonElement(ser, it).also { println(it) }
                json.decodeFromJsonElement<ServerFile>(it).also { println(it) }
            }
        }
    }
}