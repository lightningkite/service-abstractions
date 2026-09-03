package com.lightningkite.services.files

import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.*
import com.lightningkite.services.kfile.workingDirectory
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.serializersModuleOf
import kotlin.test.*

class ExternalServerFileSerializerTest {

    private val context = TestSettingContext()
    private val fs = KotlinxIoExternalFileSystem("test", context, workingDirectory.then("build/test-files"))

    private fun serializer(
        foreignUrlHandling: ForeignUrlHandling = ForeignUrlHandling.ERROR,
        resolveUpload: (String) -> ExternalFile? = { null },
    ): ExternalServerFileSerializer = ExternalServerFileSerializer(
        fileSystems = listOf(fs),
        foreignUrlHandling = foreignUrlHandling,
        resolveUpload = resolveUpload,
    )

    private fun json(ser: ExternalServerFileSerializer) = Json { serializersModule = serializersModuleOf(ser) }

    private fun ExternalServerFileSerializer.decode(raw: String): ServerFile =
        json(this).decodeFromJsonElement(this, JsonPrimitive(raw))

    // --- resolveUpload: the hook the upload endpoint supplies -------------------------------------

    @Test
    fun resolvedUploadIsUsed() {
        val resolved = fs.root.then("uploaded/resolved.txt")
        val ser = serializer(resolveUpload = { if (it == "token") resolved else null })
        assertEquals(resolved.serverFile.location, ser.decode("token").location)
    }

    /**
     * The resolver claiming nothing must not swallow the string: deserialization falls through to the
     * signed-URL handling for the configured file systems.
     */
    @Test
    fun unclaimedStringFallsThroughToFileSystems() {
        val ser = serializer(resolveUpload = { null })
        val file = fs.root.then("fallthrough.txt")
        assertEquals(file.serverFile.location, ser.decode(file.signedUrl).location)
    }

    /**
     * A resolver rejecting a reference - forged, expired, or naming a file that has not been scanned -
     * must fail the whole deserialization rather than being treated as "not mine".
     */
    @Test
    fun rejectedUploadPropagates() {
        val ser = serializer(resolveUpload = { throw IllegalArgumentException("not scanned yet") })
        assertFailsWith<IllegalArgumentException> { ser.decode("token") }
    }

    /** A server with no upload endpoint mounted resolves no references at all. */
    @Test
    fun defaultResolverClaimsNothing() {
        val ser = serializer()
        assertFailsWith<IllegalArgumentException> { ser.decode("future:anything") }
    }

    /**
     * Deserializing performs no I/O, so a reference to a file that does not exist still deserializes.
     * Whether the bytes are there is a question for the code that later reads them.
     */
    @Test
    fun deserializeDoesNoIo() {
        val missing = fs.root.then("uploaded/definitely-absent.txt")
        val ser = serializer(resolveUpload = { missing })
        assertEquals(missing.serverFile.location, ser.decode("token").location)
    }

    /** Storing an inline data URL would require an upload; the client is told where to go instead. */
    @Test
    fun dataUrlRejected() {
        val ser = serializer()
        val message = assertFailsWith<IllegalArgumentException> {
            ser.decode("data:text/plain;base64,VEVTVA==")
        }.message
        assertTrue(message!!.contains("upload endpoint"), "Error should point at the upload endpoint: $message")
    }

    // --- foreign url handling on serialize --------------------------------------------------------

    private val foreignUrl = "https://malware.example.com/evil.exe"

    private fun encode(ser: ExternalServerFileSerializer, file: ServerFile): String =
        json(ser).encodeToJsonElement(ser, file).jsonPrimitive.content

    @Test
    fun foreignUrlWarnPassesThrough() {
        val ser = serializer(foreignUrlHandling = ForeignUrlHandling.WARN)
        assertEquals(foreignUrl, encode(ser, ServerFile(foreignUrl)), "WARN mode should pass the foreign url through")
    }

    @Test
    fun foreignUrlCensorYieldsBlank() {
        val ser = serializer(foreignUrlHandling = ForeignUrlHandling.CENSOR)
        assertEquals("", encode(ser, ServerFile(foreignUrl)), "CENSOR mode should blank the foreign url")
    }

    @Test
    fun foreignUrlErrorThrows() {
        val ser = serializer(foreignUrlHandling = ForeignUrlHandling.ERROR)
        assertFailsWith<IllegalArgumentException> { encode(ser, ServerFile(foreignUrl)) }
    }

    @Test
    fun foreignUrlDefaultIsError() {
        assertFailsWith<IllegalArgumentException> { encode(serializer(), ServerFile(foreignUrl)) }
    }

    @Test
    fun knownRootFileUnaffectedInEveryMode() {
        ForeignUrlHandling.entries.forEach { entry ->
            val ser = serializer(foreignUrlHandling = entry)
            val encoded = encode(ser, fs.root.then("uploaded/known.txt").serverFile)
            // Known-root files always produce a non-blank, non-foreign signed url regardless of mode.
            assertTrue(encoded.isNotBlank(), "Known-root file should serialize to a url in mode $entry")
            assertTrue(
                encoded.contains("known.txt"),
                "Known-root file should serialize to its own url in mode $entry: $encoded"
            )
        }
    }
}
