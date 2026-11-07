package com.lightningkite.services.files.tika

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.*
import com.lightningkite.services.files.tika.TikaFileScanner.Companion.tika
import com.lightningkite.services.kfile.KFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TikaFileScannerTest {

    val context = TestSettingContext()
    val system = KotlinxIoExternalFileSystem("name", context, KFile("build/test-files/"))
    val scanner = TikaFileScanner("test", context)

    private fun hex(s: String): ByteArray = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    private val png = hex("89 50 4E 47 0D 0A 1A 0A") + ByteArray(100)
    private val jpeg = hex("FF D8 FF E1 00 10 45 78 69 66 00 00") + ByteArray(100)
    private val zip = "PK".encodeToByteArray() + hex("03 04") + ByteArray(100)
    private val elf = hex("7F 45 4C 46 02 01 01") + ByteArray(100)
    private val mp4Isom = hex("00 00 00 20 66 74 79 70 69 73 6F 6D 00 00 02 00 69 73 6F 6D") + ByteArray(100)
    private val webm = hex("1A 45 DF A3 9F 42 86 81 01 42 F7 81 01 42 F2 81 04 42 F3 81 08 42 82 84 77 65 62 6D") + ByteArray(100)

    private suspend fun scan(bytes: ByteArray, type: MediaType, scanner: FileScanner = this.scanner) {
        val file = ExternalFile(system, ExternalPath("TikaFileScannerTest-test-file"))
        file.put(TypedData.bytes(bytes, type))
        scanner.scan(file)
    }

    private suspend fun assertRejected(bytes: ByteArray, type: MediaType) {
        assertFailsWith<FileScanException> { scan(bytes, type) }
    }

    @Test
    fun matchingSignaturesPass(): Unit = runBlocking {
        scan(png, MediaType.Image.PNG)
        scan(jpeg, MediaType.Image.JPEG)
        scan(zip, MediaType.Application.Zip)
    }

    @Test
    fun unrelatedTypeFails(): Unit = runBlocking {
        assertRejected(jpeg, MediaType.Image.PNG)
        assertRejected(elf, MediaType.Image.JPEG)
        assertRejected(zip, MediaType.Application.Pdf)
    }

    @Test
    fun missingSignatureFails(): Unit = runBlocking {
        assertRejected(ByteArray(116), MediaType.Image.PNG)
        assertRejected(ByteArray(0), MediaType.Image.PNG)
        // The first five bytes of the PNG signature can never complete it.
        assertRejected(png.copyOf(5), MediaType.Image.PNG)
        // A docx is a zip; bytes Tika can't identify at all aren't one.
        assertRejected(ByteArray(116), MediaType.Application.Docx)
    }

    @Test
    fun textIsReadAsPlainTextAndNeedsNoSignature(): Unit = runBlocking {
        scan("a,b\n1,2\n".encodeToByteArray(), MediaType.Text.CSV)
        scan("""{"a": 1}""".encodeToByteArray(), MediaType.Application.Json)
        scan("hello".encodeToByteArray(), MediaType.Text.Plain)
        scan(ByteArray(0), MediaType.Text.Plain)
        scan(ByteArray(0), MediaType.Application.Json)
    }

    @Test
    fun binaryClaimedAsTextFails(): Unit = runBlocking {
        val binary = ByteArray(116) { it.toByte() }
        assertRejected(binary, MediaType.Text.Plain)
        assertRejected(binary, MediaType.Text.CSV)
        assertRejected(binary, MediaType.Application.Json)
        assertRejected(binary, MediaType.Text.Html)
        assertRejected(binary, MediaType("image/svg+xml"))
    }

    @Test
    fun textClaimedAsBinaryFormatFails(): Unit = runBlocking {
        assertRejected("hello world".encodeToByteArray(), MediaType.Image.PNG)
    }

    @Test
    fun octetStreamAcceptsAnything(): Unit = runBlocking {
        scan(ByteArray(116) { it.toByte() }, MediaType.Application.OctetStream)
        scan(png, MediaType.Application.OctetStream)
        scan(ByteArray(0), MediaType.Application.OctetStream)
    }

    @Test
    fun containerFormatsAcceptTheirGeneralForm(): Unit = runBlocking {
        // Office files are zips, and which one is often only visible deeper in the archive.
        scan(zip, MediaType.Application.Docx)
        scan(zip, MediaType.Application.Pptx)
        // Tika reports the common "isom" MP4 brand as QuickTime, and WebM as Matroska.
        scan(mp4Isom, MediaType.Video.MP4)
        scan(mp4Isom, MediaType.Audio.MP4)
        scan(webm, MediaType.Video.WEBM)
    }

    @Test
    fun aliasesAreNormalized(): Unit = runBlocking {
        scan(jpeg, MediaType("image/jpg"))
        assertRejected(png, MediaType("image/jpg"))
        scan(jpeg, MediaType("IMAGE/JPEG"))
        scan(png, MediaType("image/png; charset=binary"))
    }

    @Test
    fun unknownClaimedTypesAreAllowedByDefault(): Unit = runBlocking {
        scan(elf, MediaType("application/x-something-custom"))
        scan(png, MediaType.Any)
    }

    @Test
    fun unknownClaimedTypesCanBeBlocked(): Unit = runBlocking {
        val blocking = TikaFileScanner("blocking", context, allowUnknownTypes = false)
        assertFailsWith<FileScanException> { scan(elf, MediaType("application/x-something-custom"), blocking) }
        assertFailsWith<FileScanException> { scan(png, MediaType.Any, blocking) }
        // Known types are still checked as usual.
        scan(png, MediaType.Image.PNG, blocking)
        scan("a,b".encodeToByteArray(), MediaType.Text.CSV, blocking)
    }

    @Test
    fun allowedUnknownTypesPassWhenBlocking(): Unit = runBlocking {
        val blocking = TikaFileScanner(
            "blocking", context,
            allowUnknownTypes = false,
            allowedUnknownTypes = setOf(MediaType("application/bson"), MediaType("application/x-protobuf-text"), MediaType("x-custom/*")),
        )
        scan(ByteArray(16), MediaType("application/bson"), blocking)
        scan(ByteArray(16), MediaType("Application/BSON; v=1"), blocking)
        scan(ByteArray(16), MediaType("application/x-protobuf-text"), blocking)
        scan(ByteArray(16), MediaType("x-custom/anything"), blocking)
        assertFailsWith<FileScanException> { scan(ByteArray(16), MediaType("application/x-other"), blocking) }
    }

    @Test
    fun allowedUnknownTypesAreIgnoredWhenAllowing(): Unit = runBlocking {
        // Only a warning is logged; every unknown type still passes, listed or not.
        val allowing = TikaFileScanner("allowing", context, allowedUnknownTypes = setOf(MediaType("application/bson")))
        scan(ByteArray(16), MediaType("application/bson"), allowing)
        scan(ByteArray(16), MediaType("application/x-other"), allowing)
    }

    @Test
    fun missingFileFails(): Unit = runBlocking {
        val file = ExternalFile(system, ExternalPath("TikaFileScannerTest-missing-file"))
        file.delete()
        assertFailsWith<FileScanException> { scanner.scan(file) }
    }

    @Test
    fun settingsUrl(): Unit = runBlocking {
        assertEquals("tika://", FileScanner.Settings.tika().url)
        val default = FileScanner.Settings.tika()("tika", context)
        assertIs<TikaFileScanner>(default)
        assertEquals(true, default.allowUnknownTypes)
    }

    @Test
    fun settingsUrlUnknownTypes(): Unit = runBlocking {
        val url = FileScanner.Settings.tika(
            allowUnknownTypes = false,
            allowedUnknownTypes = setOf(MediaType("application/bson"), MediaType("application/hal+json")),
        )
        assertEquals("tika://?unknownTypes=block&allowedUnknownTypes=application/bson,application/hal+json", url.url)
        val scanner = url("tika", context)
        assertIs<TikaFileScanner>(scanner)
        assertEquals(false, scanner.allowUnknownTypes)
        assertEquals(setOf(MediaType("application/bson"), MediaType("application/hal+json")), scanner.allowedUnknownTypes)

        val allow = FileScanner.Settings("tika://?unknownTypes=allow")("tika", context)
        assertIs<TikaFileScanner>(allow)
        assertEquals(true, allow.allowUnknownTypes)

        assertFailsWith<IllegalArgumentException> { FileScanner.Settings("tika://?unknownTypes=maybe")("tika", context) }
        val ignoredList = FileScanner.Settings("tika://?allowedUnknownTypes=application/bson")("tika", context)
        assertIs<TikaFileScanner>(ignoredList)
        assertEquals(true, ignoredList.allowUnknownTypes)
        assertFailsWith<IllegalArgumentException> {
            FileScanner.Settings("tika://?unknownTypes=block&allowedUnknownTypes=bson")("tika", context)
        }
    }

    @Test
    fun signaturesPastTheStartAreDetected(): Unit = runBlocking {
        // tar's "ustar" marker sits at offset 257.
        val tar = ByteArray(1024).also { "ustar".encodeToByteArray().copyInto(it, 257) }
        scan(tar, MediaType("application/x-tar"))
    }
}
