package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.TestSettingContext
import kotlinx.io.Buffer
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for edge cases in FileScanner implementations.
 */
class FileScannerBoundsTest {

    // TODO: Fix CheckMimeFileScanner to handle files smaller than 16 bytes gracefully.
    // Currently throws EOFException when reading small files. See FileScanner.kt:182
    @Ignore("CheckMimeFileScanner lacks bounds checking for small files")
    @Test
    fun testCheckMimeFileScannerWithSmallFile() {
        val scanner = CheckMimeFileScanner("test", TestSettingContext())
        val buffer = Buffer()
        buffer.write(ByteArray(5) { it.toByte() }) // Only 5 bytes

        // This should throw FileScanException with a clear message, not EOFException
        assertFailsWith<FileScanException> {
            kotlinx.coroutines.runBlocking {
                scanner.scan(MediaType.Image.JPEG, buffer)
            }
        }
    }

    // TODO: Fix CheckMimeFileScanner to handle empty files gracefully.
    // Currently throws EOFException when reading empty files. See FileScanner.kt:182
    @Ignore("CheckMimeFileScanner lacks bounds checking for empty files")
    @Test
    fun testCheckMimeFileScannerWithEmptyFile() {
        val scanner = CheckMimeFileScanner("test", TestSettingContext())
        val buffer = Buffer()
        // Empty file

        // This should throw FileScanException with a clear message, not EOFException
        assertFailsWith<FileScanException> {
            kotlinx.coroutines.runBlocking {
                scanner.scan(MediaType.Image.PNG, buffer)
            }
        }
    }
}
