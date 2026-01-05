package com.lightningkite.services.database.cassandra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for CQL injection prevention and security.
 */
class CqlSecurityTest {

    @Test
    fun testQuoteCqlEscapesDoubleQuotes() {
        // A malicious identifier with embedded double quotes
        val maliciousName = """test"; DROP TABLE users; --"""
        val quoted = maliciousName.quoteCql()

        // Should properly escape the embedded quotes by doubling them
        // Expected: "test""; DROP TABLE users; --"
        // The doubled quotes prevent breaking out of the identifier
        assertEquals(""""test""; DROP TABLE users; --"""", quoted)

        // Verify the entire string stays within the outer quotes (no injection escape)
        assertTrue(quoted.startsWith("\"") && quoted.endsWith("\""), "Not properly quoted")

        // Verify no unescaped single quote exists (would be "; not "";)
        // After doubling, we should have "" not a standalone "
        assertFalse(
            quoted.substring(1, quoted.length - 1).contains(Regex("""(?<!")"(?!")""")),
            "Unescaped quote found - injection possible!"
        )
    }

    @Test
    fun testQuoteCqlHandlesNormalIdentifiers() {
        assertEquals(""""myTable"""", "myTable".quoteCql())
        assertEquals(""""user_id"""", "user_id".quoteCql())
        assertEquals(""""_id"""", "_id".quoteCql())
    }

    @Test
    fun testQuoteCqlHandlesMultipleQuotes() {
        val name = """a"b"c"""
        val quoted = name.quoteCql()
        assertEquals(""""a""b""c"""", quoted)
    }

    @Test
    fun testQuoteCqlHandlesEmptyString() {
        assertEquals("""""""", "".quoteCql())
    }

    @Test
    fun testIndexNamePreventsInjection() {
        // Test that index names are properly escaped
        val tableName = "users"
        val columnName = """email"; DROP TABLE users; --"""

        // Index names should be quoted to prevent injection
        val indexName = "${tableName}_${columnName}_sasi_idx"
        val quotedIndexName = indexName.quoteCql()

        // Should be properly escaped
        assertTrue(quotedIndexName.startsWith("\"") && quotedIndexName.endsWith("\""))
        // Should not contain unescaped quotes that could break out
        assertFalse(
            quotedIndexName.substring(1, quotedIndexName.length - 1).contains(Regex("""(?<!")"(?!")""")),
            "Index name has unescaped quotes - injection possible!"
        )
    }
}
