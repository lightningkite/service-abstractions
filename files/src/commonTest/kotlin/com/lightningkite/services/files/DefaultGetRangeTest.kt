package com.lightningkite.services.files

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.kfile.KFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Every backend in this repo overrides [ExternalFileSystem.getRange], so the interface's default
 * implementation - which any future backend inherits until it has a reason to do better - would
 * otherwise never run. These tests force it, so that a backend relying on it gets the same
 * semantics the conformance suite checks.
 */
class DefaultGetRangeTest {

    /**
     * Delegates everything except the range read, which is routed back to the interface default.
     * [root] has to be re-stated as well, or the delegate would hand out files owned by [backing]
     * and every operation would bypass this wrapper.
     */
    private class FallsBackToDefault(backing: ExternalFileSystem) : ExternalFileSystem by backing {
        override val root: ExternalFile get() = ExternalFile(this, ExternalPath(emptyList()))
        override suspend fun getRange(path: ExternalPath, range: LongRange): TypedData? =
            super.getRange(path, range)
    }

    private val system: ExternalFileSystem = FallsBackToDefault(
        KotlinxIoExternalFileSystem(
            name = "defaultRange",
            context = TestSettingContext(),
            rootKFile = KFile("local/test-default-range"),
            serveUrl = "http://localhost:8080/files",
        )
    )

    @Test
    fun readsWindows() = runTest {
        val file = system.root.then("default-range.txt")
        val message = "0123456789ABCDEF"
        file.put(TypedData(Data.Text(message), MediaType.Text.Plain))
        try {
            val middle = file.getRange(4L..6L)!!
            assertEquals("456", middle.data.text())
            assertEquals(3L, file.getRange(4L..6L)!!.data.size)
            assertEquals(MediaType.Text.Plain, middle.mediaType)

            assertEquals(message, file.getRange(0L..15L)!!.data.text())
            assertEquals(message, file.getRange(0L..99L)!!.data.text(), "should clamp to the end")
            assertEquals("", file.getRange(16L..99L)!!.data.text(), "starting at the end reads nothing")

            assertFailsWith<IllegalArgumentException> { file.getRange(-1L..5L) }
            assertFailsWith<IllegalArgumentException> { file.getRange(5L..1L) }
        } finally {
            file.delete()
        }
        assertNull(system.root.then("default-range-missing.txt").getRange(0L..15L))
    }

    @Test
    fun readsEmptyFile() = runTest {
        val file = system.root.then("default-range-empty.txt")
        file.put(TypedData(Data.Bytes(ByteArray(0)), MediaType.Text.Plain))
        try {
            assertEquals(0L, file.getRange(0L..15L)!!.data.size)
        } finally {
            file.delete()
        }
    }
}
