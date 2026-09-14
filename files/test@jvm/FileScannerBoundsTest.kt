package com.lightningkite.services.files

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.kfile.KFile
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for edge cases in FileScanner implementations.
 */
class FileScannerBoundsTest {

    val context = TestSettingContext()
    val system = KotlinxIoExternalFileSystem("name", context, KFile("build/test-files/"))

    @Test
    fun testCheckMimeFileScannerWithSmallFile(): Unit = runBlocking {
        val scanner = CheckMimeFileScanner("test", context)
        val file = ExternalFile(system, ExternalPath("FileScannerBoundsTest-test-file"))
        file.put(TypedData.bytes(ByteArray(5) { it.toByte() }, MediaType.Image.JPEG))

        // JPEG has a magic-number signature that can't fit in 5 bytes, so this must fail cleanly
        // with FileScanException (not an EOFException) - the file genuinely cannot be a JPEG.
        assertFailsWith<FileScanException> {
            scanner.scan(file)
        }
    }

    @Test
    fun testCheckMimeFileScannerWithEmptyFile(): Unit = runBlocking {
        val scanner = CheckMimeFileScanner("test", context)
        val file = ExternalFile(system, ExternalPath("FileScannerBoundsTest-test-file"))
        // Empty file
        file.put(TypedData.bytes(ByteArray(0), MediaType.Image.PNG))

        // PNG's 8-byte signature can't fit in an empty file, so this must fail cleanly with
        // FileScanException (not an EOFException) - the file genuinely cannot be a PNG.
        assertFailsWith<FileScanException> {
            scanner.scan(file)
        }
    }

    // FIX 39: files under 16 bytes are legitimate and must not fail merely for being short. A
    // declared type with no magic-number signature to check (e.g. text/plain) must pass regardless
    // of size, while a declared type whose signature is longer than the available bytes must fail.

    @Test
    fun testUnsignedTypePassesForEmptyFile(): Unit = runBlocking {
        val scanner = CheckMimeFileScanner("test", context)
        val file = ExternalFile(system, ExternalPath("FileScannerBoundsTest-test-file"))
        file.put(TypedData.bytes(ByteArray(0), MediaType.Text.Plain))
        // text/plain has no magic-number check in CheckMimeFileScanner, so an empty file must pass.
        scanner.scan(file)
    }

    @Test
    fun testUnsignedTypePassesForThreeByteFile(): Unit = runBlocking {
        val scanner = CheckMimeFileScanner("test", context)
        val file = ExternalFile(system, ExternalPath("FileScannerBoundsTest-test-file"))
        file.put(TypedData.bytes(byteArrayOf(1, 2, 3), MediaType.Text.CSV))
        scanner.scan(file)
    }

    @Test
    fun testSignedTypeFailsWhenFileShorterThanSignature(): Unit = runBlocking {
        val scanner = CheckMimeFileScanner("test", context)
        val file = ExternalFile(system, ExternalPath("FileScannerBoundsTest-test-file"))
        // PNG's signature is 8 bytes; 5 bytes - even ones matching the start of the real signature -
        // can never complete it, so this must fail.
        file.put(TypedData.bytes(byteArrayOf(137.toByte(), 80, 78, 71, 13), MediaType.Image.PNG))

        assertFailsWith<FileScanException> {
            scanner.scan(file)
        }
    }

    @Test
    fun testNormalSizeFileBehaviorIsUnchanged(): Unit = runBlocking {
        val scanner = CheckMimeFileScanner("test", context)
        val file = ExternalFile(system, ExternalPath("FileScannerBoundsTest-test-file"))
        file.put(TypedData.bytes(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) + ByteArray(100), MediaType.Image.PNG))
        scanner.scan(file) // must not throw

        file.put(TypedData.bytes(ByteArray(116), MediaType.Image.PNG))
        assertFailsWith<FileScanException> {
            scanner.scan(file)
        }
    }
}
