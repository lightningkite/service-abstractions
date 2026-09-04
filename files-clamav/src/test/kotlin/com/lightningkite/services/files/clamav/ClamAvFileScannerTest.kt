package com.lightningkite.services.files.clamav

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.*
import com.lightningkite.services.kfile.KFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ClamAvFileScannerTest {

    val context = TestSettingContext()
    val system = KotlinxIoExternalFileSystem("name", context, KFile("build/test-files/"))

    @Test
    fun test() = runBlocking<Unit> {
        try {
            Runtime.getRuntime().exec(arrayOf("clamd", "--version"))
        } catch (e: Exception) {
            println("Could not find clamav on this machine.  Exiting.")
            return@runBlocking
        }
        // Force the clamav:// URL scheme to register before constructing Settings.
        // The bare reference is the point of this line.
        @Suppress("UNUSED_EXPRESSION")
        ClamAvFileScanner
        val x = FileScanner.Settings("clamav://localhost/UNIX")("test", context)
        val file = ExternalFile(system, ExternalPath("ClamAvFileScannerTest-test-file"))
        file.put(TypedData.text("Some sample text", MediaType.Text.Plain))
        x.scan(file)
        try {
            file.put(
                TypedData.text(
                    "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*",
                    MediaType.Text.Plain
                )
            )
            assertFailsWith<FileScanException> {
                x.scan(file)
            }
        } finally {
            file.delete()
        }
    }
}