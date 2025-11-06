package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for FileObject operations and edge cases.
 */
class FileObjectOperationsTest {

    private val fileSystem = PublicFileSystem.Settings("file://build/test-files-ops?serveUrl=http://localhost:8080/files")
        .invoke("test", TestSettingContext())

    @Test
    fun testCopyToWithNonExistentSource() = runBlocking {
        val source = fileSystem.root.then("nonexistent-source.txt")
        val destination = fileSystem.root.then("destination.txt")

        // copyTo silently does nothing if source doesn't exist
        try {
            source.copyTo(destination)
            fail("Should have thrown")
        } catch(e: IllegalArgumentException) {
            //OK cool
        }

        // Destination should not exist since source doesn't exist
        assertNull(destination.get(), "Destination should not exist after copying from non-existent source")
    }

    @Test
    fun testMoveToWithExistingFile() = runBlocking {
        val source = fileSystem.root.then("move-source.txt")
        val destination = fileSystem.root.then("move-destination.txt")

        // Create source file
        source.put(TypedData(Data.Text("Test content"), MediaType.Text.Plain))
        assertTrue(source.get() != null, "Source should exist")

        // Move file
        source.moveTo(destination)

        // Check destination exists and source is deleted
        assertTrue(destination.get() != null, "Destination should exist after move")
        assertNull(source.get(), "Source should not exist after move")

        // Cleanup
        destination.delete()
    }

    @Test
    fun testThenRandomGeneratesUniqueNames() {
        val file1 = fileSystem.root.thenRandom("test", "txt")
        val file2 = fileSystem.root.thenRandom("test", "txt")

        assertTrue(file1.name != file2.name, "Random filenames should be unique")
        assertTrue(file1.name.startsWith("test_"), "Filename should start with prefix")
        assertTrue(file1.name.endsWith(".txt"), "Filename should end with extension")
    }

    @Test
    fun testListFiltersInternalFiles() = runBlocking {
        val testDir = fileSystem.root.then("list-test-dir")

        // Create a file in the directory
        val file = testDir.then("visible-file.txt")
        file.put(TypedData(Data.Text("Content"), MediaType.Text.Plain))

        // List should not include .contenttype files
        val files = testDir.list() ?: emptyList()
        assertTrue(files.any { it.name == "visible-file.txt" }, "Should include actual file")
        assertTrue(files.none { it.name.endsWith(".contenttype") }, "Should not include contenttype files")

        // Cleanup
        file.delete()
    }
}
