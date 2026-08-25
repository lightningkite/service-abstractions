package com.lightningkite.services.files

import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.*
import com.lightningkite.services.kfile.workingDirectory
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.serializersModuleOf
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * A scanner that records whether it was ever invoked. Used to prove the default deserialize path
 * performs no scanning (and therefore no blocking file I/O).
 */
private class RecordingScanner(override val context: SettingContext) : FileScanner {
    var scanCalled: Boolean = false
        private set

    override val name: String = "recording-scanner"
    override suspend fun scan(file: ExternalFile) {
        scanCalled = true
    }

    override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
}

class ExternalServerFileSerializerTest {

    @Test
    fun testDirectVerify() {
        val context = TestSettingContext()
        val fs = KotlinxIoExternalFileSystem("test", context, workingDirectory.then("build/test-files"))
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
        val fs = KotlinxIoExternalFileSystem("test", context, workingDirectory.then("build/test-files"))
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

    private fun serializer(
        context: SettingContext,
        scanner: RecordingScanner,
        inlineScanOnDeserialize: Boolean = false,
        foreignUrlHandling: ForeignUrlHandling = ForeignUrlHandling.ERROR,
    ): ExternalServerFileSerializer {
        val fs = KotlinxIoExternalFileSystem("test", context, workingDirectory.then("build/test-files"))
        return ExternalServerFileSerializer(
            clock = Clock.System,
            scanners = listOf(scanner),
            fileSystems = listOf(fs),
            inlineScanOnDeserialize = inlineScanOnDeserialize,
            foreignUrlHandling = foreignUrlHandling,
            onUse = {},
            key = CryptographyProvider.Default.get(HMAC).keyGenerator().generateKeyBlocking()
        )
    }

    /**
     * Default path: deserializing a signed `future:` URL must NOT scan, copy, or upload — it only
     * parses/validates the URL and returns the ready-location reference. The recording scanner
     * proves no scanning (hence no blocking file I/O) occurred.
     */
    @Test
    fun futureDeserializeDefaultDoesNoIo() {
        val context = TestSettingContext()
        val scanner = RecordingScanner(context)
        val ser = serializer(context, scanner)
        val json = Json { serializersModule = serializersModuleOf(ser) }
        val key = "no-io.txt"
        // Intentionally do NOT create the underlying file; if deserialize tried to copy/scan it would fail.
        val signed = ser.certifyForUse(key, 1.minutes)
        val result = json.decodeFromJsonElement(ser, JsonPrimitive(signed))
        assertFalse(scanner.scanCalled, "Default deserialize must not invoke the scanner")
        assertTrue(result.location.contains("uploaded"), "Should reference the ready location: ${result.location}")
    }

    /**
     * Default path rejects inline `data:` URLs (which would require a blocking upload) instead of
     * uploading them on the deserializing thread.
     */
    @Test
    fun dataUrlRejectedByDefault() {
        val context = TestSettingContext()
        val scanner = RecordingScanner(context)
        val ser = serializer(context, scanner)
        val json = Json { serializersModule = serializersModuleOf(ser) }
        val dataUrl = "data:text/plain;base64,VEVTVA=="
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromJsonElement(ser, JsonPrimitive(dataUrl))
        }
        assertFalse(scanner.scanCalled, "Rejected data URL must not be scanned")
    }

    /**
     * Flag-ON path preserves the OLD behavior: deserializing a `future:` URL copies from jail and
     * scans inline. The recording scanner confirms scanning happened.
     */
    @Test
    fun futureDeserializeWithFlagScansInline() {
        val context = TestSettingContext()
        val scanner = RecordingScanner(context)
        val ser = serializer(context, scanner, inlineScanOnDeserialize = true)
        val json = Json { serializersModule = serializersModuleOf(ser) }
        val key = "with-flag.txt"
        runBlocking {
            ser.jail.then(key).put(TypedData.text("TEST CONTENT", MediaType.Text.Plain))
        }
        val signed = ser.certifyForUse(key, 1.minutes)
        val result = json.decodeFromJsonElement(ser, JsonPrimitive(signed))
        assertTrue(scanner.scanCalled, "Flag-ON deserialize must scan inline (legacy behavior)")
        assertTrue(result.location.contains("uploaded"))
    }

