package com.lightningkite.services.files

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import kotlinx.io.Buffer
import kotlin.test.*

/**
 * Tests for edge cases in FileScanner implementations.
 */
class FileScannerBoundsTest {

    @Test
    fun testCheckMimeFileScannerWithSmallFile() {
        val scanner = CheckMimeFileScanner("test", TestSettingContext())
        val buffer = Buffer()
        buffer.write(ByteArray(5) { it.toByte() }) // Only 5 bytes

        // JPEG has a magic-number signature that can't fit in 5 bytes, so this must fail cleanly
        // with FileScanException (not an EOFException) - the file genuinely cannot be a JPEG.
        assertFailsWith<FileScanException> {
            kotlinx.coroutines.runBlocking {
                scanner.scan(MediaType.Image.JPEG, buffer)
            }
        }
    }

    @Test
    fun testCheckMimeFileScannerWithEmptyFile() {
        val scanner = CheckMimeFileScanner("test", TestSettingContext())
        val buffer = Buffer()
        // Empty file

        // PNG's 8-byte signature can't fit in an empty file, so this must fail cleanly with
        // FileScanException (not an EOFException) - the file genuinely cannot be a PNG.
        assertFailsWith<FileScanException> {
            kotlinx.coroutines.runBlocking {
                scanner.scan(MediaType.Image.PNG, buffer)
            }
        }
    }

    // FIX 39: files under 16 bytes are legitimate and must not fail merely for being short. A
    // declared type with no magic-number signature to check (e.g. text/plain) must pass regardless
    // of size, while a declared type whose signature is longer than the available bytes must fail.

    @Test
    fun testUnsignedTypePassesForEmptyFile() = kotlinx.coroutines.runBlocking {
        val scanner = CheckMimeFileScanner("test", TestSettingContext())
        // text/plain has no magic-number check in CheckMimeFileScanner, so an empty file must pass.
        scanner.scan(MediaType.Text.Plain, Buffer())
    }

    @Test
    fun testUnsignedTypePassesForThreeByteFile() = kotlinx.coroutines.runBlocking {
        val scanner = CheckMimeFileScanner("test", TestSettingContext())
        val buffer = Buffer().apply { write(byteArrayOf(1, 2, 3)) }
        scanner.scan(MediaType.Text.CSV, buffer)
    }

    @Test
    fun testSignedTypeFailsWhenFileShorterThanSignature() {
        val scanner = CheckMimeFileScanner("test", TestSettingContext())
        // PNG's signature is 8 bytes; 5 bytes - even ones matching the start of the real signature -
        // can never complete it, so this must fail.
        val buffer = Buffer().apply { write(byteArrayOf(137.toByte(), 80, 78, 71, 13)) }

        assertFailsWith<FileScanException> {
            kotlinx.coroutines.runBlocking {
                scanner.scan(MediaType.Image.PNG, buffer)
            }
        }
    }

    @Test
    fun testNormalSizeFileBehaviorIsUnchanged(): Unit = kotlinx.coroutines.runBlocking {
        val scanner = CheckMimeFileScanner("test", TestSettingContext())
        val validPng = Buffer().apply {
            write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            write(ByteArray(100)) // trailing "image data"
        }
        scanner.scan(MediaType.Image.PNG, validPng) // must not throw

        val invalidPng = Buffer().apply { write(ByteArray(116)) } // wrong signature, plenty of bytes
        assertFailsWith<FileScanException> {
            scanner.scan(MediaType.Image.PNG, invalidPng)
        }
    }
}
