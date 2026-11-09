package com.lightningkite.services.files

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.kfile.KFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Regression tests for individual entries in [CheckMimeFileScanner]'s signature table.
 */
class CheckMimeFileScannerSignatureTest {

    val context = TestSettingContext()
    val system = KotlinxIoExternalFileSystem("name", context, KFile("build/test-files/"))
    val scanner = CheckMimeFileScanner("test", context)

    private fun hex(s: String): ByteArray = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    private suspend fun scan(bytes: ByteArray, type: MediaType) {
        val file = ExternalFile(system, ExternalPath("CheckMimeFileScannerSignatureTest-test-file"))
        file.put(TypedData.bytes(bytes, type))
        scanner.scan(file)
    }

    @Test
    fun octetStreamAcceptsAnyBinary(): Unit = runBlocking {
        // application/octet-stream means "arbitrary bytes"; it used to require an ELF header.
        scan(ByteArray(32) { it.toByte() }, MediaType.Application.OctetStream)
        scan(hex("7F 45 4C 46 02 01 01") + ByteArray(9), MediaType.Application.OctetStream)
    }

    @Test
    fun exifJpegMatchesExifSignature(): Unit = runBlocking {
        // FF D8 FF E1 ?? ?? 45 78 69 66 00 00. Pad the bytes after the segment marker so no other JPEG entry
        // could match by accident.
        scan(hex("FF D8 FF E1 00 10 45 78 69 66 00 00 00 00 00 00"), MediaType.Image.JPEG)
    }

    @Test
    fun webmMatchesEbmlSignature(): Unit = runBlocking {
        scan(hex("1A 45 DF A3 9F 42 86 81 01 42 F7 81 01 42 F2 81"), MediaType.Video.WEBM)
        assertFailsWith<FileScanException> {
            scan(hex("6D 6F 6F 76 00 00 00 00 00 00 00 00 00 00 00 00"), MediaType.Video.WEBM)
        }
    }
}