    /**
     * Flag-ON path preserves the OLD behavior for `data:` URLs: the inline data is scanned and
     * uploaded during deserialization.
     */
    @Test
    fun dataUrlWithFlagUploadsInline() {
        val context = TestSettingContext()
        val scanner = RecordingScanner(context)
        val ser = serializer(context, scanner, inlineScanOnDeserialize = true)
        val json = Json { serializersModule = serializersModuleOf(ser) }
        val dataUrl = "data:text/plain;base64,VEVTVA=="
        val result = json.decodeFromJsonElement(ser, JsonPrimitive(dataUrl))
        assertTrue(scanner.scanCalled, "Flag-ON deserialize must scan inline data (legacy behavior)")
        assertTrue(result.location.contains("uploaded"))
    }

    private val foreignUrl = "https://malware.example.com/evil.exe"

    /** A ServerFile whose location belongs to the configured file system, as it would be stored. */
    private fun knownRootFile(ser: ExternalServerFileSerializer): ServerFile =
        ser.ready.then("known.txt").serverFile

    @Test
    fun foreignUrlWarnPassesThrough() {
        val context = TestSettingContext()
        val ser = serializer(context, RecordingScanner(context), foreignUrlHandling = ForeignUrlHandling.WARN)
        val json = Json { serializersModule = serializersModuleOf(ser) }
        val encoded = json.encodeToJsonElement(ser, ServerFile(foreignUrl))
        assertEquals(foreignUrl, encoded.jsonPrimitive.content, "WARN mode should pass the foreign url through")
    }

    @Test
    fun foreignUrlCensorYieldsBlank() {
        val context = TestSettingContext()
        val ser = serializer(context, RecordingScanner(context), foreignUrlHandling = ForeignUrlHandling.CENSOR)
        val json = Json { serializersModule = serializersModuleOf(ser) }
        val encoded = json.encodeToJsonElement(ser, ServerFile(foreignUrl))
        assertEquals("", encoded.jsonPrimitive.content, "CENSOR mode should blank the foreign url")
    }

    @Test
    fun foreignUrlErrorThrows() {
        // ERROR is the default, but set it explicitly for clarity.
        val context = TestSettingContext()
        val ser = serializer(context, RecordingScanner(context), foreignUrlHandling = ForeignUrlHandling.ERROR)
        val json = Json { serializersModule = serializersModuleOf(ser) }
        assertFailsWith<IllegalArgumentException> {
            json.encodeToJsonElement(ser, ServerFile(foreignUrl))
        }
    }

    @Test
    fun foreignUrlDefaultIsError() {
        // No explicit configuration: the companion default must reject foreign urls.
        val context = TestSettingContext()
        val ser = serializer(context, RecordingScanner(context))
        val json = Json { serializersModule = serializersModuleOf(ser) }
        assertFailsWith<IllegalArgumentException> {
            json.encodeToJsonElement(ser, ServerFile(foreignUrl))
        }
    }

    @Test
    fun knownRootFileUnaffectedInEveryMode() {
        val context = TestSettingContext()
        ForeignUrlHandling.entries.forEach { entry ->
            val ser = serializer(context, RecordingScanner(context), foreignUrlHandling = entry)
            val json = Json { serializersModule = serializersModuleOf(ser) }
            val encoded = json.encodeToJsonElement(ser, knownRootFile(ser)).jsonPrimitive.content
            // Known-root files always produce a non-blank, non-foreign signed url regardless of mode.
            assertTrue(encoded.isNotBlank(), "Known-root file should serialize to a url in mode $entry")
            assertTrue(
                encoded.contains("known.txt"),
                "Known-root file should serialize to its own url in mode $entry: $encoded"
            )
        }
    }
}