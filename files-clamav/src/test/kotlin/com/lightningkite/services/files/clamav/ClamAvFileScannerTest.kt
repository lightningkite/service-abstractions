package com.lightningkite.services.files.clamav

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ClamAvFileScannerTest {
    @Test
    fun test() = runBlocking<Unit> {
        try {
            Runtime.getRuntime().exec(arrayOf("clamd", "--version"))
        } catch (e: Exception) {
            println("Could not find clamav on this machine.  Exiting.")
            return@runBlocking
        }
        ClamAvFileScanner
        val x = FileScanner.Settings("clamav://localhost/UNIX")("test", TestSettingContext())
        x.scan(TypedData.text("Some sample text", MediaType.Text.Plain))
        assertFailsWith<FileScanException> {
            x.scan(
                TypedData.text(
                    "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*",
                    MediaType.Text.Plain
                )
            )
        }
    }
}