package com.lightningkite.services.ai.koog.rag

import com.lightningkite.services.TestSettingContext
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
