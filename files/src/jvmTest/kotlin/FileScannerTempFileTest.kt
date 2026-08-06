package com.lightningkite.services.files

import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.runBlocking
import kotlinx.io.Source
import kotlinx.io.readByteArray
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for FIX 20: [List] `<`[FileScanner]`>.scan()` downloads the item to a new OS temp
 * file so multiple scanners can each read it, but used to never delete that file - every call used to
 * leak one file into the system temp directory permanently.
 */
class FileScannerTempFileTest {

    /** A scanner that actually reads the stream, like a real one would, but never rejects anything. */
    private class NoopScanner(override val name: String, override val context: SettingContext) : FileScanner {
        override fun requires(claimedType: MediaType): FileScanner.Requires = FileScanner.Requires.Whole
        override suspend fun scan(claimedType: MediaType, data: Source) {
            data.use { it.readByteArray() }
        }

        override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
    }

    @Test
    fun multiScannerScanDeletesItsTempFile() = runBlocking {
        val scanners = listOf(
            NoopScanner("a", TestSettingContext()),
            NoopScanner("b", TestSettingContext()),
        )
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val before = tempDir.listFiles()?.toSet() ?: emptySet()

        scanners.scan(TypedData(Data.Text("hello world"), MediaType.Text.Plain))

        val after = tempDir.listFiles()?.toSet() ?: emptySet()
        assertEquals(before, after, "List<FileScanner>.scan() must delete the temp file it downloads to")
    }

    @Test
    fun multiScannerScanDeletesItsTempFileEvenOnFailure() = runBlocking {
        val failing = object : FileScanner {
            override val name = "failing"
            override val context: SettingContext = TestSettingContext()
            override fun requires(claimedType: MediaType): FileScanner.Requires = FileScanner.Requires.Whole
            override suspend fun scan(claimedType: MediaType, data: Source) {
                data.use { it.readByteArray() }
                throw FileScanException("rejected")
            }

            override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
        }
        val scanners = listOf(NoopScanner("a", TestSettingContext()), failing)
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val before = tempDir.listFiles()?.toSet() ?: emptySet()

        try {
            scanners.scan(TypedData(Data.Text("hello world"), MediaType.Text.Plain))
        } catch (e: FileScanException) {
            // expected
        }

        val after = tempDir.listFiles()?.toSet() ?: emptySet()
        assertEquals(before, after, "the temp file must be cleaned up even when a scanner rejects the file")
    }
}
