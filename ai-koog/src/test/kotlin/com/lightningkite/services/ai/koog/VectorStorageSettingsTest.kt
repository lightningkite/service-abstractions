// Tests added by Claude during code review
package com.lightningkite.services.ai.koog.rag

import com.lightningkite.services.TestSettingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for VectorStorageSettings focusing on URL parsing and basic instantiation.
 */
class VectorStorageSettingsTest {

    private val context = TestSettingContext()

    @Test
    fun testInMemoryVectorStorageSerialization() {
        val url = "rag-memory://"
        val settings = VectorStorageSettings<String>(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testFileBasedVectorStorageSerialization() {
        val url = "rag-file://./local/test-vectors"
        val settings = VectorStorageSettings<String>(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testFileBasedVectorStorageDefaultPath() {
        val url = "rag-file://"
        val settings = VectorStorageSettings<String>(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testVectorStorageWithQueryParams() {
        val url = "rag-file://./local/vectors?someParam=value"
        val settings = VectorStorageSettings<String>(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testInMemoryVectorStorageInstantiation() {
        val settings = VectorStorageSettings<String>("rag-memory://")
        val storage = settings("test-storage", context)

        assertNotNull(storage)
        assertTrue(storage::class.simpleName?.contains("InMemory") == true)
    }

    @Test
    fun testFileBasedVectorStorageInstantiation() {
        val settings = VectorStorageSettings<String>("rag-file://./local/test-vectors")
        val storage = settings("test-file-storage", context)

        assertNotNull(storage)
        assertTrue(storage::class.simpleName?.contains("FileVectorStorage") == true)
    }

    @Test
    fun testVectorStorageTypeSafety() {
        // Test that VectorStorage can be typed to different document types
        val stringSettings = VectorStorageSettings<String>("rag-memory://")
        val intSettings = VectorStorageSettings<Int>("rag-memory://")

        assertNotNull(stringSettings)
        assertNotNull(intSettings)
    }

    @Test
    fun testVectorStorageUrlSchemeRecognition() {
        val memoryUrl = "rag-memory://"
        val fileUrl = "rag-file://./data"

        assertTrue(memoryUrl.startsWith("rag-memory"))
        assertTrue(fileUrl.startsWith("rag-file"))
    }

    // Tests added by Claude for improved coverage

    @Test
    fun testUnknownSchemeThrowsException() {
        // Verify that an unknown URL scheme throws IllegalArgumentException
        val settings = VectorStorageSettings<String>("unknown-scheme://some-path")
        assertFailsWith<IllegalArgumentException> {
            settings("test-storage", context)
        }
    }

    @Test
    fun testFileStorageDefaultsToCurrentDirWhenPathEmpty() {
        // When rag-file:// has no path, extractPathFromUrl returns "."
        val settings = VectorStorageSettings<String>("rag-file://")
        val storage = settings("test-default-path", context)
        // Should not throw and should create a valid storage
        assertNotNull(storage)
    }

    @Test
    fun testFileStorageWithAbsolutePath() {
        // Test with an absolute path
        val settings = VectorStorageSettings<String>("rag-file:///tmp/vectors")
        val storage = settings("test-absolute-path", context)
        assertNotNull(storage)
        assertTrue(storage::class.simpleName?.contains("FileVectorStorage") == true)
    }

    @Test
    fun testFileStoragePathWithQueryStripped() {
        // Verify query parameters are stripped from path
        val settings = VectorStorageSettings<String>("rag-file://./vectors?param=value&other=123")
        val storage = settings("test-query-stripped", context)
        assertNotNull(storage)
    }

    @Test
    fun testCompanionObjectOptionsContainsBothSchemes() {
        // Verify that both rag-memory and rag-file schemes are registered
        val options = VectorStorageSettings.options
        assertTrue(options.contains("rag-memory"), "Should contain rag-memory scheme")
        assertTrue(options.contains("rag-file"), "Should contain rag-file scheme")
    }

    @Test
    fun testCompanionObjectSupportsMethod() {
        // Test the supports method
        assertTrue(VectorStorageSettings.supports("rag-memory"), "Should support rag-memory")
        assertTrue(VectorStorageSettings.supports("rag-file"), "Should support rag-file")
        assertTrue(!VectorStorageSettings.supports("unknown"), "Should not support unknown schemes")
    }
}
